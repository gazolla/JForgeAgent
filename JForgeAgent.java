///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 26+
//DEPS com.google.adk:google-adk:1.0.0-rc.1
//DEPS org.slf4j:slf4j-simple:2.0.12
//DEPS info.picocli:picocli:4.7.5
//DEPS com.google.code.gson:gson:2.10.1

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.SessionKey;
import com.google.adk.tools.GoogleSearchTool;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import static picocli.CommandLine.Help.Ansi.AUTO;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Command(name = "jforge", mixinStandardHelpOptions = true, version = "JForge V1.0", description = "MCP Metadata Tool Orchestrator - Autonomous Java Agent", headerHeading = "@|bold,underline Usage|@:%n%n", descriptionHeading = "%n@|bold,underline Description|@:%n%n", optionListHeading = "%n@|bold,underline Options|@:%n")
public class JForgeAgent implements Callable<Integer> {

    // ==================== CONSTANTS ====================

    private static final Path TOOLS_DIR = Path.of("tools");
    private static final Path LOGS_DIR = Path.of("logs");
    private static final Path ARTIFACTS_DIR = Path.of("artifacts");
    private static final Path PRODUCTS_DIR = Path.of("products");
    private static final Path MEMORY_DIR = Path.of("memory");
    private static final Path MEMORY_FILE = MEMORY_DIR.resolve("context.json");

    private static final int MAX_MEMORY_ENTRIES_CONST = 20; // kept internal — not user-tunable
    private static final int MAX_HISTORY_CHARS_CONST = 2000;
    private static final int MAX_LOOP_ITERATIONS_CONST = 10;
    private static final int MAX_SEARCH_PER_DEMAND_CONST = 3;
    private static final int MAX_TOOL_TIMEOUT_CONST = 120;

    // ==================== CLI OPTIONS ====================

    @CommandLine.Option(names = {
            "--supervisor-model" }, description = "Model for Supervisor agent (default: ${DEFAULT-VALUE})", defaultValue = "gemini-3.1-pro")
    private String supervisorModel = "gemini-3.1-pro";

    @CommandLine.Option(names = {
            "--router-model" }, description = "Model for Router agent (default: ${DEFAULT-VALUE})", defaultValue = "gemini-3.1-pro")
    private String routerModel = "gemini-3.1-pro";

    @CommandLine.Option(names = {
            "--coder-model" }, description = "Model for Coder agent (default: ${DEFAULT-VALUE})", defaultValue = "gemini-3.1-pro")
    private String coderModel = "gemini-3.1-pro";

    @CommandLine.Option(names = {
            "--assistant-model" }, description = "Model for Assistant agent (default: ${DEFAULT-VALUE})", defaultValue = "gemini-2.5-flash")
    private String assistantModel = "gemini-2.5-flash";

    @CommandLine.Option(names = {
            "--searcher-model" }, description = "Model for Searcher agent (default: ${DEFAULT-VALUE})", defaultValue = "gemini-2.5-flash")
    private String searcherModel = "gemini-2.5-flash";

    @CommandLine.Option(names = {
            "--tester-model" }, description = "Model for Tester agent (default: ${DEFAULT-VALUE})", defaultValue = "gemini-2.5-flash")
    private String testerModel = "gemini-2.5-flash";

    @CommandLine.Option(names = {
            "--max-tools" }, description = "Maximum number of cached tools before GC eviction (default: 10)", defaultValue = "10")
    private int maxTools = 10;

    @CommandLine.Option(names = {
            "--tool-age-days" }, description = "Days before an unused tool is eligible for GC deletion (default: 30)", defaultValue = "30")
    private long maxToolAgeDays = 30;

    @CommandLine.Option(names = {
            "--prompt" }, description = "Run a single prompt non-interactively and exit (CI/CD/pipe mode)")
    private String promptFlag;

    @CommandLine.Option(names = {
            "--skip-test" }, description = "Skip automatic test after CREATE (use for GUI/Swing or hardware-dependent tools)", defaultValue = "false")
    private boolean skipTest = false;

    @CommandLine.Option(names = {
            "--silent" }, description = "Suppress all status/decorative output; print only the final result (for pipe/MCP/A2A use)", defaultValue = "false")
    private boolean silent = false;

    private static final int MAX_MEMORY_ENTRIES = MAX_MEMORY_ENTRIES_CONST;
    private static final int MAX_HISTORY_CHARS = MAX_HISTORY_CHARS_CONST;
    private static final int MAX_LOOP_ITERATIONS = MAX_LOOP_ITERATIONS_CONST;
    private static final int MAX_SEARCH_PER_DEMAND = MAX_SEARCH_PER_DEMAND_CONST;
    private static final int MAX_TOOL_TIMEOUT_SECONDS = MAX_TOOL_TIMEOUT_CONST;

    /**
     * Sort paths by last-modified time descending; IOException -> tie (unreadable
     * mtime).
     */
    private static final Comparator<Path> BY_MTIME_DESC = (p1, p2) -> {
        try {
            return Files.getLastModifiedTime(p2).compareTo(Files.getLastModifiedTime(p1));
        } catch (IOException e) {
            return 0;
        }
    };

    /**
     * OS-specific system/hidden files that the guardrail must never move to
     * products/.
     * Covers Windows (Thumbs.db, desktop.ini) and any future platform artefacts.
     */
    private static final Set<String> GUARDRAIL_IGNORED_FILES = Set.of(
            "thumbs.db", "desktop.ini", "ehthumbs.db", "ehthumbs_vista.db");

    /**
     * JVM/jbang flag prefixes that the LLM must not inject into script arguments.
     */
    private static final List<String> BLOCKED_ARG_PREFIXES = List.of("-D", "-X", "--classpath", "--deps",
            "--jvm-options",
            "--", "-agent", "--source"); // "--" ends jbang flags; "-agent" loads JVM agents; "--source" redirects
                                         // compilation

    /**
     * Pre-compiled once; used in isToolNameSafe() on every tool name validation.
     */
    private static final java.util.regex.Pattern SAFE_TOOL_NAME = java.util.regex.Pattern
            .compile("[A-Za-z0-9_\\-]+\\.java");

    private static final DateTimeFormatter FMT_CLOCK = DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy HH:mm:ss");
    private static final DateTimeFormatter FMT_LOG_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** Workspace topology — computed once; paths are static final and immutable. */
    private static final String WORKSPACE_TOPOLOGY = String.format(
            """
                    Workspace Architecture (Absolute Paths):
                    - TOOLS: %s
                    - LOGS: %s
                    - ARTIFACTS: %s (Instruct tools to use this EXACT ABSOLUTE PATH for temporary data and extractions)
                    - PRODUCTS: %s (Instruct tools to save user-requested final files using this EXACT ABSOLUTE PATH)

                    MANDATORY RULE: When creating tools that write files, you MUST feed them the literal absolute path strings above. Do not use relative paths like '/products'.
                    """,
            TOOLS_DIR.toAbsolutePath().toString().replace("\\", "/"),
            LOGS_DIR.toAbsolutePath().toString().replace("\\", "/"),
            ARTIFACTS_DIR.toAbsolutePath().toString().replace("\\", "/"),
            PRODUCTS_DIR.toAbsolutePath().toString().replace("\\", "/"));

    // ==================== FIELDS ====================

    private Path currentSessionLog;
    private long lastGcRun = 0; // throttle: prevents redundant filesystem scans within the same minute
    private final Deque<String> conversationMemory = new ArrayDeque<>();

    /** Captures the last result text when running in --silent / machine mode. */
    private final StringBuilder resultBuffer = new StringBuilder();

    private Agent supervisor;
    private Agent router;
    private Agent coder;
    private Agent assistant;
    private Agent searcher;
    private Agent tester;

    // ==================== ENTRY POINT ====================

    public static void main(String[] args) {
        System.setProperty("picocli.ansi", "true");
        int exitCode = new CommandLine(new JForgeAgent()).execute(args);
        System.exit(exitCode);
    }

    // ==================== MAIN CICLE ====================

    @Override
    public Integer call() throws Exception {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println(AUTO.string("@|bold,red \u274C Please set the GEMINI_API_KEY environment variable.|@"));
            return 1;
        }
        // Required by google-adk
        System.setProperty("GOOGLE_API_KEY", apiKey);

