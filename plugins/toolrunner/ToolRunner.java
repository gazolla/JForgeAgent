///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../shared/Event.java
//DESCRIPTION MK3 Tool Runner — secure orchestration of JBang tool execution (UDS)

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ToolRunner — secure JBang tool execution with full guardrails.
 *
 * Supported tool.invoke payload formats:
 *
 *   Single tool (Orchestrator — CLI args + stdin):
 *     { "tool": "WeatherTool.java", "args": ["London"], "input": "London" }
 *
 *   Single tool (legacy ConsolePlugin — stdin only):
 *     { "tool": "WeatherTool", "input": "London" }
 *
 *   Sequential pipeline:
 *     { "pipeline": [
 *         { "tool": "WeatherTool", "input": "London" },
 *         { "llm": true, "prompt": "Summarize: {result}" }
 *     ]}
 *
 *   Parallel execution:
 *     { "parallel": "WeatherTool", "inputs": ["London", "Tokyo", "NYC"] }
 *
 * Security layers (ported from JForgeAgent):
 *   1. Tool name validation  — regex [A-Za-z0-9_\-]+\.java only
 *   2. Path containment      — resolved path must stay inside TOOLS_DIR
 *   3. Symlink rejection     — symlinks in TOOLS_DIR are blocked
 *   4. Arg sanitisation      — JVM/jbang flags stripped before execution
 *   5. Process timeout       — 120 s hard limit
 *   6. Output file guardrail — non-.java/.meta.json files moved to products/
 */
public class ToolRunner {

    private static final String SOCKET      = "../../kernel/kernel.sock";
    private static final String PLUGIN_ID   = "tool-runner";
    private static final Path   TOOLS_DIR   = Path.of("../../tools");
    private static final Path   PRODUCTS_DIR = Path.of("../../products");
    private static final int    TIMEOUT_S   = 120;

