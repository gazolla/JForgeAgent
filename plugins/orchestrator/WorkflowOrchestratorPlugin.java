///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../shared/Event.java
//DESCRIPTION MK3 Workflow Orchestrator — central coordinator for all demands (UDS)

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * WorkflowOrchestratorPlugin — the central coordinator of every demand.
 *
 * Receives:
 *   supervisor.response   — WorkflowPlan JSON from SupervisorPlugin
 *   router.response       — action decision from RouterPlugin
 *   coder.result          — code generation result from CoderPlugin
 *   tester.result         — test execution result from TesterPlugin
 *   search.result         — search report from SearcherPlugin
 *   assistant.response    — conversational reply from AssistantPlugin
 *   tool.result           — tool execution result from ToolRunner
 *   memory.result         — history snapshot from MemoryPlugin
 *
 * Publishes:
 *   supervisor.request    — replan request on workflow failure
 *   router.request        — per-step action request to RouterPlugin
 *   coder.request         — code generation request to CoderPlugin
 *   tester.request        — test validation request to TesterPlugin
 *   search.request        — web search request to SearcherPlugin
 *   assistant.request     — conversational request to AssistantPlugin
 *   tool.invoke           — tool execution to ToolRunner
 *   chat.response         — final answer to ConsolePlugin
 *   memory.write          — persist context to MemoryPlugin
 *   artifact.gc           — garbage-collection trigger
 *   workflow.saved        — persist successful workflow plan
 *
 * Concurrency model:
 *   The main kernel read loop stays permanently running on the main thread.
 *   Incoming response events are resolved via a ConcurrentHashMap<corrId, Future>.
 *   Each supervisor.response triggers a new virtual thread for execution.
 *   Within each execution thread, sub-requests block cheaply on CompletableFutures.
 */
public class WorkflowOrchestratorPlugin {

    private static final String SOCKET    = "../../kernel/kernel.sock";
    private static final String PLUGIN_ID = "orchestrator-plugin";

    private static final Path TOOLS_DIR = Path.of("../../tools");