        Files.createDirectories(TOOLS_DIR);
        Files.createDirectories(LOGS_DIR);
        Files.createDirectories(ARTIFACTS_DIR);
        Files.createDirectories(PRODUCTS_DIR);
        Files.createDirectories(MEMORY_DIR);

        initLogging();
        loadMemory();

        supervisor = new Agent("supervisor", supervisorModel, SUPERVISOR_INSTRUCTION);
        router = new Agent("router", routerModel, ROUTER_INSTRUCTION);
        coder = new Agent("coder", coderModel, CODER_INSTRUCTION);
        assistant = new Agent("assistant", assistantModel, ASSISTANT_INSTRUCTION);
        searcher = new Agent("searcher", searcherModel, SEARCHER_INSTRUCTION, new GoogleSearchTool());
        tester = new Agent("tester", testerModel, TESTER_INSTRUCTION);

        status("@|faint [LLM] supervisor [" + supervisorModel + "]  router [" + routerModel + "]  coder [" + coderModel
                + "]|@");
        status("@|faint [LLM] assistant  [" + assistantModel + "]  searcher [" + searcherModel + "]  tester ["
                + testerModel + "]|@");
        if (promptFlag != null && !promptFlag.isBlank()) {
            if (!silent)
                printWelcome();
            runGarbageCollector();
            logToFile("[USER] " + promptFlag);
            processDemand(promptFlag);
        } else {
            startChatMenu();
        }
        return 0;
    }

    private void printWelcome() {
        status("@|bold,cyan Welcome to JForge V1.0 - Tool Orchestrator.|@");
        status("Available tools are cached in: @|yellow " + TOOLS_DIR.toAbsolutePath() + "|@");
        status("Logs are recorded in:          @|yellow " + LOGS_DIR.toAbsolutePath() + "|@");
        status("Workspace [Products]:          @|yellow " + PRODUCTS_DIR.toAbsolutePath() + "|@");
        status("Workspace [Artifacts]:         @|yellow " + ARTIFACTS_DIR.toAbsolutePath() + "|@\n");
    }

    // ==================== LOGGING ====================

    private void initLogging() {
        String timestamp = LocalDateTime.now().format(FMT_LOG_TS);
        this.currentSessionLog = LOGS_DIR.resolve("session_" + timestamp + ".log");
        logToFile("==== JForgeAgent Orchestration Lifecycle Started ====");
        rotateLogs();
    }

    private void logToFile(String message) {
        if (this.currentSessionLog == null)
            return;
        try {
            Files.writeString(this.currentSessionLog, message + "\n", StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[LOG ERROR] Could not write to session log: " + e.getMessage());
        }
    }

    private void addToMemory(String entry) {
        if (conversationMemory.size() >= MAX_MEMORY_ENTRIES) {
            conversationMemory.pollFirst();
        }
        conversationMemory.addLast(entry);
        saveMemory();
    }

    private void loadMemory() {
        if (!Files.exists(MEMORY_FILE))
            return;
        try {
            List<String> lines = Files.readAllLines(MEMORY_FILE);
            int start = Math.max(0, lines.size() - MAX_MEMORY_ENTRIES);
            lines.subList(start, lines.size()).forEach(conversationMemory::addLast);
            logToFile("[MEMORY] Loaded " + (lines.size() - start) + " entries from persistent memory.");
        } catch (Exception e) {
            logToFile("[WARN] Could not load memory file — starting empty: " + e.getMessage());
        }
    }

    private void saveMemory() {
        try {
            Files.write(MEMORY_FILE, new ArrayList<>(conversationMemory));
        } catch (Exception e) {
            logToFile("[WARN] Could not persist memory: " + e.getMessage());
        }
    }

    private void rotateLogs() {
        try (Stream<Path> stream = Files.list(LOGS_DIR)) {
            stream.filter(p -> p.toString().endsWith(".log"))
                    .sorted(BY_MTIME_DESC)
                    .skip(3)
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            System.err.println(
                                    "[LOG ROTATE ERROR] Could not delete " + p.getFileName() + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            System.err.println("[LOG ROTATE ERROR] Could not list logs directory: " + e.getMessage());
        }
    }

    // ==================== SUPERVISOR WORKFLOW ====================

    /**
     * Main orchestration path: the Supervisor decomposes the user goal into a
     * WorkflowPlan and the WorkflowExecutor runs it. On failure the Supervisor
     * is asked to replan (max {@code MAX_REPLANS} times). If the plan cannot be
     * parsed at all, falls back to the legacy Router loop.
     */
    private void supervisorWorkflow(String userPrompt) throws Exception {
        final int MAX_REPLANS = 2;
        status("@|bold,cyan [SUPERVISOR] Decomposing goal into workflow...|@");

        LoopState state = new LoopState();
        String timestamp = LocalDateTime.now().format(FMT_LOG_TS);

        String rawJson = supervisor.invoke(buildSupervisorPrompt(userPrompt, state));
        WorkflowPlan plan = parseWorkflowPlan(rawJson);
        saveWorkflowPlan(rawJson, timestamp, "");

        if (plan == null || plan.steps().isEmpty()) {
            status("@|bold,yellow [SUPERVISOR] No valid plan produced — falling back to Router mode.|@");
            logToFile("[SUPERVISOR] Plan parse failed — delegating to processDemandRouter.");
            processDemandRouter(userPrompt);
            return;
        }

        logToFile("[SUPERVISOR] Plan '" + plan.goal() + "' | " + plan.steps().size() + " steps");
        status("@|bold,cyan [SUPERVISOR] " + plan.steps().size() + " steps planned for: "
                + truncate(plan.goal(), 80) + "|@");

        for (int replan = 0; replan <= MAX_REPLANS; replan++) {
            boolean ok = new WorkflowExecutor().execute(plan, state);
            if (ok) {
                addToMemory("USER: " + userPrompt);
                addToMemory("SYSTEM (WORKFLOW): " + plan.goal()
                        + " | steps=" + plan.steps().size());
                break;
            }

            if (replan == MAX_REPLANS) {
                status("@|bold,red [SUPERVISOR] Max replans (" + MAX_REPLANS
                        + ") reached. Aborting.|@");
                logToFile("[SUPERVISOR] Max replans exceeded. Last error: "
                        + truncate(state.lastError, 300));
                break;
            }

            status("@|bold,yellow [SUPERVISOR] Replanning (" + (replan + 1) + "/" + MAX_REPLANS
                    + ") due to: " + truncate(state.lastError, 80) + "|@");
            logToFile("[SUPERVISOR] Replan " + (replan + 1) + ". Error: "
                    + truncate(state.lastError, 200));

            String prevError = state.lastError;
            state.lastError = null;

            rawJson = supervisor.invoke(buildSupervisorReplanPrompt(userPrompt, plan, prevError, state));
            plan = parseWorkflowPlan(rawJson);
            saveWorkflowPlan(rawJson, timestamp, "_replan" + (replan + 1));

            if (plan == null || plan.steps().isEmpty()) {
                status("@|bold,red [SUPERVISOR] Replan produced no valid plan. Aborting.|@");
                break;
            }

            logToFile("[SUPERVISOR] New plan after replan " + (replan + 1)
                    + ": " + plan.goal() + " | " + plan.steps().size() + " steps");
        }
    }

    /**
     * Persists the raw WorkflowPlan JSON to logs/workflow_<timestamp><suffix>.json
     */
    private void saveWorkflowPlan(String rawJson, String timestamp, String suffix) {
        if (rawJson == null || rawJson.isBlank())
            return;
        // Extract only the JSON block (strip any prose the LLM may have added)
        int start = rawJson.indexOf('{');
        int end = rawJson.lastIndexOf('}');
        String json = (start != -1 && end > start) ? rawJson.substring(start, end + 1) : rawJson;
        Path file = LOGS_DIR.resolve("workflow_" + timestamp + suffix + ".json");
        try {
            Files.writeString(file, json);
            logToFile("[SUPERVISOR] Workflow plan saved: " + file.getFileName());
        } catch (IOException e) {
            logToFile("[WARN] Could not save workflow plan: " + e.getMessage());
        }
    }

    private String buildSupervisorPrompt(String userGoal, LoopState state) {
        if (state.cacheList == null)
            state.cacheList = listCachedTools().stream()
                    .reduce((a, b) -> a + ",\n" + b).orElse("Empty");
        return String.format("""
                [Workspace Topology]
                %s

                [System Clock]
                %s

                [Cached Tools]
                [%s]

                [Recent Chat History]
                %s

                User Goal: %s

                Generate a WorkflowPlan JSON.
                """,
                WORKSPACE_TOPOLOGY, buildClock(), state.cacheList, buildHistory(), userGoal);
    }

    private String buildSupervisorReplanPrompt(String userGoal, WorkflowPlan failedPlan,
            String error, LoopState state) {
        String prevSteps = failedPlan.steps().stream()
                .map(s -> "  " + s.id() + ": " + truncate(s.goal(), 80))
                .collect(Collectors.joining("\n"));
        return buildSupervisorPrompt(userGoal, state)
                + "\n\nPREVIOUS PLAN FAILED:\n" + prevSteps
                + "\n\nERROR:\n" + error
                + "\n\nGenerate a corrected WorkflowPlan JSON.";
    }

    /**
     * Extracts and parses the JSON WorkflowPlan from a (possibly decorated) LLM
     * response.
     * Returns null on any parse failure — callers must handle gracefully.
     */
    private WorkflowPlan parseWorkflowPlan(String json) {
        if (json == null || json.isBlank())
            return null;

        // Extract the outermost {...} block (LLM may wrap JSON in prose)
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start == -1 || end == -1 || end <= start)
            return null;
        String trimmed = json.substring(start, end + 1);

        try {
            Gson gson = new Gson();
            JsonObject root = gson.fromJson(trimmed, JsonObject.class);
            String goal = jsonStr(root, "goal", "");

            List<WorkflowStep> steps = new ArrayList<>();
            if (root.has("steps")) {
                JsonArray arr = root.getAsJsonArray("steps");
                for (var el : arr) {
                    JsonObject s = el.getAsJsonObject();
                    String id = jsonStr(s, "id", "s" + steps.size());
                    String stepGoal = jsonStr(s, "goal", "");

                    List<String> dependsOn = new ArrayList<>();
                    if (s.has("dependsOn"))
                        for (var d : s.getAsJsonArray("dependsOn"))
                            dependsOn.add(d.getAsString());

                    steps.add(new WorkflowStep(id, stepGoal, dependsOn));
                }
            }
            return new WorkflowPlan(goal, steps);
        } catch (Exception e) {
            logToFile("[SUPERVISOR] WorkflowPlan parse failed: " + e.getMessage()
                    + " | JSON: " + truncate(trimmed, 400));
            return null;
        }
    }

    /** Null-safe string getter for a JsonObject field with a default value. */
    private static String jsonStr(JsonObject obj, String key, String def) {
        return (obj.has(key) && !obj.get(key).isJsonNull()) ? obj.get(key).getAsString() : def;
    }

    // ==================== CHAT MENU ====================

    private void startChatMenu() throws Exception {
        Console console = System.console();
        if (console == null) {
            System.err.println(AUTO
                    .string("@|bold,red \u274C Interactive console is not supported in this environment. Exiting.|@"));
            return;
        }

        printWelcome();
        runGarbageCollector();

        String inputPrompt = AUTO
                .string("@|bold,green \n\uD83E\uDD16 What would you like to achieve? (or 'exit'/'quit'): |@");

        while (true) {
            String userPrompt = console.readLine(inputPrompt);

            if (userPrompt == null || userPrompt.isBlank()
                    || userPrompt.equalsIgnoreCase("exit")
                    || userPrompt.equalsIgnoreCase("quit")) {
                status("@|bold,yellow Shutting down the forge...|@");
                logToFile("[SYSTEM] Shutting down.");
                break;
            }

            logToFile("[USER] " + userPrompt);
            processDemand(userPrompt);
        }
    }

    // ==================== ORCHESTRATION ====================

    /**
     * Entry point for every user request — delegates to the
     * Supervisor+WorkflowExecutor pipeline.
     */
    private void processDemand(String userPrompt) throws Exception {
        supervisorWorkflow(userPrompt);
    }

    /**
     * Legacy Router loop — used as fallback when the Supervisor fails to produce a
     * valid plan.
     */
    private void processDemandRouter(String userPrompt) throws Exception {
        LoopState state = new LoopState();

        while (!state.taskResolved) {
            if (++state.loopIterations > MAX_LOOP_ITERATIONS) {
                status("@|bold,red [LOOP GUARD] Maximum orchestration iterations reached ("
                        + MAX_LOOP_ITERATIONS + "). Aborting demand.|@");
                logToFile("[SYSTEM] Loop guard triggered after " + MAX_LOOP_ITERATIONS + " iterations. Last error: "
                        + truncate(state.lastError, 300));
                break;
            }

            String clock = buildClock();
            if (state.cacheList == null)
                state.cacheList = listCachedTools().stream().reduce((a, b) -> a + ",\n" + b).orElse("Empty");

            String statePrompt = buildStatePrompt(userPrompt, state, clock, state.cacheList);

            status("@|bold,blue [ROUTER] Analyzing Intent and Metadata Schemas...|@");
            String routerAction = router.invoke(statePrompt);
            logToFile("[ROUTER ACTION] " + routerAction);

            // Empty response means the LLM call failed (timeout, quota, network) — not a
            // hallucination.
            if (routerAction.isBlank()) {
                status("@|bold,red [ROUTER] LLM returned empty response (timeout or quota). Aborting demand.|@");
                logToFile("[ERROR] Router returned empty response — likely timeout or rate limit. Demand aborted.");
                state.taskResolved = true;
                continue;
            }

            int colon = routerAction.indexOf(':');
            String command = (colon != -1 ? routerAction.substring(0, colon) : routerAction).trim();

            switch (command) {
                case "DELEGATE_CHAT" -> handleDelegateChat(userPrompt, clock, state);
                case "SEARCH" -> handleSearch(routerAction.substring(colon + 1).trim(), state);
                case "EDIT" -> handleEdit(routerAction.substring(colon + 1).trim(), state);
                case "CREATE" -> handleCreate(routerAction.substring(colon + 1).trim(), state);
                case "EXECUTE" -> handleExecute(routerAction, colon, userPrompt, state);
                default -> {
                    status("@|bold,red [ROUTER] Unexpected response format. Halting. Response: |@" + routerAction);
                    logToFile("[WARN] Unexpected router output (possible hallucination): " + routerAction);
                    state.taskResolved = true;
                }
            }
        }
    }

    // ==================== PROMPT BUILDERS ====================

    private String buildHistory() {
        if (conversationMemory.isEmpty())
            return "No previous context.";
        // Iterate the Deque back-to-front accumulating entries that fit the budget,
        // without copying to ArrayList — uses the native descending iterator of
        // ArrayDeque.
        var it = conversationMemory.descendingIterator();
        int budget = MAX_HISTORY_CHARS;
        var selected = new ArrayDeque<String>();
        while (it.hasNext()) {
            String entry = it.next();
            int len = entry.length() + 1;
            if (budget - len < 0)
                break;
            budget -= len;
            selected.addFirst(entry); // restore original chronological order
        }
        return String.join("\n", selected);
    }

    private String buildClock() {
        return LocalDateTime.now().format(FMT_CLOCK)
                + " | Local System Zone: " + java.time.ZoneId.systemDefault();
    }

    private String buildStatePrompt(String userPrompt, LoopState state, String clock, String cacheList) {
        String fallbackText = state.lastError == null ? "No previous errors."
                : "A FAILURE OCCURRED IN THE LAST EXECUTION WITH THE FOLLOWING TRACE. REQUIRED FIX: " + state.lastError;
        String historyList = buildHistory();
        String ragSection = state.ragContext.isEmpty() ? "No recent searches." : state.ragContext;

        return String.format("""
                [Workspace Topology]
                %s

                [System Clock]
                %s

                [Recent Chat History]
                %s

                [System State]
                Cached Tools (JSON format):
                [%s]

                [RAG Search Results]
                %s

                %s
                Original User Request: %s
                Decide next action: EXECUTE, CREATE, EDIT, SEARCH, or DELEGATE_CHAT.
                """, WORKSPACE_TOPOLOGY, clock, historyList, cacheList, ragSection, fallbackText, userPrompt);
    }

    // ==================== HANDLERS ====================

    private void handleDelegateChat(String userPrompt, String clock, LoopState state) {
        status("@|bold,yellow \uD83D\uDCAC [ASSISTANT] Generating intelligent response...|@");

        String chatMessage = assistant
                .invoke(buildAssistantPrompt(userPrompt, state.ragContext, state.cacheList, clock));

        // ── GUARDRAIL: if the assistant mentions a real cached tool, redirect to
        // EXECUTE ──
        java.util.regex.Matcher m = SAFE_TOOL_NAME.matcher(chatMessage);
        while (m.find()) {
            String candidate = m.group();
            if (isToolNameSafe(candidate) && Files.exists(TOOLS_DIR.resolve(candidate))) {
                status("@|bold,yellow [GUARDRAIL] DELEGATE_CHAT mentioned '" + candidate
                        + "' \u2014 overriding to EXECUTE|@");
                logToFile("[GUARDRAIL] DELEGATE_CHAT referenced cached tool '" + candidate
                        + "'. Redirecting loop to EXECUTE.");
                state.ragContext = "ROUTING CORRECTION: The previous routing decision was wrong. "
                        + "Tool '" + candidate + "' is cached and MUST be executed directly. "
                        + "Respond with EXECUTE: " + candidate + " <args from user prompt>. "
                        + "Do NOT use DELEGATE_CHAT.\n\n" + state.ragContext;
                addToMemory("USER: " + userPrompt);
                return; // skip display and taskResolved — loop will re-route to EXECUTE
            }
        }
        // ── END GUARDRAIL ──

        resultBuffer.setLength(0);
        resultBuffer.append(chatMessage.strip());
        if (silent) {
            System.out.println(chatMessage.strip());
        } else {
            System.out.println(AUTO.string("\n@|cyan " + chatMessage + "|@\n"));
        }
        logToFile("[CHAT RESULT]\n" + chatMessage);

        addToMemory("USER: " + userPrompt);
        addToMemory("SYSTEM (CHAT): " + (chatMessage.length() > 200
                ? chatMessage.substring(0, 200).replace("\n", " ") + "..."
                : chatMessage.replace("\n", " ")));
        state.taskResolved = true;
    }

    private void handleSearch(String query, LoopState state) {
        if (++state.searchCount > MAX_SEARCH_PER_DEMAND) {
            status("@|bold,red [SEARCH GUARD] Maximum searches per demand reached ("
                    + MAX_SEARCH_PER_DEMAND + "). Aborting.|@");
            logToFile("[SYSTEM] Search guard triggered after " + MAX_SEARCH_PER_DEMAND + " searches. Last query: "
                    + query);
            state.taskResolved = true;
            return;
        }
        status("@|bold,cyan \uD83D\uDD0D [WEB SEARCH] Searching infrastructure: |@" + query);
        String searchResult = searchWeb(query);

        state.ragContext = "Query: " + query + "\nResults:\n" + searchResult;
        addToMemory("SYSTEM (SEARCHED): " + query);
        status("@|bold,yellow \uD83D\uDD04 Reloading Orchestrator with fresh contextual knowledge...|@");

        boolean failed = searchResult.isBlank() || searchResult.startsWith("[searcher] LLM API call failed");
        logToFile((failed ? "[SEARCH FAILED] " : "[SEARCH OK] ") + query + "\nOutcome: " + searchResult);
    }

    private void handleEdit(String editPayload, LoopState state) {
        status("@|bold,magenta [CODER] Modifying existing tool -> |@" + editPayload);

        int firstSpace = editPayload.indexOf(' ');
        String targetTool = firstSpace == -1 ? editPayload : editPayload.substring(0, firstSpace).trim();
        String changes = firstSpace == -1 ? "Fix or update according to user prompt"
                : editPayload.substring(firstSpace).trim();

        String existingCode;
        try {
            existingCode = Files.readString(TOOLS_DIR.resolve(targetTool));
        } catch (Exception e) {
            logToFile("[ERROR] Failed to read tool: " + targetTool + " — " + e.getMessage());
            existingCode = "Tool code unreadable/missing.";
        }

        runCoderPipeline(coder.invoke(buildCoderEditPrompt(changes, existingCode, state.lastError)), state, false);
    }

    private void handleCreate(String instruction, LoopState state) {
        status("@|bold,magenta [CODER] Tool missing (or corrupted). Developing new Tool -> |@" + instruction);
        runCoderPipeline(coder.invoke(buildCoderCreatePrompt(instruction, state.lastError)), state, true);
    }

    private void runCoderPipeline(String generatedCode, LoopState state, boolean isCreate) {
        if (generatedCode.isBlank()) {
            state.lastError = "Coder LLM returned empty response (API error). Retrying.";
            return;
        }
        try {
            String savedFileName = handleCodeGeneration(generatedCode);
            state.lastError = null;
            state.cacheList = null;
            if (isCreate) {
                handleTest(savedFileName, extractMetadataFromCode(generatedCode), state);
            }
            runGarbageCollector();
        } catch (IOException e) {
            logToFile("[ERROR] handleCodeGeneration failed: " + e.getMessage());
            state.lastError = "Code generation I/O failure: " + e.getMessage();
        }
        status("@|bold,yellow Returning control to [ROUTER] to invoke the produced tool...|@");
    }

    /**
     * Runs a Tester-agent-generated invocation immediately after CREATE.
     * On failure sets state.lastError + increments crashRetries for auto-heal via
     * EDIT.
     */
    private void handleTest(String fileName, String metadataContent, LoopState state) {
        if (skipTest || state.crashRetries > 0)
            return;

        status("@|bold,cyan [TESTER] Generating test invocation for: |@" + fileName);
        logToFile("[TESTER] Running auto-test for: " + fileName);

        String toolSource;
        try {
            toolSource = Files.readString(TOOLS_DIR.resolve(fileName));
        } catch (IOException e) {
            logToFile("[TESTER] Could not read tool source — skipping test: " + e.getMessage());
            return;
        }

        String testResponse = tester.invoke(
                "Tool file: " + fileName + "\n\nMetadata:\n" + metadataContent + "\n\nSource code:\n" + toolSource);

        if (testResponse.isBlank() || !testResponse.contains("TEST_INVOCATION:")) {
            logToFile("[TESTER] Invalid response from tester agent — skipping test.");
            return;
        }

        String invocation = testResponse.substring(testResponse.indexOf("TEST_INVOCATION:") + 16).trim();
        String[] parts = invocation.split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) {
            logToFile("[TESTER] Empty invocation after parsing — skipping test.");
            return;
        }
        String testToolName = parts[0];
        if (!isToolNameSafe(testToolName)) {
            logToFile("[TESTER] Unsafe tool name from tester agent '" + testToolName + "' — skipping test.");
            return;
        }
        List<String> testArgs = Arrays.asList(Arrays.copyOfRange(parts, 1, parts.length));

        ProcessResult testResult;
        try {
            testResult = executeToolProcess(testToolName, testArgs);
        } catch (Exception e) {
            logToFile("[TESTER] Test execution threw exception: " + e.getMessage());
            state.lastError = "[AUTO-TEST EXCEPTION] " + e.getMessage();
            state.crashRetries++;
            return;
        }

        logToFile("[TESTER] Test result:\n" + testResult.output());
        if (testResult.success()) {
            status("@|bold,green [TEST PASSED] Tool validated successfully.|@");
            logToFile("[TESTER] Test PASSED for: " + fileName);
        } else {
            status("@|bold,red [TEST FAILED] Tool failed validation. Routing for auto-heal...|@");
            state.lastError = "[AUTO-TEST FAILED]\n" + testResult.output();
            state.crashRetries++;
            logToFile("[TESTER] Test FAILED for: " + fileName + "\nError: " + testResult.output());
        }
    }

    private void handleExecute(String routerAction, int actionColon, String userPrompt, LoopState state)
            throws IOException, InterruptedException {
        status("@|bold,cyan [EXECUTE] |@" + routerAction);

        String[] parts = routerAction.substring(actionColon + 1).trim().split("\\s+");
        String toolName = parts[0];
        if (!isToolNameSafe(toolName)) {
            String msg = "Rejected unsafe tool name from LLM: '" + toolName + "'";
            status("@|bold,red [SECURITY] " + msg + "|@");
            logToFile("[SECURITY] " + msg);
            state.taskResolved = true;
            return;
        }

        // Filter JVM/jbang flags that the LLM could inject into script arguments
        List<String> scriptArgs = Arrays.stream(parts, 1, parts.length)
                .filter(arg -> {
                    boolean blocked = BLOCKED_ARG_PREFIXES.stream().anyMatch(arg::startsWith);
                    if (blocked)
                        logToFile("[SECURITY] Stripped injected flag from LLM args: '" + arg + "'");
                    return !blocked;
                })
                .collect(Collectors.toList());

        ProcessResult result = executeToolProcess(toolName, scriptArgs);
        logToFile("[EXECUTION RESULT]\n" + result.output());

        if (result.success()) {
            status("@|bold,green Demand successfully fulfilled via native JBang tool.|@");
            String outLog = truncate(result.output(), 300);
            addToMemory("USER: " + userPrompt);
            addToMemory("SYSTEM (EXECUTED): " + routerAction + "\nResult Preview: " + outLog.trim());
            state.taskResolved = true;
        } else {
            status("@|bold,red Tool Execution Failed. Returning trace to Router for analysis...|@");
            state.crashRetries++;
            if (state.crashRetries > 2) {
                status("@|bold,red Maximum retry limit reached (" + state.crashRetries
                        + "). Architecture failed to heal!|@");
                logToFile("[SYSTEM] Auto-heal limits exceeded."
                        + " | Tool: " + toolName
                        + " | Retries: " + state.crashRetries
                        + " | Last Error: " + truncate(state.lastError, 300));
                state.taskResolved = true;
                return;
            }
            state.lastError = result.output();
        }
    }

    private String handleCodeGeneration(String generatedCode) throws IOException {
        String code = generatedCode.replace("```java", "").replace("```json", "").replace("```", "").trim();

        String metadataContent = "";
        int metaStart = code.indexOf("//METADATA_START");
        int metaEnd = code.indexOf("//METADATA_END");

        if (metaStart != -1 && metaEnd != -1) {
            metadataContent = code.substring(metaStart + 16, metaEnd).trim();
            code = code.substring(metaEnd + 14).trim();
        }

        if (!code.startsWith("//FILE:")) {
            throw new IOException("LLM output missing //FILE: directive — incomplete or malformed generation.");
        }
        int fileIndex = code.indexOf('\n');
        if (fileIndex == -1) {
            throw new IOException("LLM output has //FILE: directive but no code body after it.");
        }
        String fileName = code.substring(7, fileIndex).trim();
        code = code.substring(fileIndex).trim();

        if (!isToolNameSafe(fileName)) {
            throw new IOException("Rejected unsafe file name from LLM: '" + fileName + "'");
        }

        // fast structural validation before writing to disk
        validateCodeStructure(code, fileName);

        Files.writeString(TOOLS_DIR.resolve(fileName), code);
        logToFile("[SYSTEM] Forge saved script: " + fileName);
        status("@|bold,green [Operation Successful] Script saved as: |@" + fileName);

        if (!metadataContent.isBlank()) {
            String metaFileName = toMetaName(fileName);
            Files.writeString(TOOLS_DIR.resolve(metaFileName), metadataContent);
            status("@|bold,green [Metadata] Schema generated and attached: |@" + metaFileName);
            logToFile("[SYSTEM] Metadata attached: " + metadataContent);
        }

        return fileName; // caller needs this to invoke handleTest()
    }

    // ==================== AGENTS PROMPT BUILDERS ====================

    private String buildCoderCreatePrompt(String instruction, String lastError) {
        String prompt = WORKSPACE_TOPOLOGY + "\n" + instruction;
        if (lastError != null)
            prompt += "\nImportant: Last logic crashed. CORRECT the architecture constraints:\n" + lastError;
        return prompt;
    }

    private String buildCoderEditPrompt(String changes, String existingCode, String lastError) {
        String prompt = WORKSPACE_TOPOLOGY
                + "\nRewrite the following tool applying these changes: " + changes
                + "\n\n[EXISTING CODE]\n" + existingCode;
        if (lastError != null)
            prompt += "\nImportant: Last logic crashed. CORRECT the architecture constraints:\n" + lastError;
        return prompt;
    }

    private String buildAssistantPrompt(String userPrompt, String ragContext, String toolsList, String clock) {
        String prompt = "Original Request: " + userPrompt + "\n\n[Local System Clock]: " + clock;
        if (!toolsList.equals("Empty"))
            prompt += "\n\n[System State - Available Cached Tools]:\n" + toolsList;
        if (!ragContext.isEmpty())
            prompt += "\n\n[RAG Context for Factual Accuracy]:\n" + ragContext;
        return prompt;
    }

    // ==================== GUARDRAILS ====================

    /**
     * Guardrail: after every tool execution, scans tools/ and moves any non-tool
     * files (PDFs, CSVs, images, etc.) to products/. Also removes stray
     * subdirectories created by tools that used malformed paths (e.g. paths with
     * literal quote characters from broken argument passing).
     */
    private void guardrailMoveOutputFiles() {
        try (Stream<Path> stream = Files.walk(TOOLS_DIR)) {
            stream
                    .filter(p -> !p.equals(TOOLS_DIR))
                    .sorted(Comparator.reverseOrder()) // depth-first: process files before their parent dirs
                    .forEach(p -> {
                        try {
                            if (Files.isRegularFile(p)) {
                                String name = p.getFileName().toString();
                                if (name.startsWith("."))
                                    return; // macOS/Linux hidden files (.DS_Store etc.)
                                if (GUARDRAIL_IGNORED_FILES.contains(name.toLowerCase()))
                                    return; // Windows system files
                                if (name.endsWith(".java") || name.endsWith(".meta.json"))
                                    return;
                                // Output file found inside tools/ — relocate to products/
                                Path dest = PRODUCTS_DIR.resolve(p.getFileName());
                                Files.move(p, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                status("@|bold,yellow [GUARDRAIL] Output file moved to products/: |@" + name);
                                logToFile("[GUARDRAIL] Moved " + p + " → " + dest);
                            } else if (Files.isDirectory(p)) {
                                // Stray subdirectory (artefact of a path with literal quotes or extra slashes)
                                Files.deleteIfExists(p);
                                logToFile("[GUARDRAIL] Removed stray directory in tools/: " + p.getFileName());
                            }
                        } catch (IOException e) {
                            logToFile("[WARN] guardrailMoveOutputFiles: " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            logToFile("[WARN] guardrailMoveOutputFiles scan failed: " + e.getMessage());
        }
    }

    // ==================== UTILITIES ====================

    /**
     * Prints a status/decorative message to stdout — silenced when --silent is
     * active.
     * All noise output (agent names, progress, banners) must go through this
     * method.
     */
    private void status(String ansiMessage) {
        if (!silent)
            System.out.println(AUTO.string(ansiMessage));
    }

    /**
     * Truncates a string to max chars, appending "..." if cut. Null-safe (returns
     * "n/a").
     */
    private static String truncate(String s, int max) {
        if (s == null)
            return "n/a";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Converts "ToolName.java" -> "ToolName.meta.json" using the last occurrence of
     * ".java".
     */
    private static String toMetaName(String javaFileName) {
        int dot = javaFileName.lastIndexOf(".java");
        return dot == -1 ? javaFileName + ".meta.json" : javaFileName.substring(0, dot) + ".meta.json";
    }

    private boolean isToolNameSafe(String toolName) {
        // Level 1 — simple names only: letters, digits, _ or - ending in .java
        if (!SAFE_TOOL_NAME.matcher(toolName).matches())
            return false;
        // Level 2 — path containment: normalized path must remain inside TOOLS_DIR
        Path resolved = TOOLS_DIR.resolve(toolName).toAbsolutePath().normalize();
        if (!resolved.startsWith(TOOLS_DIR.toAbsolutePath().normalize()))
            return false;
        // Level 3 — reject symlinks: a symlink inside TOOLS_DIR could point outside the
        // sandbox
        if (Files.exists(resolved) && Files.isSymbolicLink(resolved))
            return false;
        return true;
    }

    /**
     * fast structural checks on LLM-generated code before writing to
     * disk.
     * Catches blank body, missing //DEPS, missing class/main, and leaked markdown
     * fences.
     */
    private void validateCodeStructure(String code, String fileName) throws IOException {
        record Check(boolean fail, String msg) {
        }
        for (var c : new Check[] {
                new Check(code.isBlank(),
                        "code body is blank after stripping metadata"),
                new Check(!code.contains("//DEPS"),
                        "missing //DEPS directive"),
                new Check(!code.contains("class") || !code.contains("void main"),
                        "missing 'class' or 'void main'"),
                new Check(code.contains("```java") || code.contains("```"),
                        "leaked markdown fences in generated code")
        }) {
            if (c.fail()) {
                String msg = "Validation failed for '" + fileName + "': " + c.msg() + ".";
                status("@|bold,red [VALIDATION] |@" + msg);
                logToFile("[VALIDATION FAILED] " + msg);
                throw new IOException(msg);
            }
        }
    }

    /** Re-extracts the raw metadata JSON from the original LLM response string. */
    private String extractMetadataFromCode(String generatedCode) {
        String code = generatedCode.replace("```java", "").replace("```json", "").replace("```", "").trim();
        int metaStart = code.indexOf("//METADATA_START");
        int metaEnd = code.indexOf("//METADATA_END");
        return (metaStart != -1 && metaEnd != -1) ? code.substring(metaStart + 16, metaEnd).trim() : "";
    }

    private ProcessResult executeToolProcess(String toolName, List<String> scriptArgs)
            throws IOException, InterruptedException {
        try {
            long now = System.currentTimeMillis();
            Path javaFile = TOOLS_DIR.resolve(toolName);
            Path metaFile = TOOLS_DIR.resolve(toMetaName(toolName));
            if (Files.exists(javaFile))
                Files.setLastModifiedTime(javaFile, java.nio.file.attribute.FileTime.fromMillis(now));
            if (Files.exists(metaFile))
                Files.setLastModifiedTime(metaFile, java.nio.file.attribute.FileTime.fromMillis(now));
        } catch (IOException ignored) {
        }

        List<String> procArgs = resolveJbangCommand();
        procArgs.add("-Dfile.encoding=UTF-8");
        procArgs.add(toolName); // validated by isToolNameSafe() in handleExecute
        procArgs.addAll(scriptArgs); // script args, no flags JVM/jbang

        logToFile("[EXECUTE] " + procArgs);

        Process process = new ProcessBuilder(procArgs)
                .directory(TOOLS_DIR.toFile())
                .redirectErrorStream(true)
                .start();

        AtomicReference<String> outputRef = new AtomicReference<>("");
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                outputRef.set(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                outputRef.set("[STREAM READ ERROR] " + e.getMessage());
            }
        });

        if (!process.waitFor(MAX_TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            String msg = "[TIMEOUT] Tool '" + toolName + "' exceeded " + MAX_TOOL_TIMEOUT_SECONDS
                    + "s. Process forcibly terminated.";
            logToFile("[TIMEOUT] " + msg);
            status("@|bold,red " + msg + "|@");
            return new ProcessResult(false, msg);
        }
        reader.join();
        String executionOutput = outputRef.get();
        int exitCode = process.exitValue();

        resultBuffer.setLength(0);
        resultBuffer.append(executionOutput.strip());
        if (silent) {
            System.out.println(executionOutput.strip());
        } else {
            System.out.println("----------------[ RESULT ]----------------");
            System.out.print(executionOutput);
            System.out.println("------------------------------------------");
        }

        boolean success = exitCode == 0;
        // Fallback: exitCode 0 mas StackTrace vazou no stdout
        if (success && (executionOutput.contains("Exception in thread")
                || executionOutput.contains("Caused by: ")
                || executionOutput.toLowerCase().contains("an error occurred while"))) {
            success = false;
        }

        guardrailMoveOutputFiles();
        return new ProcessResult(success, executionOutput);
    }

    private List<String> resolveJbangCommand() {
        boolean windows = isWindowsHost();
        List<Path> candidates = new ArrayList<>();

        String jbangHome = System.getenv("JBANG_HOME");
        if (jbangHome != null && !jbangHome.isBlank()) {
            addJbangCandidates(Path.of(jbangHome, "bin"), windows, candidates);
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && !pathEnv.isBlank()) {
            for (String entry : pathEnv.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (entry == null || entry.isBlank())
                    continue;
                Path dir = Path.of(entry.trim());
                addJbangCandidates(dir, windows, candidates);
            }
        }

        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate))
                continue;
            if (!Files.isExecutable(candidate) && !candidate.toString().endsWith(".ps1"))
                continue;
            String normalized = candidate.toString();
            if (normalized.toLowerCase().endsWith(".ps1")) {
                return new ArrayList<>(
                        List.of("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", normalized));
            }
            return new ArrayList<>(List.of(normalized));
        }

        return new ArrayList<>(List.of("jbang"));
    }

    private static void addJbangCandidates(Path dir, boolean windows, List<Path> candidates) {
        if (windows) {
            candidates.add(dir.resolve("jbang.cmd"));
            candidates.add(dir.resolve("jbang.exe"));
            candidates.add(dir.resolve("jbang.ps1"));
            candidates.add(dir.resolve("jbang"));
            return;
        }
        candidates.add(dir.resolve("jbang"));
    }

    private static boolean isWindowsHost() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private List<String> listCachedTools() {
        try (Stream<Path> stream = Files.list(TOOLS_DIR)) {
            return stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted(BY_MTIME_DESC)
                    .limit(maxTools)
                    .map(javaFile -> {
                        Path metaPath = TOOLS_DIR.resolve(toMetaName(javaFile.getFileName().toString()));
                        if (Files.exists(metaPath)) {
                            try {
                                return Files.readString(metaPath);
                            } catch (IOException e) {
                                logToFile("[ERROR] listCachedTools: failed to read metadata "
                                        + metaPath.getFileName() + " — " + e.getMessage());
                            }
                        }
                        return javaFile.getFileName().toString();
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logToFile("[ERROR] listCachedTools: failed to list tools directory — " + e.getMessage());
            return List.of();
        }
    }

    private void runGarbageCollector() {
        long now = System.currentTimeMillis();
        if (now - lastGcRun < 60_000)
            return; // max one GC run per minute
        lastGcRun = now;
        try (Stream<Path> stream = Files.list(TOOLS_DIR)) {
            long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(maxToolAgeDays);

            // age-based: partition into "delete" (too old) and "keep"
            Map<Boolean, List<Path>> partitioned = stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .collect(Collectors.partitioningBy(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() < cutoff;
                        } catch (IOException e) {
                            logToFile("[WARN] runGarbageCollector: could not read mtime for "
                                    + p.getFileName() + " — " + e.getMessage());
                            return false; // uncertain -> preserve
                        }
                    }));

            List<Path> toDelete = new ArrayList<>(partitioned.get(true)); // defensive copy — partitioningBy mutability
                                                                          // not guaranteed by spec
            List<Path> remaining = partitioned.get(false);

            // count-based: if still > maxTools, evict the oldest by mtime
            if (remaining.size() > maxTools) {
                toDelete.addAll(remaining.stream()
                        .sorted(BY_MTIME_DESC)
                        .skip(maxTools)
                        .toList());
            }

            toDelete.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                    Files.deleteIfExists(TOOLS_DIR.resolve(toMetaName(p.getFileName().toString())));
                    status("@|bold,red \uD83D\uDDD1 [GARBAGE COLLECTOR] Deleting old unused tool: |@"
                            + p.getFileName());
                    logToFile("[GC] Deleted: " + p.getFileName());
                } catch (IOException e) {
                    logToFile("[ERROR] runGarbageCollector: failed to delete "
                            + p.getFileName() + " — " + e.getMessage());
                }
            });
        } catch (IOException e) {
            logToFile("[ERROR] runGarbageCollector: failed to list tools directory — " + e.getMessage());
        }
    }

    private String searchWeb(String query) {
        // Delegates to the searcher agent which holds a GoogleSearchTool
        return searcher.invoke(query);
    }

    // ==================== AGENT INSTRUCTIONS ====================

    private static final String ROUTER_INSTRUCTION = """
            You are a Logical CLI Tool Orchestrator System AND a highly intelligent Conversational Assistant.
            Read the [Cached Tools], [Recent Chat History], and [RAG Search Results] carefully before deciding.

            DECISION RULES — apply in this exact priority order:

            1. EXECUTE       — A cached tool already solves the request.
                               → 'EXECUTE: ToolName.java <args>'

            2. EDIT          — A cached tool exists but needs a change or fix requested by the user.
                               → 'EDIT: ToolName.java "change description"'

            3. SEARCH        — You need a free API endpoint, library syntax, or current factual data
                               not yet present in [RAG Search Results].
                               → 'SEARCH: "<targeted query>"'

            4. DELEGATE_CHAT — [RAG Search Results] already contains the DIRECT ANSWER to the user's question
                               (a name, a date, a fact, an explanation). Use this ONLY when the search result
                               itself IS the answer — not when it merely points to websites where the answer lives.
                               Also use for pure conversation, concepts, and explanations that need no live data.
                               → 'DELEGATE_CHAT'

            5. CREATE        — The user wants a concrete VALUE (coordinates, price, temperature, score, status)
                               AND [RAG Search Results] did not return that value directly — it only returned
                               descriptions, links, or tracker websites. Build a tool that fetches the real data.
                               If [RAG Search Results] contains a free API endpoint → use it immediately in CREATE.
                               If no API is known yet → SEARCH first, then CREATE on the next iteration.
                               → 'CREATE: Write a Java tool using <endpoint> to fetch <data> and print <output>'

            THE KEY QUESTION after a SEARCH: "Does [RAG Search Results] contain the actual answer,
            or does it only tell me WHERE to find the answer?"
            — Contains the answer  (e.g. "Donald Trump is president since Jan 2025") → DELEGATE_CHAT
            — Only points to sites (e.g. "track the ISS at isstracker.com")           → CREATE

            YOUR RESPONSE MUST BE EXACTLY ONE OF: 'EXECUTE: ...', 'CREATE: ...', 'EDIT: ...', 'SEARCH: ...' or 'DELEGATE_CHAT'.
            """;

    private static final String CODER_INSTRUCTION = """
            You are a Master Java Programmer working with jbang.
            Critical Rule:
            Your output MUST contain two sections:

            //METADATA_START
            {
              "name": "ToolName.java",
              "description": "Short explanation of the script",
              "args": ["description of arg1"]
            }
            //METADATA_END

            //FILE: ToolName.java
            //DEPS ...
            ... java code ...

            All generated scripts MUST be robust and extract input variables dynamically from the 'args' array.
            DO NOT WRITE MARKDOWN (such as ```java). RETURN EXECUTABLE TEXT AND STRICT METADATA BLOCK ONLY.
            CRITICAL ERROR HANDLING: Do not swallow exceptions in empty try-catch blocks. If a fatal failure occurs (e.g. network error, bad API), the script MUST crash explicitly or call System.exit(1) so the orchestrator can detect the failure.
            """;

    private static final String ASSISTANT_INSTRUCTION = """
            You are JForge Assistant, a highly intelligent conversational interface within a CLI application.
            Your role is to strictly answer the user's questions or help them conceptually.
            If [RAG Context for Factual Accuracy] search results are provided to you, USE THEM rigorously to ensure your factual answers are perfectly up-to-date and accurate. Do not hallucinate.
            Never generate entire Java code files. Code automation is handled by another agent.
            Keep your text crisp, beautifully formatted (Markdown is allowed here), and highly helpful.
            """;

    private static final String SEARCHER_INSTRUCTION = """
            You are a web search assistant with access to Google Search.
            When given a query, search the web and return a structured plain-text report following these rules:

            1. If the query is about finding an API or data endpoint: report the exact free API URL, the HTTP method,
               required parameters, and a sample response or response structure. This is the most important output.
            2. If the query is about a factual topic: report key facts, figures, and dates found in the results.
            3. Always include the source URL for any specific data point or API endpoint you report.
            4. Do NOT summarize tracker websites or dashboards as an answer — report the underlying API instead.
            5. Return plain text only. No markdown, no preamble, no commentary.
            """;

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

    private static final String SUPERVISOR_INSTRUCTION = """
            You are a Workflow Supervisor for a Java tool orchestrator.
            Your ONLY job: decide whether the user's request needs a single step or a multi-step workflow,
            and produce a WorkflowPlan JSON.

            You do NOT decide HOW each step is implemented. A Router agent handles that automatically.
            You only define WHAT each step should achieve and WHICH steps depend on others.

            Output ONLY valid JSON. No markdown, no code fences, no explanation.

            Schema:
            {
              "goal": "<one-line summary>",
              "steps": [
                {
                  "id": "s1",
                  "goal": "<sub-goal for this step — use <<sN>> to inject the output of step sN>",
                  "dependsOn": []
                }
              ]
            }

            Rules:
            - id must be unique: s1, s2, s3, ...
            - dependsOn: IDs of steps that must finish before this one starts
            - Steps with no mutual dependency run in parallel — use this for independent sub-tasks
            - Use <<stepId>> in a goal to chain the output of a previous step
            - For simple questions or single-tool requests, produce exactly ONE step
            - For complex workflows, decompose into the minimum number of steps needed

            EXAMPLE 1 — Simple question (single step, Router uses the Assistant):
            Goal: "Who is the current president of the USA?"
            {"goal":"Answer question about US president","steps":[
              {"id":"s1","goal":"Who is the current president of the USA?","dependsOn":[]}
            ]}

            EXAMPLE 2 — Single tool request (single step, Router handles search/create/execute):
            Goal: "Show the current Bitcoin price"
            {"goal":"Fetch current Bitcoin price","steps":[
              {"id":"s1","goal":"Fetch and display the current Bitcoin price in USD","dependsOn":[]}
            ]}

            EXAMPLE 3 — Complex workflow: create once, run in parallel, summarize:
            Goal: "Get current weather for London, Tokyo and New York and summarize"
            {"goal":"Multi-city weather summary","steps":[
              {"id":"s1","goal":"Create or use a weather tool that accepts a city name and shows temperature and conditions","dependsOn":[]},
              {"id":"s2","goal":"Show weather for London","dependsOn":["s1"]},
              {"id":"s3","goal":"Show weather for Tokyo","dependsOn":["s1"]},
              {"id":"s4","goal":"Show weather for New York","dependsOn":["s1"]},
              {"id":"s5","goal":"Summarize these weather results in a clear comparison table: <<s2>> | <<s3>> | <<s4>>","dependsOn":["s2","s3","s4"]}
            ]}

            EXAMPLE 4 — Mixed parallel + sequential pipeline with chaining and file output:
            Goal: "Pesquise os preços atuais de BTC, ETH e SOL, calcule qual teria dado o maior retorno num investimento de R$1000 há 30 dias, e gere um relatório PDF"
            {"goal":"Crypto ROI analysis and PDF report","steps":[
              {"id":"s1","goal":"Search for current prices of BTC, ETH and SOL in USD","dependsOn":[]},
              {"id":"s2","goal":"Search for prices of BTC, ETH and SOL 30 days ago in USD","dependsOn":[]},
              {"id":"s3","goal":"Create a tool that calculates the ROI for a R$1000 investment in each crypto using: current=<<s1>> | past=<<s2>>","dependsOn":["s1","s2"]},
              {"id":"s4","goal":"Execute the ROI calculator tool","dependsOn":["s3"]},
              {"id":"s5","goal":"Create a tool that generates a PDF report with the ROI ranking from: <<s4>>","dependsOn":["s4"]},
              {"id":"s6","goal":"Execute the PDF generator tool and save the report to products/","dependsOn":["s5"]}
            ]}
            """;

    // ==================== AGENTE ====================

    private class Agent {

        private final String name;
        private final InMemoryRunner runner;
        private final SessionKey sessionKey;

        /** Convenience constructor — no tools attached. */
        Agent(String name, String model, String instruction) {
            this(name, model, instruction, new com.google.adk.tools.BaseTool[0]);
        }

        /** Full constructor — optionally attach ADK tools (e.g. GoogleSearchTool). */
        Agent(String name, String model, String instruction, com.google.adk.tools.BaseTool... tools) {
            this.name = name;
            LlmAgent.Builder builder = LlmAgent.builder()
                    .name(name)
                    .model(model)
                    .instruction(instruction);
            if (tools.length > 0)
                builder = builder.tools(List.of(tools));
            this.runner = new InMemoryRunner(builder.build(), name + "-app");
            this.sessionKey = runner.sessionService()
                    .createSession(name + "-app", "user")
                    .blockingGet()
                    .sessionKey();
        }

        String invoke(String prompt) {
            try {
                StringBuilder sb = new StringBuilder();
                // Wrap in CompletableFuture to enforce a hard timeout; blockingForEach alone
                // can hang indefinitely.
                return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    runner.runAsync(sessionKey, Content.builder()
                            .role("user")
                            .parts(List.of(Part.fromText(prompt)))
                            .build())
                            .blockingForEach(event -> {
                                if (event.finalResponse() && event.content() != null)
                                    sb.append(event.stringifyContent());
                            });
                    return sb.toString().replace("```", "").trim();
                }).orTimeout(60, TimeUnit.SECONDS).join();
            } catch (Exception e) {
                String msg = "[" + name + "] LLM API call failed: " + e.getMessage();
                logToFile("[ERROR] " + msg);
                status("@|bold,red [LLM ERROR] " + msg + "|@");
                return "";
            }
        }
    }

    // ==================== CLASSES AUXILIARES ====================

    private static class LoopState {
        boolean taskResolved = false;
        String lastError = null;
        int crashRetries = 0;
        int loopIterations = 0;
        int searchCount = 0;
        String ragContext = "";
        String cacheList = null; // null = stale, reloads on demand
        /** Accumulated step outputs keyed by step id — used for ${stepId} chaining. */
        final Map<String, String> stepResults = new HashMap<>();
    }

    private record ProcessResult(boolean success, String output) {
    }

    // ==================== WORKFLOW EXECUTOR ====================

    /**
     * Executes a WorkflowPlan by delegating each step to the Router loop.
     *
     * Architecture:
     * Supervisor → decides WHAT to do and in what order (sub-goals + dependencies)
     * Router → decides HOW to achieve each sub-goal (SEARCH/CREATE/EXECUTE/CHAT)
     *
     * Execution model:
     * - Steps are grouped into topological layers via dependency analysis.
     * - Steps in the same layer (no mutual dependency) run in parallel via
     * VirtualThreads.
     * - Each step gets its own Router loop (processDemandRouter) with isolated
     * LoopState.
     * - ${stepId} placeholders in a step's goal are replaced with the output of
     * that step.
     */
    private class WorkflowExecutor {

        /** Step output keyed by step id — thread-safe for parallel layers. */
        private final Map<String, String> stepResults = new ConcurrentHashMap<>();

        /**
         * @return true if all steps completed; false if a step failed and replan is
         *         needed.
         */
        boolean execute(WorkflowPlan plan, LoopState workflowState) throws Exception {
            List<List<WorkflowStep>> layers = buildExecutionLayers(plan.steps());
            logToFile("[EXECUTOR] Plan: '" + plan.goal()
                    + "' | " + plan.steps().size() + " steps | " + layers.size() + " layers");

            for (int i = 0; i < layers.size(); i++) {
                List<WorkflowStep> layer = layers.get(i);
                status("@|bold,blue [EXECUTOR] Layer " + (i + 1) + "/" + layers.size() + ": ["
                        + layer.stream().map(WorkflowStep::id).collect(Collectors.joining(", ")) + "]|@");

                boolean layerOk = executeLayer(layer, workflowState);
                if (!layerOk) {
                    logToFile("[EXECUTOR] Layer " + (i + 1) + " failed.");
                    return false;
                }
            }

            workflowState.stepResults.putAll(stepResults);
            return true;
        }

        /**
         * Runs all steps in a layer.
         * Steps run in parallel via VirtualThreads; each gets its own Router LoopState.
         */
        private boolean executeLayer(List<WorkflowStep> layer, LoopState workflowState) throws Exception {
            if (layer.size() == 1) {
                return executeSingleStep(layer.get(0), workflowState);
            }

            // Parallel execution: one VirtualThread per step, each with an isolated Router
            // loop
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = layer.stream()
                        .map(step -> pool.submit(() -> executeSingleStep(step, workflowState)))
                        .toList();

                boolean allOk = true;
                for (var f : futures) {
                    if (!f.get())
                        allOk = false;
                }
                return allOk;
            }
        }

        /**
         * Resolves <<stepId>> in the step's goal, then delegates to the Router loop.
         * Captures the final output from resultBuffer for downstream chaining.
         *
         * Thread safety: resultBuffer and Agent sessions are shared instance state.
         * synchronized(JForgeAgent.this) serializes LLM calls and buffer access so
         * parallel VirtualThreads don't clobber each other's results.
         */
        private boolean executeSingleStep(WorkflowStep step, LoopState workflowState) throws Exception {
            String goal = resolveChaining(step.goal(), stepResults);

            status("@|faint [STEP " + step.id() + "] → |@" + truncate(goal, 80));
            logToFile("[STEP " + step.id() + "] goal: " + goal);

            String output;
            synchronized (JForgeAgent.this) {
                resultBuffer.setLength(0);
                // Delegate entirely to the Router — it decides SEARCH/CREATE/EXECUTE/CHAT
                processDemandRouter(goal);
                output = resultBuffer.toString().strip();
            }

            stepResults.put(step.id(), output);
            logToFile("[STEP " + step.id() + "] result: " + truncate(output, 200));

            boolean ok = !output.isBlank();
            if (!ok)
                workflowState.lastError = "[STEP " + step.id() + "] produced no output — possible LLM failure.";
            return ok;
        }

        /**
         * Topological sort: assigns each step a "level" = max(dependsOn levels) + 1.
         * Steps sharing the same level have no mutual dependency and form a parallel
         * layer.
         */
        private List<List<WorkflowStep>> buildExecutionLayers(List<WorkflowStep> steps) {
            Map<String, WorkflowStep> byId = steps.stream()
                    .collect(Collectors.toMap(WorkflowStep::id, s -> s));
            Map<String, Integer> levels = new HashMap<>();
            for (WorkflowStep s : steps)
                computeLevel(s, byId, levels);

            Map<Integer, List<WorkflowStep>> layerMap = new LinkedHashMap<>();
            for (WorkflowStep s : steps)
                layerMap.computeIfAbsent(levels.get(s.id()), k -> new ArrayList<>()).add(s);

            return new ArrayList<>(layerMap.values());
        }

        private int computeLevel(WorkflowStep step, Map<String, WorkflowStep> byId,
                Map<String, Integer> levels) {
            if (levels.containsKey(step.id()))
                return levels.get(step.id());
            int level = step.dependsOn().stream()
                    .filter(byId::containsKey)
                    .mapToInt(depId -> computeLevel(byId.get(depId), byId, levels) + 1)
                    .max().orElse(0);
            levels.put(step.id(), level);
            return level;
        }

        /** Replaces <<stepId>> placeholders with the captured output of that step. */
        private String resolveChaining(String text, Map<String, String> results) {
            if (text == null)
                return "";
            for (var e : results.entrySet())
                text = text.replace("<<" + e.getKey() + ">>", e.getValue());
            return text;
        }
    }

    // ==================== AUXILIARY CLASSES ====================

    /**
     * One task inside a WorkflowPlan.
     * The Router decides HOW to achieve the goal (SEARCH / CREATE / EXECUTE /
     * CHAT).
     * The Supervisor only decides WHAT to achieve and in what order.
     */
    private record WorkflowStep(
            String id, // unique identifier: s1, s2, ...
            String goal, // sub-goal delegated to the Router (may contain ${stepId} placeholders)
            List<String> dependsOn // step IDs that must complete before this one
    ) {
    }

    /** The full plan returned by the Supervisor. */
    private record WorkflowPlan(String goal, List<WorkflowStep> steps) {
    }
}