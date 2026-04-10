///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 26+
//DEPS com.google.adk:google-adk:1.0.0-rc.1
//DEPS org.slf4j:slf4j-simple:2.0.12
//DEPS info.picocli:picocli:4.7.5

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
            "--model" }, description = "Gemini model to use (default: gemini-3-pro-preview)", defaultValue = "gemini-3-pro-preview")
    private String defaultModel = "gemini-3-pro-preview";

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

        router = new Agent("router", defaultModel, ROUTER_INSTRUCTION);
        coder = new Agent("coder", defaultModel, CODER_INSTRUCTION);
        assistant = new Agent("assistant", defaultModel, ASSISTANT_INSTRUCTION);
        searcher = new Agent("searcher", defaultModel, SEARCHER_INSTRUCTION, new GoogleSearchTool());
        tester = new Agent("tester", defaultModel, TESTER_INSTRUCTION);

        status("@|faint [LLM] Model: " + defaultModel
                + " | Agents: router, coder, assistant, searcher, tester|@");
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

    private void processDemand(String userPrompt) throws Exception {
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

        List<String> procArgs = new ArrayList<>();
        procArgs.add("jbang");
        procArgs.add("-Dfile.encoding=UTF-8");
        procArgs.add(toolName); // validated by isToolNameSafe() in handleExecute
        procArgs.addAll(scriptArgs); // script args, no flags JVM/jbang

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

        return new ProcessResult(success, executionOutput);
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
        String cacheList = null; // null = stale, recarrega sob demanda
    }

    private record ProcessResult(boolean success, String output) {
    }
}