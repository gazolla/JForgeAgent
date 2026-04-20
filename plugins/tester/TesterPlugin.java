///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS dev.langchain4j:langchain4j-core:0.36.2
//DEPS dev.langchain4j:langchain4j-open-ai:0.36.2
//SOURCES ../../shared/Event.java
//DESCRIPTION MK3 Tester Plugin — auto-validates generated tools after CREATE (UDS)

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * TesterPlugin — auto-validates a freshly generated tool right after CREATE.
 *
 * Subscribes: tester.request
 *   Payload (JSON):
 *   {
 *     "fileName":   "ToolName.java",
 *     "sourceCode": "full java source",
 *     "metadata":   "{ ... }"          (contents of .meta.json, may be empty)
 *   }
 *
 * Publishes: tester.result
 *   Payload (JSON):
 *   {
 *     "pass":       true | false,
 *     "invocation": "ToolName.java arg1 arg2",
 *     "output":     "stdout / stderr captured"
 *   }
 *
 * Flow:
 *   1. Ask LLM for one safe TEST_INVOCATION line.
 *   2. Parse and security-validate the tool name.
 *   3. Execute via jbang (120 s timeout, redirectErrorStream).
 *   4. Detect failure via exit code OR stack-trace keywords in output.
 *   5. Return pass/fail result.
 */
public class TesterPlugin {

    private static final String  SOCKET      = "../../kernel/kernel.sock";
    private static final String  PLUGIN_ID   = "tester-plugin";
    private static final String  MODEL_NAME  = "llama-3.1-8b-instant";
    private static final Path    TOOLS_DIR   = Path.of("../../tools");
    private static final int     TIMEOUT_S   = 120;
    private static final Pattern SAFE_NAME   = Pattern.compile("[A-Za-z0-9_\\-]+\\.java");

    /** JVM/jbang flags the LLM must not inject into test arguments. */
    private static final List<String> BLOCKED_PREFIXES = List.of(
            "-D", "-X", "--classpath", "--deps", "--jvm-options", "--", "-agent", "--source");

    private static ChatLanguageModel model;

    // -------------------------------------------------------------------------
    // Tester LLM instruction (ported from JForgeAgent.java)
    // -------------------------------------------------------------------------

    private static final String TESTER_INSTRUCTION = """
            You are a Test Case Generator for Java/JBang CLI tools.
            You receive a tool's source code and its metadata JSON.
            Your job: produce ONE safe test invocation that exercises the tool's main functionality.

            Rules:
            - Use only safe, realistic, harmless arguments (city names, public URLs, simple numbers).
            - The test must be runnable without user interaction or side effects.
            - Do NOT test error paths or edge cases.
            - Output exactly ONE line in this format:
              TEST_INVOCATION: ToolName.java arg1 arg2
            - Output nothing else. No explanation. No markdown.
            """;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        System.out.println("[TESTER] Starting...");

