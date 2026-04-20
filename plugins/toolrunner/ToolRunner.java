///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../shared/Event.java
//DESCRIPTION MK3 Tool Runner — orchestrates tool execution and LLM calls via kernel

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * ToolRunner — a plugin that orchestrates tool execution on behalf of other plugins.
 *
 * Supported tool.invoke payload formats:
 *
 *   Single tool:
 *     { "tool": "WeatherTool", "input": "London" }
 *
 *   Sequential pipeline:
 *     { "pipeline": [
 *         { "tool": "WeatherTool", "input": "London" },
 *         { "llm": true, "prompt": "Summarize this weather data: {result}" }
 *     ]}
 *
 *   Parallel execution:
 *     { "parallel": "WeatherTool", "inputs": ["London", "New York", "Tokyo"] }
 *
 * Invoking from ConsolePlugin: type !WeatherTool London
 */
public class ToolRunner {

    private static final String SOCKET    = "../../kernel/kernel.sock";
    private static final String PLUGIN_ID = "tool-runner";
    private static final Path   TOOLS_DIR = Path.of("../../tools"); // pure tool scripts live in /tools/

    // Pending LLM calls: correlationId → CompletableFuture<response payload>
    private static final Map<String, CompletableFuture<String>> pendingLLM = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        System.out.println("[TOOL-RUNNER] Starting...");

        var config = Event.loadConfig("plugin.json");

        Event.connectAndRun(SOCKET, config, (in, out) -> {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    Event ev = Event.MAPPER.readValue(line, Event.class);
                    switch (ev.type()) {

                        case "tool.invoke" -> {
                            // Process in a virtual thread — the main loop must stay free
                            // to receive llm.result responses while tools/LLM are running
                            final Event event = ev;
                            executor.submit(() -> handleInvoke(event, out));
                        }

                        case "llm.result" -> {
                            // Resolve the CompletableFuture that a workflow step is waiting on
                            if (ev.correlationId() != null) {
                                var future = pendingLLM.remove(ev.correlationId());
                                if (future != null) future.complete(ev.payload());
                                else System.out.println("[TOOL-RUNNER] No pending request for correlationId: " + ev.correlationId());
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

    // -------------------------------------------------------------------------
    // Invocation dispatch
    // -------------------------------------------------------------------------

    private static void handleInvoke(Event ev, BufferedWriter out) {
        String workflowId = ev.workflowId() != null ? ev.workflowId() : UUID.randomUUID().toString();
        System.out.println("[TOOL-RUNNER] Handling tool.invoke — workflow: " + workflowId);
        try {
            JsonNode inv = Event.MAPPER.readTree(ev.payload());
            String result;

            if (inv.has("pipeline")) {
                result = runPipeline(inv.get("pipeline"), ev, workflowId, out);
            } else if (inv.has("parallel")) {
                String toolName = inv.get("parallel").asText();
                List<String> inputs = new ArrayList<>();
                inv.get("inputs").forEach(n -> inputs.add(n.asText()));
                result = runParallel(toolName, inputs);
            } else if (inv.has("tool")) {
                String toolName  = inv.get("tool").asText();
                String toolInput = inv.has("input") ? inv.get("input").asText() : "";
                result = runTool(toolName, toolInput);
            } else {
                result = "Unknown tool.invoke format: " + ev.payload();
            }

            publish(Event.reply(ev, "tool.result", result, PLUGIN_ID), out);

        } catch (Exception e) {
            System.err.println("[TOOL-RUNNER] Error in workflow " + workflowId + ": " + e.getMessage());
            publish(Event.reply(ev, "tool.result", "Error: " + e.getMessage(), PLUGIN_ID), out);
        }
    }

    // -------------------------------------------------------------------------
    // Execution strategies
    // -------------------------------------------------------------------------

    /** Runs steps in order, piping each output as input to the next. */
    private static String runPipeline(JsonNode steps, Event origin, String workflowId, BufferedWriter out)
            throws Exception {
        String result = "";
        for (JsonNode step : steps) {
            if (step.has("llm") && step.get("llm").asBoolean()) {
                // LLM step — route through kernel (Path B), block virtual thread until response
                String prompt = step.has("prompt")
                        ? step.get("prompt").asText().replace("{result}", result)
                        : result;
                result = callLLM(prompt, origin, workflowId, out);
            } else if (step.has("tool")) {
                String toolName  = step.get("tool").asText();
                String toolInput = step.has("input")
                        ? step.get("input").asText().replace("{result}", result)
                        : result;
                result = runTool(toolName, toolInput);
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
                            return input + ": " + runTool(toolName, input);
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

    // -------------------------------------------------------------------------
    // Tool execution (pure script subprocess)
    // -------------------------------------------------------------------------

    private static String runTool(String toolName, String input) throws Exception {
        System.out.println("[TOOL-RUNNER] Executing: " + toolName + " | input: " + input);

        // Pass just the filename — CWD is already TOOLS_DIR, so jbang resolves it correctly.
        // redirectErrorStream(true) merges stderr into stdout so errors are never silently lost.
        Process process = new ProcessBuilder("jbang", toolName + ".java")
                .directory(TOOLS_DIR.toFile())
                .redirectErrorStream(true)
                .start();

        try (var stdin = process.getOutputStream()) {
            if (input != null && !input.isEmpty())
                stdin.write(input.getBytes());
        }

        String output = new String(process.getInputStream().readAllBytes()).trim();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroy();
            throw new RuntimeException("Tool timed out after 30s: " + toolName);
        }
        if (process.exitValue() != 0) {
            throw new RuntimeException("Tool failed (exit " + process.exitValue() + "): " + output);
        }

        System.out.println("[TOOL-RUNNER] Result from " + toolName + ": " + output);
        return output;
    }

    // -------------------------------------------------------------------------
    // LLM call via kernel — Path B (correlationId-based request/response)
    // -------------------------------------------------------------------------

    /**
     * Publishes llm.invoke to the kernel and blocks the current virtual thread
     * until llm.result arrives with the matching correlationId.
     * Safe to call from virtual threads — blocking is cheap.
     */
    private static String callLLM(String prompt, Event origin, String workflowId, BufferedWriter out)
            throws Exception {
        String corrId = UUID.randomUUID().toString();
        var future = new CompletableFuture<String>();
        pendingLLM.put(corrId, future);

        Event request = Event.request(
                "llm.invoke", prompt, PLUGIN_ID, corrId, workflowId, "llm.result");

        System.out.println("[TOOL-RUNNER] Calling LLM — correlationId: " + corrId);
        publish(request, out);

        try {
            String response = future.get(60, TimeUnit.SECONDS);
            System.out.println("[TOOL-RUNNER] LLM responded — correlationId: " + corrId);
            return response;
        } catch (TimeoutException e) {
            pendingLLM.remove(corrId);
            throw new RuntimeException("LLM call timed out after 60s");
        }
    }

    // -------------------------------------------------------------------------
    // Publishing
    // -------------------------------------------------------------------------

    private static void publish(Event event, BufferedWriter out) {
        synchronized (out) {
            try {
                out.write(Event.MAPPER.writeValueAsString(event));
                out.newLine();
                out.flush();
            } catch (Exception e) {
                System.err.println("[TOOL-RUNNER] Error publishing event: " + e.getMessage());
            }
        }
    }
}