    private static final int MAX_LOOP_ITERATIONS  = 10;
    private static final int MAX_SEARCH_PER_STEP  = 3;
    private static final int MAX_CRASH_RETRIES    = 3;
    private static final int MAX_REPLANS          = 2;
    private static final long GC_THROTTLE_MS      = 60_000L;

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_\\-]+\\.java");
    private static final List<String> BLOCKED_PREFIXES = List.of(
            "-D", "-X", "--classpath", "--deps", "--jvm-options", "--", "-agent", "--source");
    private static final DateTimeFormatter FMT_CLOCK =
            DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy HH:mm:ss");

    /** Pending async request/response futures keyed by correlationId. */
    private static final Map<String, CompletableFuture<Event>> pending =
            new ConcurrentHashMap<>();

    private static BufferedWriter sharedOut;
    private static volatile long  lastGcRun = 0L;

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("[ORCHESTRATOR] Starting...");
        Files.createDirectories(TOOLS_DIR);

        var config = Event.loadConfig("plugin.json");

        Event.connectAndRun(SOCKET, config, (in, out) -> {
            sharedOut = out;
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    Event ev = Event.MAPPER.readValue(line, Event.class);
                    dispatch(ev, executor);
                }
            } finally {
                executor.shutdownNow();
            }
        });
    }

    // =========================================================================
    // Main dispatcher
    // =========================================================================

    private static void dispatch(Event ev, ExecutorService executor) {
        switch (ev.type()) {

            // New demand — start a fresh workflow execution in a virtual thread
            case "supervisor.response" -> {
                if (ev.correlationId() != null && pending.containsKey(ev.correlationId())) {
                    // Replan response from a running workflow thread — resolve its future
                    resolvePending(ev);
                } else {
                    final Event event = ev;
                    executor.submit(() -> handleWorkflow(event));
                }
            }

            // All response events resolve a pending CompletableFuture by correlationId
            case "router.response",
                 "coder.result",
                 "tester.result",
                 "search.result",
                 "assistant.response",
                 "tool.result",
                 "memory.result" -> resolvePending(ev);

            default -> System.out.println("[ORCHESTRATOR] Ignored: " + ev.type());
        }
    }

    private static void resolvePending(Event ev) {
        if (ev.correlationId() == null) return;
        CompletableFuture<Event> future = pending.remove(ev.correlationId());
        if (future != null) future.complete(ev);
    }

    // =========================================================================
    // Workflow entry point (runs in a virtual thread)
    // =========================================================================

    private static void handleWorkflow(Event supervisorEv) {
        try {
            Event.WorkflowPlan plan =
                    Event.MAPPER.readValue(supervisorEv.payload(), Event.WorkflowPlan.class);

            String workflowId = UUID.randomUUID().toString();
            System.out.println("[ORCHESTRATOR] Demand — type=" + plan.type()
                    + " | workflowId=" + workflowId.substring(0, 8));

            String result;
            if (plan.isSimple()) {
                result = runRouterLoop(plan.originalPrompt(), plan.history(),
                        workflowId, supervisorEv);
            } else {
                result = runWorkflowPlan(plan, workflowId, supervisorEv, MAX_REPLANS);
            }

            // Publish final chat.response back to ConsolePlugin
            publishChatResponse(result, supervisorEv);

            // Persist to memory
            publishMemoryWrite("USER: " + plan.originalPrompt());
            publishMemoryWrite("SYSTEM: " + truncate(result, 200));

            // Persist successful workflow plan (multi-step only)
            if (plan.isWorkflow() && !plan.steps().isEmpty()) {
                publishWorkflowSaved(plan, false);
            }

            // Throttled GC trigger
            maybePublishGC();

        } catch (Exception e) {
            System.err.println("[ORCHESTRATOR] handleWorkflow error: " + e.getMessage());
            e.printStackTrace();
            publishChatResponse("An internal error occurred: " + e.getMessage(), supervisorEv);
        }
    }

    // =========================================================================
    // Workflow plan execution with replan support
    // =========================================================================

    private static String runWorkflowPlan(Event.WorkflowPlan plan, String workflowId,
            Event origin, int replansLeft) throws Exception {

        Map<String, String> stepResults = new ConcurrentHashMap<>();
        List<List<Event.WorkflowStep>> layers = buildExecutionLayers(plan.steps());

        System.out.println("[ORCHESTRATOR] Plan: '" + plan.goal()
                + "' | steps=" + plan.steps().size() + " | layers=" + layers.size());

        String lastError = null;

        for (int i = 0; i < layers.size(); i++) {
            List<Event.WorkflowStep> layer = layers.get(i);
            System.out.println("[ORCHESTRATOR] Layer " + (i + 1) + "/" + layers.size()
                    + ": [" + layer.stream().map(Event.WorkflowStep::id)
                                  .collect(Collectors.joining(", ")) + "]");

            boolean layerOk = executeLayer(layer, stepResults, plan.history(), workflowId, origin);
            if (!layerOk) {
                lastError = "Layer " + (i + 1) + " failed — one or more steps produced no output.";
                System.err.println("[ORCHESTRATOR] " + lastError);

                if (replansLeft > 0) {
                    System.out.println("[ORCHESTRATOR] Requesting replan (" + replansLeft + " left)...");
                    Event.WorkflowPlan newPlan = requestReplan(
                            plan.originalPrompt(), plan, lastError, origin, workflowId);
                    if (newPlan != null) {
                        publishWorkflowSaved(plan, true); // audit the failed plan
                        return runWorkflowPlan(newPlan, workflowId, origin, replansLeft - 1);
                    }
                }
                return "Workflow failed: " + lastError;
            }
        }

        // Collect result: last step's output (or combine all)
        String finalResult = plan.steps().isEmpty() ? ""
                : stepResults.getOrDefault(plan.steps().get(plan.steps().size() - 1).id(), "");

        // If last step empty, concatenate all non-empty results
        if (finalResult.isBlank()) {
            finalResult = stepResults.values().stream()
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.joining("\n\n"));
        }

        return finalResult;
    }

    // -------------------------------------------------------------------------
    // Layer execution (sequential or parallel virtual threads)
    // -------------------------------------------------------------------------

    private static boolean executeLayer(List<Event.WorkflowStep> layer,
            Map<String, String> stepResults, String history,
            String workflowId, Event origin) throws Exception {

        if (layer.size() == 1) {
            return executeSingleStep(layer.get(0), stepResults, history, workflowId, origin);
        }

        // Parallel: one virtual thread per step
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Boolean>> futures = layer.stream()
                    .map(step -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return executeSingleStep(step, stepResults, history, workflowId, origin);
                        } catch (Exception e) {
                            System.err.println("[ORCHESTRATOR] Step " + step.id() + " threw: " + e.getMessage());
                            return false;
                        }
                    }, pool))
                    .toList();

            boolean allOk = true;
            for (var f : futures) {
                if (!Boolean.TRUE.equals(f.get())) allOk = false;
            }
            return allOk;
        }
    }

    private static boolean executeSingleStep(Event.WorkflowStep step,
            Map<String, String> stepResults, String history,
            String workflowId, Event origin) throws Exception {

        String goal = resolveChaining(step.goal(), stepResults);
        System.out.println("[ORCHESTRATOR] Step " + step.id() + ": " + truncate(goal, 80));

        String output = runRouterLoop(goal, history, workflowId, origin);
        stepResults.put(step.id(), output);

        if (output.isBlank()) {
            System.err.println("[ORCHESTRATOR] Step " + step.id() + " produced no output.");
            return false;
        }
        return true;
    }

    // =========================================================================
    // Router loop — the core state machine (runs in a virtual thread)
    // =========================================================================

    private static String runRouterLoop(String goal, String history,
            String workflowId, Event origin) throws Exception {

        StepState state = new StepState(goal, history);

        while (!state.taskResolved) {
            if (++state.iterations > MAX_LOOP_ITERATIONS) {
                System.err.println("[ORCHESTRATOR] Loop guard hit for: " + truncate(goal, 60));
                break;
            }

            if (state.cachedTools == null) state.cachedTools = listCachedTools();

            String clock = LocalDateTime.now().format(FMT_CLOCK)
                    + " | Zone: " + java.time.ZoneId.systemDefault();

            // Build RouterState and send to RouterPlugin
            Event.RouterState rs = new Event.RouterState(
                    goal,
                    state.cachedTools,
                    state.ragContext,
                    state.lastError,
                    state.history,
                    clock,
                    state.crashRetries,
                    state.searchCount,
                    state.stepResults);

            String rsJson = Event.MAPPER.writeValueAsString(rs);
            String corrId = UUID.randomUUID().toString();
            CompletableFuture<Event> future = new CompletableFuture<>();
            pending.put(corrId, future);
            publish(Event.request("router.request", rsJson, PLUGIN_ID, corrId, workflowId, "router.response"));

            Event routerEv = awaitResponse(corrId, future, 60);
            if (routerEv == null) {
                System.err.println("[ORCHESTRATOR] Router timed out — using DELEGATE_CHAT fallback.");
                state.result = "Request timed out. Please try again.";
                state.taskResolved = true;
                continue;
            }

            String action  = routerEv.payload().trim();
            int    colon   = action.indexOf(':');
            String command = (colon != -1 ? action.substring(0, colon) : action).trim();

            System.out.println("[ORCHESTRATOR] Router → " + truncate(action, 100));

            switch (command) {
                case "DELEGATE_CHAT" -> handleDelegateChat(goal, state, workflowId, origin);
                case "SEARCH"        -> handleSearch(action.substring(colon + 1).trim(), state, workflowId);
                case "CREATE"        -> handleCreate(action.substring(colon + 1).trim(), state, workflowId, origin);
                case "EDIT"          -> handleEdit(action.substring(colon + 1).trim(), state, workflowId, origin);
                case "EXECUTE"       -> handleExecute(action.substring(colon + 1).trim(), state, workflowId, origin);
                default -> {
                    System.err.println("[ORCHESTRATOR] Unexpected router output: " + action);
                    state.result = "Unexpected router response.";
                    state.taskResolved = true;
                }
            }
        }

        return state.result;
    }

    // =========================================================================
    // Action handlers
    // =========================================================================

    // --- DELEGATE_CHAT -------------------------------------------------------

    private static void handleDelegateChat(String goal, StepState state,
            String workflowId, Event origin) throws Exception {

        Map<String, String> req = new LinkedHashMap<>();
        req.put("prompt",     goal);
        req.put("ragContext", state.ragContext);
        req.put("toolList",   state.cachedTools == null ? "" : state.cachedTools);

        String corrId = UUID.randomUUID().toString();
        CompletableFuture<Event> future = new CompletableFuture<>();
        pending.put(corrId, future);
        publish(Event.request("assistant.request",
                Event.MAPPER.writeValueAsString(req), PLUGIN_ID, corrId, workflowId, "assistant.response"));

        Event assistantEv = awaitResponse(corrId, future, 60);
        if (assistantEv == null) {
            state.result = "Assistant timed out.";
            state.taskResolved = true;
            return;
        }

        String response = assistantEv.payload();

        // GUARDRAIL: if response mentions a cached tool with intent → force EXECUTE
        String guardedTool = detectCachedToolInResponse(response, state.cachedTools);
        if (guardedTool != null) {
            System.out.println("[ORCHESTRATOR] Guardrail: DELEGATE_CHAT mentioned '"
                    + guardedTool + "' — re-routing to EXECUTE.");
            state.ragContext = "ROUTING CORRECTION: Tool '" + guardedTool
                    + "' is cached and MUST be executed directly. "
                    + "Respond with EXECUTE: " + guardedTool + " <args>. "
                    + "Do NOT use DELEGATE_CHAT.\n\n" + state.ragContext;
            return; // loop continues, router will pick EXECUTE next
        }

        state.result = response;
        state.taskResolved = true;
    }

    // --- SEARCH --------------------------------------------------------------

    private static void handleSearch(String query, StepState state,
            String workflowId) throws Exception {

        // Guard: if we already hit the search limit, force DELEGATE_CHAT immediately
        if (state.searchCount >= MAX_SEARCH_PER_STEP) {
            System.err.println("[ORCHESTRATOR] Search guard hit — forcing DELEGATE_CHAT with accumulated RAG context.");
            handleDelegateChat(state.goal, state, workflowId, null);
            return;
        }
        state.searchCount++;

        String corrId = UUID.randomUUID().toString();
        CompletableFuture<Event> future = new CompletableFuture<>();
        pending.put(corrId, future);
        publish(Event.request("search.request", query, PLUGIN_ID, corrId, workflowId, "search.result"));

        System.out.println("[ORCHESTRATOR] Searching: " + query);
        Event searchEv = awaitResponse(corrId, future, 45);
        if (searchEv != null) {
            // Append a directive so the Router uses DELEGATE_CHAT on the next iteration
            state.ragContext = "Query: " + query + "\nResults:\n" + searchEv.payload()
                    + "\n\n[DIRECTIVE: Search results are populated above. "
                    + "Your NEXT action MUST be DELEGATE_CHAT to synthesize the answer.]";
        } else {
            state.ragContext = "Search timed out for: " + query;
        }
        // Loop continues — router will see fresh RAG context + directive
    }

    // --- CREATE --------------------------------------------------------------

    private static void handleCreate(String instruction, StepState state,
            String workflowId, Event origin) throws Exception {

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("mode",        "CREATE");
        req.put("instruction", instruction);
        req.put("lastError",   state.lastError == null ? "" : state.lastError);

        String corrId = UUID.randomUUID().toString();
        CompletableFuture<Event> future = new CompletableFuture<>();
        pending.put(corrId, future);
        publish(Event.request("coder.request",
                Event.MAPPER.writeValueAsString(req), PLUGIN_ID, corrId, workflowId, "coder.result"));

        System.out.println("[ORCHESTRATOR] Coder: CREATE " + truncate(instruction, 60));
        Event coderEv = awaitResponse(corrId, future, 120);
        if (coderEv == null) {
            state.lastError = "Coder timed out.";
            return;
        }

        JsonNode result = Event.MAPPER.readTree(coderEv.payload());
        if (!result.path("success").asBoolean(false)) {
            state.lastError = result.path("error").asText("Unknown coder error.");
            return;
        }

        String fileName        = result.path("fileName").asText("");
        String metadataContent = result.path("metadataContent").asText("");
        state.cachedTools = null; // invalidate cache after new tool

        // Auto-test (skip if already failing — auto-heal path)
        if (state.crashRetries == 0) {
            handleTest(fileName, metadataContent, state, workflowId);
        }
        state.lastError = null; // clear after successful coder pass
    }

    // --- EDIT ----------------------------------------------------------------

    private static void handleEdit(String editPayload, StepState state,
            String workflowId, Event origin) throws Exception {

        int space = editPayload.indexOf(' ');
        String targetTool = space == -1 ? editPayload : editPayload.substring(0, space).trim();
        String changes    = space == -1 ? "Fix or update according to user prompt"
                                        : editPayload.substring(space).trim();

        String existingCode = "";
        try {
            existingCode = Files.readString(TOOLS_DIR.resolve(targetTool));
        } catch (IOException e) {
            System.err.println("[ORCHESTRATOR] Cannot read tool for EDIT: " + targetTool);
        }

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("mode",         "EDIT");
        req.put("instruction",  changes);
        req.put("existingCode", existingCode);
        req.put("lastError",    state.lastError == null ? "" : state.lastError);

        String corrId = UUID.randomUUID().toString();
        CompletableFuture<Event> future = new CompletableFuture<>();
        pending.put(corrId, future);
        publish(Event.request("coder.request",
                Event.MAPPER.writeValueAsString(req), PLUGIN_ID, corrId, workflowId, "coder.result"));

        System.out.println("[ORCHESTRATOR] Coder: EDIT " + targetTool);
        Event coderEv = awaitResponse(corrId, future, 120);
        if (coderEv == null) {
            state.lastError = "Coder timed out on EDIT.";
            return;
        }

        JsonNode result = Event.MAPPER.readTree(coderEv.payload());
        if (!result.path("success").asBoolean(false)) {
            state.lastError = result.path("error").asText("Unknown coder error on EDIT.");
            return;
        }
        state.cachedTools = null;
        state.lastError   = null;
    }

    // --- TEST (after CREATE) ------------------------------------------------

    private static void handleTest(String fileName, String metadataContent,
            StepState state, String workflowId) throws Exception {

        String sourceCode = "";
        try {
            sourceCode = Files.readString(TOOLS_DIR.resolve(fileName));
        } catch (IOException e) {
            System.out.println("[ORCHESTRATOR] Cannot read tool for test — skipping: " + e.getMessage());
            return;
        }

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("fileName",   fileName);
        req.put("sourceCode", sourceCode);
        req.put("metadata",   metadataContent);

        String corrId = UUID.randomUUID().toString();
        CompletableFuture<Event> future = new CompletableFuture<>();
        pending.put(corrId, future);
        publish(Event.request("tester.request",
                Event.MAPPER.writeValueAsString(req), PLUGIN_ID, corrId, workflowId, "tester.result"));

        System.out.println("[ORCHESTRATOR] Tester: " + fileName);
        Event testerEv = awaitResponse(corrId, future, 200);
        if (testerEv == null) {
            System.err.println("[ORCHESTRATOR] Tester timed out — continuing anyway.");
            return;
        }

        JsonNode result = Event.MAPPER.readTree(testerEv.payload());
        boolean pass    = result.path("pass").asBoolean(true);
        String output   = result.path("output").asText("");

        if (pass) {
            System.out.println("[ORCHESTRATOR] Test PASSED: " + fileName);
        } else {
            System.err.println("[ORCHESTRATOR] Test FAILED: " + fileName);
            state.lastError = "[AUTO-TEST FAILED]\n" + output;
            state.crashRetries++;
        }
    }

    // --- EXECUTE -------------------------------------------------------------

    private static void handleExecute(String args, StepState state,
            String workflowId, Event origin) throws Exception {

        List<String> parts = tokenizeArgs(args);
        if (parts.isEmpty()) {
            state.taskResolved = true;
            return;
        }

        String toolName = parts.get(0);
        if (!isToolNameSafe(toolName)) {
            System.err.println("[ORCHESTRATOR] Rejected unsafe tool name: " + toolName);
            state.result = "Security: rejected unsafe tool name '" + toolName + "'.";
            state.taskResolved = true;
            return;
        }

        // Filter blocked JVM/jbang flags
        List<String> safeArgs = parts.subList(1, parts.size()).stream()
                .filter(a -> BLOCKED_PREFIXES.stream().noneMatch(a::startsWith))
                .toList();
        String joinedArgs = String.join(" ", safeArgs);

        // Build tool.invoke payload: support both "input" (stdin) and "args" (CLI)
        Map<String, Object> invokePayload = new LinkedHashMap<>();
        invokePayload.put("tool",  toolName);
        invokePayload.put("input", joinedArgs);
        invokePayload.put("args",  safeArgs);

        String corrId = UUID.randomUUID().toString();
        CompletableFuture<Event> future = new CompletableFuture<>();
        pending.put(corrId, future);
        publish(Event.request("tool.invoke",
                Event.MAPPER.writeValueAsString(invokePayload),
                PLUGIN_ID, corrId, workflowId, "tool.result"));

        System.out.println("[ORCHESTRATOR] Execute: " + toolName + " " + joinedArgs);
        Event toolEv = awaitResponse(corrId, future, 130);

        if (toolEv == null) {
            state.crashRetries++;
            state.lastError = "Tool execution timed out: " + toolName;
            if (state.crashRetries >= MAX_CRASH_RETRIES) state.taskResolved = true;
            return;
        }

        String output  = toolEv.payload();
        boolean failed = output != null && (
                output.contains("Exception in thread") ||
                output.contains("Caused by: ") ||
                output.toLowerCase().contains("an error occurred while") ||
                output.startsWith("Error:"));

        if (!failed) {
            state.result = output;
            state.taskResolved = true;
            System.out.println("[ORCHESTRATOR] Tool succeeded: " + truncate(output, 100));
        } else {
            state.crashRetries++;
            state.lastError = output;
            System.err.println("[ORCHESTRATOR] Tool failed (retry " + state.crashRetries + "): "
                    + truncate(output, 100));
            if (state.crashRetries >= MAX_CRASH_RETRIES) {
                state.result = output; // expose final error as result
                state.taskResolved = true;
            }
        }
    }

    // =========================================================================
    // Replan
    // =========================================================================

    private static Event.WorkflowPlan requestReplan(String originalGoal,
            Event.WorkflowPlan failedPlan, String errorTrace,
            Event origin, String workflowId) throws Exception {

        Map<String, Object> replanReq = new LinkedHashMap<>();
        replanReq.put("goal",       originalGoal);
        replanReq.put("prevPlan",   Event.MAPPER.writeValueAsString(failedPlan));
        replanReq.put("errorTrace", errorTrace);

        String corrId = UUID.randomUUID().toString();
        CompletableFuture<Event> future = new CompletableFuture<>();
        pending.put(corrId, future);
        publish(Event.request("supervisor.request",
                Event.MAPPER.writeValueAsString(replanReq),
                PLUGIN_ID, corrId, workflowId, "supervisor.response"));

        Event supervisorEv = awaitResponse(corrId, future, 90);
        if (supervisorEv == null) {
            System.err.println("[ORCHESTRATOR] Replan timed out.");
            return null;
        }

        try {
            return Event.MAPPER.readValue(supervisorEv.payload(), Event.WorkflowPlan.class);
        } catch (Exception e) {
            System.err.println("[ORCHESTRATOR] Failed to parse replan: " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Topological sort — execution layers
    // =========================================================================

    private static List<List<Event.WorkflowStep>> buildExecutionLayers(
            List<Event.WorkflowStep> steps) {

        Map<String, Event.WorkflowStep> byId = steps.stream()
                .collect(Collectors.toMap(Event.WorkflowStep::id, s -> s));
        Map<String, Integer> levels = new HashMap<>();
        for (Event.WorkflowStep s : steps) computeLevel(s, byId, levels);

        Map<Integer, List<Event.WorkflowStep>> layerMap = new java.util.TreeMap<>();
        for (Event.WorkflowStep s : steps)
            layerMap.computeIfAbsent(levels.get(s.id()), k -> new ArrayList<>()).add(s);

        return new ArrayList<>(layerMap.values());
    }

    private static int computeLevel(Event.WorkflowStep step,
            Map<String, Event.WorkflowStep> byId, Map<String, Integer> levels) {
        if (levels.containsKey(step.id())) return levels.get(step.id());
        int level = step.dependsOn().stream()
                .filter(byId::containsKey)
                .mapToInt(d -> computeLevel(byId.get(d), byId, levels) + 1)
                .max().orElse(0);
        levels.put(step.id(), level);
        return level;
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private static String resolveChaining(String text, Map<String, String> results) {
        if (text == null) return "";
        for (var e : results.entrySet())
            text = text.replace("<<" + e.getKey() + ">>", e.getValue());
        return text;
    }

    private static String listCachedTools() {
        if (!Files.exists(TOOLS_DIR)) return "Empty";
        try {
            Comparator<Path> byMtime = (a, b) -> {
                try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); }
                catch (IOException e) { return 0; }
            };
            List<Path> tools = Files.list(TOOLS_DIR)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted(byMtime)
                    .toList();

            if (tools.isEmpty()) return "Empty";

            StringBuilder sb = new StringBuilder();
            for (Path t : tools) {
                String name = t.getFileName().toString();
                String meta = "";
                Path metaFile = TOOLS_DIR.resolve(
                        name.substring(0, name.length() - 5) + ".meta.json");
                if (Files.exists(metaFile)) {
                    try { meta = Files.readString(metaFile); }
                    catch (IOException ignored) {}
                }
                sb.append(name);
                if (!meta.isBlank()) sb.append(": ").append(meta);
                sb.append("\n");
            }
            return sb.toString().trim();
        } catch (IOException e) {
            return "Empty";
        }
    }

    private static String detectCachedToolInResponse(String response, String cachedTools) {
        if (cachedTools == null || cachedTools.isBlank()) return null;
        Matcher m = SAFE_NAME.matcher(response);
        while (m.find()) {
            String candidate = m.group();
            if (cachedTools.contains(candidate)) {
                int start   = m.start();
                String ctx  = response.substring(Math.max(0, start - 30), start).toLowerCase();
                boolean intent = ctx.contains("use") || ctx.contains("run")
                        || ctx.contains("execute") || ctx.contains("tool")
                        || ctx.contains("script")  || ctx.contains("java");
                if (intent) return candidate;
            }
        }
        return null;
    }

    private static boolean isToolNameSafe(String name) {
        if (!SAFE_NAME.matcher(name).matches()) return false;
        Path resolved = TOOLS_DIR.resolve(name).toAbsolutePath().normalize();
        if (!resolved.startsWith(TOOLS_DIR.toAbsolutePath().normalize())) return false;
        if (Files.exists(resolved) && Files.isSymbolicLink(resolved)) return false;
        return true;
    }

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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // =========================================================================
    // Publishing helpers
    // =========================================================================

    private static void publishChatResponse(String text, Event origin) {
        if (text == null || text.isBlank()) text = "(no result)";
        publish(Event.reply(origin, "chat.response", text, PLUGIN_ID));
    }

    private static void publishMemoryWrite(String entry) {
        publish(Event.of("memory.write", entry, PLUGIN_ID));
    }

    private static void publishWorkflowSaved(Event.WorkflowPlan plan, boolean isReplan) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("plan",     Event.MAPPER.writeValueAsString(plan));
            payload.put("isReplan", isReplan);
            publish(Event.of("workflow.saved", Event.MAPPER.writeValueAsString(payload), PLUGIN_ID));
        } catch (Exception e) {
            System.err.println("[ORCHESTRATOR] Failed to publish workflow.saved: " + e.getMessage());
        }
    }

    private static void maybePublishGC() {
        long now = System.currentTimeMillis();
        if (now - lastGcRun < GC_THROTTLE_MS) return;
        lastGcRun = now;
        String gcPayload = "{\"toolAgeDays\":30,\"maxTools\":10,"
                + "\"workflowAgeDays\":30,\"maxWorkflows\":10}";
        publish(Event.of("artifact.gc", gcPayload, PLUGIN_ID));
        System.out.println("[ORCHESTRATOR] GC trigger published.");
    }

    private static Event awaitResponse(String corrId,
            CompletableFuture<Event> future, int timeoutSeconds) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pending.remove(corrId);
            System.err.println("[ORCHESTRATOR] Timeout (" + timeoutSeconds + "s) for corrId="
                    + corrId.substring(0, 8));
            return null;
        } catch (Exception e) {
            pending.remove(corrId);
            System.err.println("[ORCHESTRATOR] Await error: " + e.getMessage());
            return null;
        }
    }

    private static void publish(Event event) {
        synchronized (sharedOut) {
            try {
                sharedOut.write(Event.MAPPER.writeValueAsString(event));
                sharedOut.newLine();
                sharedOut.flush();
            } catch (Exception e) {
                System.err.println("[ORCHESTRATOR] Error publishing: " + e.getMessage());
            }
        }
    }

    // =========================================================================
    // Mutable loop state (one instance per router loop / step)
    // =========================================================================

    private static class StepState {
        final String  goal;
        final String  history;
        boolean taskResolved = false;
        String  result       = "";
        String  lastError    = null;
        int     crashRetries = 0;
        int     iterations   = 0;
        int     searchCount  = 0;
        String  ragContext   = "";
        String  cachedTools  = null; // null = stale, reloads on demand
        final Map<String, String> stepResults = new HashMap<>();

        StepState(String goal, String history) {
            this.goal    = goal;
            this.history = history == null ? "No previous context." : history;
        }
    }
}