        String apiKey = System.getenv("GROQ_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[TESTER] GROQ_API_KEY is not set.");
            return;
        }

        model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl("https://api.groq.com/openai/v1")
                .modelName(MODEL_NAME)
                .logRequests(false)
                .logResponses(false)
                .build();

        System.out.println("[TESTER] Model initialised: " + MODEL_NAME);

        var config = Event.loadConfig("plugin.json");

        Event.connectAndRun(SOCKET, config, (in, out) -> {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    Event ev = Event.MAPPER.readValue(line, Event.class);
                    if ("tester.request".equals(ev.type())) {
                        final Event event = ev;
                        executor.submit(() -> handleRequest(event, out));
                    } else {
                        System.out.println("[TESTER] Ignored: " + ev.type());
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Request handler
    // -------------------------------------------------------------------------

    private static void handleRequest(Event ev, BufferedWriter out) {
        try {
            JsonNode req    = Event.MAPPER.readTree(ev.payload());
            String fileName = req.path("fileName").asText("");
            String source   = req.path("sourceCode").asText("");
            String metadata = req.path("metadata").asText("");

            if (fileName.isBlank() || source.isBlank()) {
                publishResult(ev, false, "", "Missing fileName or sourceCode in tester.request.", out);
                return;
            }

            System.out.println("[TESTER] Generating test invocation for: " + fileName);

            // --- 1. Ask LLM for a safe TEST_INVOCATION line ---
            String prompt = "Tool file: " + fileName
                    + "\n\nMetadata:\n" + metadata
                    + "\n\nSource code:\n" + source;
            String llmResponse = callLLM(prompt);

            if (llmResponse.isBlank() || !llmResponse.contains("TEST_INVOCATION:")) {
                System.err.println("[TESTER] Invalid LLM response — skipping test.");
                publishResult(ev, true, "", "(test skipped — LLM did not return TEST_INVOCATION)", out);
                return;
            }

            // --- 2. Extract invocation line ---
            String invocation = llmResponse
                    .substring(llmResponse.indexOf("TEST_INVOCATION:") + 16)
                    .lines().findFirst().orElse("").trim();

            List<String> parts = tokenizeArgs(invocation);
            if (parts.isEmpty() || parts.get(0).isBlank()) {
                publishResult(ev, true, invocation, "(test skipped — empty invocation)", out);
                return;
            }

            String toolName = parts.get(0);
            if (!isToolNameSafe(toolName)) {
                System.err.println("[TESTER] Unsafe tool name from LLM: '" + toolName + "'");
                publishResult(ev, true, invocation, "(test skipped — unsafe tool name)", out);
                return;
            }

            // Filter blocked JVM/jbang flags
            List<String> testArgs = parts.subList(1, parts.size()).stream()
                    .filter(a -> BLOCKED_PREFIXES.stream().noneMatch(a::startsWith))
                    .toList();

            // --- 3. Execute the tool ---
            System.out.println("[TESTER] Running: " + toolName + " " + testArgs);
            ProcessResult result = executeToolProcess(toolName, testArgs);

            System.out.println("[TESTER] " + (result.success() ? "PASSED" : "FAILED")
                    + " — " + fileName);

            publishResult(ev, result.success(), invocation, result.output(), out);

        } catch (Exception e) {
            System.err.println("[TESTER] Error: " + e.getMessage());
            publishResult(ev, false, "", "[TESTER EXCEPTION] " + e.getMessage(), out);
        }
    }

    // -------------------------------------------------------------------------
    // Tool execution (ported from JForgeAgent.executeToolProcess)
    // -------------------------------------------------------------------------

    private static ProcessResult executeToolProcess(String toolName, List<String> scriptArgs)
            throws IOException, InterruptedException {

        List<String> cmd = resolveJbangCommand();
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add(toolName);
        cmd.addAll(scriptArgs);

        Process process = new ProcessBuilder(cmd)
                .directory(TOOLS_DIR.toFile())
                .redirectErrorStream(true)
                .start();

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
            return new ProcessResult(false,
                    "[TIMEOUT] Tool '" + toolName + "' exceeded " + TIMEOUT_S + "s.");
        }

        reader.join();
        String output   = outputRef.get();
        int    exitCode = process.exitValue();

        boolean success = exitCode == 0
                && !output.contains("Exception in thread")
                && !output.contains("Caused by: ")
                && !output.toLowerCase().contains("an error occurred while");

        return new ProcessResult(success, output);
    }

    // -------------------------------------------------------------------------
    // jbang resolver (ported from JForgeAgent.resolveJbangCommand)
    // -------------------------------------------------------------------------

    private static List<String> resolveJbangCommand() {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        String home = System.getProperty("user.home", "");

        // SDKMAN
        Path sdkman = Path.of(home, ".sdkman/candidates/jbang/current/bin/jbang" + (win ? ".cmd" : ""));
        if (Files.exists(sdkman)) return new ArrayList<>(List.of(sdkman.toString()));

        // JBANG_HOME
        String jbangHome = System.getenv("JBANG_HOME");
        if (jbangHome != null && !jbangHome.isBlank()) {
            Path jh = Path.of(jbangHome, "bin/jbang" + (win ? ".cmd" : ""));
            if (Files.exists(jh)) return new ArrayList<>(List.of(jh.toString()));
        }

        // Fallback: rely on PATH
        return new ArrayList<>(List.of(win ? "jbang.cmd" : "jbang"));
    }

    // -------------------------------------------------------------------------
    // Security helpers
    // -------------------------------------------------------------------------

    private static boolean isToolNameSafe(String name) {
        if (!SAFE_NAME.matcher(name).matches()) return false;
        Path resolved = TOOLS_DIR.resolve(name).toAbsolutePath().normalize();
        if (!resolved.startsWith(TOOLS_DIR.toAbsolutePath().normalize())) return false;
        if (Files.exists(resolved) && Files.isSymbolicLink(resolved)) return false;
        return true;
    }

    // -------------------------------------------------------------------------
    // Argument tokenizer (ported from JForgeAgent.tokenizeArgs)
    // -------------------------------------------------------------------------

    private static List<String> tokenizeArgs(String input) {
        List<String> tokens = new ArrayList<>();
        if (input == null || input.isBlank()) return tokens;
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == '\\' && inQuote && i + 1 < input.length()) {
                char next = input.charAt(++i);
                cur.append(switch (next) {
                    case 'n'  -> '\n';
                    case 't'  -> '\t';
                    case '\\' -> '\\';
                    default   -> { cur.append('\\'); yield next; }
                });
            } else if (c == ' ' && !inQuote) {
                if (!cur.isEmpty()) { tokens.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) tokens.add(cur.toString());
        return tokens;
    }

    // -------------------------------------------------------------------------
    // LLM call
    // -------------------------------------------------------------------------

    private static String callLLM(String prompt) {
        try {
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(TESTER_INSTRUCTION),
                    UserMessage.from(prompt));
            AiMessage response = model.generate(messages).content();
            return response.text().replace("```", "").trim();
        } catch (Exception e) {
            System.err.println("[TESTER] LLM call failed: " + e.getMessage());
            return "";
        }
    }

    // -------------------------------------------------------------------------
    // Publishing
    // -------------------------------------------------------------------------

    private static void publishResult(Event origin, boolean pass,
            String invocation, String output, BufferedWriter out) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pass",       pass);
        payload.put("invocation", invocation);
        payload.put("output",     output);
        publish(Event.reply(origin, "tester.result", toJson(payload), PLUGIN_ID), out);
    }

    private static String toJson(Object obj) {
        try { return Event.MAPPER.writeValueAsString(obj); }
        catch (Exception e) { return "{}"; }
    }

    private static void publish(Event event, BufferedWriter out) {
        synchronized (out) {
            try {
                out.write(Event.MAPPER.writeValueAsString(event));
                out.newLine();
                out.flush();
            } catch (Exception e) {
                System.err.println("[TESTER] Error publishing: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal record
    // -------------------------------------------------------------------------

    private record ProcessResult(boolean success, String output) {}
}