    // ── Security ─────────────────────────────────────────────────────────────
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_\\-]+\\.java");

    private static final List<String> BLOCKED_PREFIXES = List.of(
            "-D", "-X", "--classpath", "--deps", "--jvm-options",
            "--", "-agent", "--source");

    private static final Set<String> GUARDRAIL_IGNORED = Set.of(
            "thumbs.db", "desktop.ini", "ehthumbs.db", "ehthumbs_vista.db");

    // ── Pending LLM calls: correlationId → CompletableFuture<payload> ────────
    private static final Map<String, CompletableFuture<String>> pendingLLM =
            new ConcurrentHashMap<>();

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("[TOOL-RUNNER] Starting...");
        Files.createDirectories(TOOLS_DIR);
        Files.createDirectories(PRODUCTS_DIR);

        var config = Event.loadConfig("plugin.json");

        Event.connectAndRun(SOCKET, config, (in, out) -> {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    Event ev = Event.MAPPER.readValue(line, Event.class);
                    switch (ev.type()) {

                        case "tool.invoke" -> {
                            final Event event = ev;
                            executor.submit(() -> handleInvoke(event, out));
                        }

                        case "llm.result" -> {
                            if (ev.correlationId() != null) {
                                var future = pendingLLM.remove(ev.correlationId());
                                if (future != null) future.complete(ev.payload());
                            }
                        }

                        default -> System.out.println("[TOOL-RUNNER] Ignored: " + ev.type());
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        });
    }

    // =========================================================================
    // Invocation dispatch
    // =========================================================================

    private static void handleInvoke(Event ev, BufferedWriter out) {
        String workflowId = ev.workflowId() != null
                ? ev.workflowId() : UUID.randomUUID().toString();
        System.out.println("[TOOL-RUNNER] Handling tool.invoke — workflow: " + workflowId);

        try {
            JsonNode inv = Event.MAPPER.readTree(ev.payload());
            String result;

            if (inv.has("pipeline")) {
                result = runPipeline(inv.get("pipeline"), ev, workflowId, out);

            } else if (inv.has("parallel")) {
                String toolName = normalise(inv.get("parallel").asText());
                if (!isToolNameSafe(toolName)) {
                    publish(Event.reply(ev, "tool.result",
                            "Security: rejected unsafe tool name '" + toolName + "'", PLUGIN_ID), out);
                    return;
                }
                List<String> inputs = new ArrayList<>();
                inv.get("inputs").forEach(n -> inputs.add(n.asText()));
                result = runParallel(toolName, inputs);

            } else if (inv.has("tool")) {
                String toolName = normalise(inv.get("tool").asText());

                // Security: validate name before touching the filesystem
                if (!isToolNameSafe(toolName)) {
                    publish(Event.reply(ev, "tool.result",
                            "Security: rejected unsafe tool name '" + toolName + "'", PLUGIN_ID), out);
                    return;
                }

                // Prefer CLI args array (from Orchestrator); fall back to stdin input
                if (inv.has("args") && inv.get("args").isArray()) {
                    List<String> cliArgs = new ArrayList<>();
                    inv.get("args").forEach(n -> cliArgs.add(n.asText()));

                    // Filter blocked JVM/jbang flags
                    List<String> safeArgs = cliArgs.stream()
                            .filter(a -> {
                                boolean blocked = BLOCKED_PREFIXES.stream().anyMatch(a::startsWith);
                                if (blocked) System.out.println(
                                        "[TOOL-RUNNER] Stripped blocked flag: " + a);
                                return !blocked;
                            })
                            .collect(Collectors.toList());

                    result = runToolWithArgs(toolName, safeArgs);
                } else {
                    // Legacy: stdin input
                    String input = inv.has("input") ? inv.get("input").asText() : "";
                    result = runToolWithStdin(toolName, input);
                }

            } else {
                result = "Unknown tool.invoke format: " + ev.payload();
            }

            publish(Event.reply(ev, "tool.result", result, PLUGIN_ID), out);

        } catch (Exception e) {
            System.err.println("[TOOL-RUNNER] Error in workflow " + workflowId + ": " + e.getMessage());
            publish(Event.reply(ev, "tool.result", "Error: " + e.getMessage(), PLUGIN_ID), out);
        }
    }

    // =========================================================================
    // Execution strategies
    // =========================================================================

    /** Runs steps in order, piping each output as input to the next. */
    private static String runPipeline(JsonNode steps, Event origin,
            String workflowId, BufferedWriter out) throws Exception {
        String result = "";
        for (JsonNode step : steps) {
            if (step.has("llm") && step.get("llm").asBoolean()) {
                String prompt = step.has("prompt")
                        ? step.get("prompt").asText().replace("{result}", result)
                        : result;
                result = callLLM(prompt, origin, workflowId, out);
            } else if (step.has("tool")) {
                String toolName = normalise(step.get("tool").asText());
                if (!isToolNameSafe(toolName)) {
                    result = "Security: rejected unsafe tool name '" + toolName + "'";
                    continue;
                }
                String input = step.has("input")
                        ? step.get("input").asText().replace("{result}", result)
                        : result;
                result = runToolWithStdin(toolName, input);
            }
        }
        return result;
    }

    /** Runs the same tool for every input in parallel, then joins the results. */
    private static String runParallel(String toolName, List<String> inputs) {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<CompletableFuture<String>> futures = inputs.stream()
                    .map(input -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return input + ": " + runToolWithStdin(toolName, input);
                        } catch (Exception e) {
                            return input + ": Error — " + e.getMessage();
                        }
                    }, executor))
                    .toList();
            return futures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.joining("\n"));
        } finally {
            executor.shutdown();
        }
    }

    // =========================================================================
    // Tool execution — CLI args (preferred, from Orchestrator)
    // =========================================================================

    private static String runToolWithArgs(String toolName, List<String> scriptArgs)
            throws IOException, InterruptedException {

        List<String> cmd = resolveJbangCommand();
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add(toolName);
        cmd.addAll(scriptArgs);

        System.out.println("[TOOL-RUNNER] Exec (args): " + cmd);
        return executeProcess(toolName, cmd, null);
    }

    // =========================================================================
    // Tool execution — stdin (legacy / pipeline)
    // =========================================================================

    private static String runToolWithStdin(String toolName, String input)
            throws IOException, InterruptedException {

        List<String> cmd = resolveJbangCommand();
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add(toolName);

        System.out.println("[TOOL-RUNNER] Exec (stdin): " + toolName
                + " | input: " + truncate(input, 60));
        return executeProcess(toolName, cmd, input);
    }

    // =========================================================================
    // Core process runner
    // =========================================================================

    private static String executeProcess(String toolName, List<String> cmd, String stdinInput)
            throws IOException, InterruptedException {

        // Touch mtime for GC aging
        touchMtime(toolName);

        Process process = new ProcessBuilder(cmd)
                .directory(TOOLS_DIR.toFile())
                .redirectErrorStream(true)
                .start();

        // Write stdin if provided (legacy / pipeline mode)
        if (stdinInput != null && !stdinInput.isEmpty()) {
            try (var stdin = process.getOutputStream()) {
                stdin.write(stdinInput.getBytes(StandardCharsets.UTF_8));
            }
        } else {
            process.getOutputStream().close();
        }

        // Read output in a virtual thread so we don't deadlock on large output
        AtomicReference<String> outputRef = new AtomicReference<>("");
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                outputRef.set(new String(
                        process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                outputRef.set("[STREAM READ ERROR] " + e.getMessage());
            }
        });

        if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            String msg = "[TIMEOUT] Tool '" + toolName + "' exceeded " + TIMEOUT_S + "s.";
            System.err.println("[TOOL-RUNNER] " + msg);
            return msg;
        }

        reader.join();
        String output   = outputRef.get().trim();
        int    exitCode = process.exitValue();

        System.out.println("[TOOL-RUNNER] " + toolName
                + " exit=" + exitCode + " output=" + truncate(output, 100));

        // Guardrail: move any non-tool output files to products/
        guardrailMoveOutputFiles();

        if (exitCode != 0) {
            return "Error (exit " + exitCode + "): " + output;
        }
        return output;
    }

    // =========================================================================
    // LLM call via kernel (pipeline mode)
    // =========================================================================

    private static String callLLM(String prompt, Event origin,
            String workflowId, BufferedWriter out) throws Exception {
        String corrId = UUID.randomUUID().toString();
        var future = new CompletableFuture<String>();
        pendingLLM.put(corrId, future);

        Event request = Event.request(
                "llm.invoke", prompt, PLUGIN_ID, corrId, workflowId, "llm.result");

        System.out.println("[TOOL-RUNNER] Calling LLM — corrId: " + corrId.substring(0, 8));
        publish(request, out);

        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingLLM.remove(corrId);
            throw new RuntimeException("LLM call timed out after 60s");
        }
    }

    // =========================================================================
    // Security
    // =========================================================================

    private static boolean isToolNameSafe(String name) {
        if (!SAFE_NAME.matcher(name).matches()) return false;
        Path resolved = TOOLS_DIR.resolve(name).toAbsolutePath().normalize();
        if (!resolved.startsWith(TOOLS_DIR.toAbsolutePath().normalize())) return false;
        if (Files.exists(resolved) && Files.isSymbolicLink(resolved)) return false;
        return true;
    }

    /**
     * Normalises tool name: adds .java if missing so both "WeatherTool"
     * and "WeatherTool.java" are accepted.
     */
    private static String normalise(String name) {
        if (name == null) return "";
        return name.endsWith(".java") ? name : name + ".java";
    }

    // =========================================================================
    // Output file guardrail (ported from JForgeAgent.guardrailMoveOutputFiles)
    // =========================================================================

    /**
     * After every tool execution: scan tools/ and relocate any file that is
     * not a .java source or .meta.json sidecar to products/.
     * Also removes stray subdirectories created by badly-formed tool paths.
     */
    private static void guardrailMoveOutputFiles() {
        try (var stream = Files.walk(TOOLS_DIR)) {
            stream
                .filter(p -> !p.equals(TOOLS_DIR))
                .sorted(Comparator.reverseOrder()) // depth-first: files before parent dirs
                .forEach(p -> {
                    try {
                        if (Files.isRegularFile(p)) {
                            String name = p.getFileName().toString();
                            if (name.startsWith(".")) return;
                            if (GUARDRAIL_IGNORED.contains(name.toLowerCase())) return;
                            if (name.endsWith(".java") || name.endsWith(".meta.json")) return;

                            // Output file found inside tools/ — relocate to products/
                            Files.createDirectories(PRODUCTS_DIR);
                            Path dest = PRODUCTS_DIR.resolve(p.getFileName());
                            Files.move(p, dest,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            System.out.println("[TOOL-RUNNER] Guardrail: moved "
                                    + name + " → products/");

                        } else if (Files.isDirectory(p)) {
                            Files.deleteIfExists(p);
                            System.out.println("[TOOL-RUNNER] Guardrail: removed stray dir: "
                                    + p.getFileName());
                        }
                    } catch (IOException e) {
                        System.err.println("[TOOL-RUNNER] Guardrail error on " + p + ": "
                                + e.getMessage());
                    }
                });
        } catch (IOException e) {
            System.err.println("[TOOL-RUNNER] Guardrail walk error: " + e.getMessage());
        }
    }

    // =========================================================================
    // jbang resolver (ported from JForgeAgent.resolveJbangCommand)
    // =========================================================================

    private static List<String> resolveJbangCommand() {
        boolean win  = System.getProperty("os.name", "").toLowerCase().contains("win");
        String  home = System.getProperty("user.home", "");

        // SDKMAN (highest priority — explicit installation)
        Path sdkman = Path.of(home, ".sdkman/candidates/jbang/current/bin/jbang"
                + (win ? ".cmd" : ""));
        if (Files.exists(sdkman))
            return new ArrayList<>(List.of(sdkman.toString()));

        // JBANG_HOME env var
        String jbangHome = System.getenv("JBANG_HOME");
        if (jbangHome != null && !jbangHome.isBlank()) {
            for (String ext : win ? new String[]{".cmd", ".ps1", ".exe", ""} : new String[]{""}) {
                Path candidate = Path.of(jbangHome, "bin", "jbang" + ext);
                if (Files.exists(candidate))
                    return new ArrayList<>(List.of(candidate.toString()));
            }
        }

        // PATH fallback
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                for (String ext : win ? new String[]{".cmd", ".exe", ""} : new String[]{""}) {
                    Path candidate = Path.of(dir, "jbang" + ext);
                    if (Files.exists(candidate))
                        return new ArrayList<>(List.of(candidate.toString()));
                }
            }
        }

        // Last resort: rely on OS to find it
        return new ArrayList<>(List.of(win ? "jbang.cmd" : "jbang"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static void touchMtime(String toolName) {
        try {
            long now = System.currentTimeMillis();
            Path javaFile = TOOLS_DIR.resolve(toolName);
            Path metaFile = TOOLS_DIR.resolve(
                    toolName.replace(".java", ".meta.json"));
            if (Files.exists(javaFile))
                Files.setLastModifiedTime(javaFile,
                        java.nio.file.attribute.FileTime.fromMillis(now));
            if (Files.exists(metaFile))
                Files.setLastModifiedTime(metaFile,
                        java.nio.file.attribute.FileTime.fromMillis(now));
        } catch (IOException ignored) {}
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static void publish(Event event, BufferedWriter out) {
        synchronized (out) {
            try {
                out.write(Event.MAPPER.writeValueAsString(event));
                out.newLine();
                out.flush();
            } catch (Exception e) {
                System.err.println("[TOOL-RUNNER] Error publishing: " + e.getMessage());
            }
        }
    }
}
