///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS dev.langchain4j:langchain4j-core:0.36.2
//DEPS dev.langchain4j:langchain4j-open-ai:0.36.2
//SOURCES ../../shared/Event.java
//DESCRIPTION MK3 Supervisor Plugin — classifies demands as SIMPLE/REUSE/WORKFLOW (UDS)

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 * SupervisorPlugin — the entry point for every user demand.
 *
 * Receives:  chat.prompt       (from ConsolePlugin — new user message)
 *            supervisor.request (from WorkflowOrchestratorPlugin — replan with error context)
 *            memory.result     (response to memory.read — correlationId matched internally)
 *
 * Publishes: supervisor.response  (WorkflowPlan JSON → WorkflowOrchestratorPlugin)
 *            memory.read          (requests recent history from MemoryPlugin)
 */
public class SupervisorPlugin {

    private static final String SOCKET        = "../../kernel/kernel.sock";
    private static final String PLUGIN_ID     = "supervisor-plugin";
    private static final Path   WORKFLOWS_DIR = Path.of("../../workflows");
    private static final int    MAX_WORKFLOWS_IN_CONTEXT = 3;
    private static final String MODEL_NAME    = "llama-3.3-70b-versatile";

    private static final DateTimeFormatter FMT_CLOCK =
            DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy HH:mm:ss");

    // Pending memory.read futures: correlationId → CompletableFuture<history string>
    private static final Map<String, CompletableFuture<String>> pendingMemory =
            new ConcurrentHashMap<>();

    private static ChatLanguageModel model;
    private static BufferedWriter    sharedOut;

    // -------------------------------------------------------------------------
    // Supervisor LLM instruction (ported from JForgeAgent.java)
    // -------------------------------------------------------------------------

    private static final String SUPERVISOR_INSTRUCTION = """
            You are a Workflow Supervisor for a Java tool orchestrator.
            Your job: classify each user request as SIMPLE or WORKFLOW, then respond with JSON only.

            You do NOT decide HOW each step is implemented. A Router agent handles that automatically.

            Output ONLY valid JSON. No markdown, no code fences, no explanation.

            ── SIMPLE bypass ────────────────────────────────────────────────────────
            Use {"type":"SIMPLE"} when ALL of the following are true:
              • At most one Router action is needed (a factual answer, a single known tool call, or a search)
              • No output from one step needs to feed into another
              • No parallel execution is worthwhile
              • If a tool is needed, it is already listed in [Cached Tools]
            Output:
            {"type":"SIMPLE"}

            ── REUSE ─────────────────────────────────────────────────────────────────
            Use {"type":"REUSE","id":"<filename>"} when [Recent Successful Workflows]
            contains a workflow whose goal and steps closely match the current request.
            The id must be the exact filename shown in the id: field of that entry.
            Output:
            {"type":"REUSE","id":"workflow_20260412_123758.json"}

            ── WORKFLOW ─────────────────────────────────────────────────────────────
            Use {"type":"WORKFLOW", ...} when ANY of the following is true:
              • A new tool must be created (not in [Cached Tools]) before it can be executed
              • Results from step A feed into step B  (chaining with <<stepId>>)
              • Multiple independent sub-tasks benefit from parallel execution
              • A file (PDF, CSV, …) must be generated as a final step combining earlier results
            Schema:
            {
              "type": "WORKFLOW",
              "goal": "<one-line summary>",
              "steps": [
                {
                  "id": "s1",
                  "goal": "<sub-goal — use <<sN>> to inject the output of step sN>",
                  "dependsOn": []
                }
              ]
            }

            WORKFLOW rules:
            - id must be unique: s1, s2, s3, ...
            - dependsOn: IDs of steps that must finish before this one starts
            - Steps with no mutual dependency run in parallel — use for independent sub-tasks
            - Use <<stepId>> in a goal to chain the output of a previous step
            - NEVER split "create a tool" and "execute the tool" into separate steps — the Router
              always creates AND executes in a single step. One deliverable = one step.

            GENERIC STEP GOALS: Write step goals generically so the Router builds reusable tools.
            Name steps after the action and data type, NOT the specific value from the request.
              Bad : "Fetch the current price of Bitcoin in USD"
              Good: "Fetch current prices for the given cryptocurrency symbols"
            Generic goals produce generic tool names (CryptoPriceFetcher.java, not BitcoinFetcher.java).
            The specific values (city, symbol, amount) belong in the EXECUTE arguments, not in the step goal.

            EXAMPLE A — SIMPLE: factual / conversational question
            Goal: "Who is the current president of the USA?"
            {"type":"SIMPLE"}

            EXAMPLE B — SIMPLE: follow-up reusing a tool already in [Cached Tools]
            Goal: "E do Etherium?" (CryptoPriceFetcher already in cache)
            {"type":"SIMPLE"}

            EXAMPLE C — SIMPLE: trivial question answerable from knowledge
            Goal: "Que dia é hoje?"
            {"type":"SIMPLE"}

            EXAMPLE D — WORKFLOW (1 step): new tool required (not in cache)
            Goal: "Show the current Bitcoin price"  (no price tool in [Cached Tools])
            {"type":"WORKFLOW","goal":"Fetch current Bitcoin price","steps":[
              {"id":"s1","goal":"Fetch and display the current price of Bitcoin in USD","dependsOn":[]}
            ]}

            EXAMPLE E — WORKFLOW (multi-step, parallel + sequential):
            Goal: "Get current weather for London, Tokyo and New York and summarize"
            {"type":"WORKFLOW","goal":"Multi-city weather summary","steps":[
              {"id":"s1","goal":"Create or use a weather tool that accepts a city name and shows temperature and conditions","dependsOn":[]},
              {"id":"s2","goal":"Show weather for London","dependsOn":["s1"]},
              {"id":"s3","goal":"Show weather for Tokyo","dependsOn":["s1"]},
              {"id":"s4","goal":"Show weather for New York","dependsOn":["s1"]},
              {"id":"s5","goal":"Summarize these weather results in a clear comparison table: <<s2>> | <<s3>> | <<s4>>","dependsOn":["s2","s3","s4"]}
            ]}

            EXAMPLE F — WORKFLOW (parallel + sequential + file output):
            Goal: "Pesquise os preços atuais de BTC, ETH e SOL, calcule o maior retorno num investimento de R$1000 há 30 dias e gere um PDF"
            {"type":"WORKFLOW","goal":"Crypto ROI analysis and PDF report","steps":[
              {"id":"s1","goal":"Search for current prices of BTC, ETH and SOL in USD","dependsOn":[]},
              {"id":"s2","goal":"Search for prices of BTC, ETH and SOL 30 days ago in USD","dependsOn":[]},
              {"id":"s3","goal":"Develop a tool to calculate the ROI for a R$1000 investment in each crypto (current=<<s1>> | past=<<s2>>)","dependsOn":["s1","s2"]},
              {"id":"s4","goal":"Create a tool to generate a PDF report with the ROI ranking from: <<s3>> and save to products/","dependsOn":["s3"]}
            ]}

            GENERIC STEP GOALS: For development tasks, explicitly use verbs like 'Develop', 'Create',
            or 'Implement a tool for...'. This ensures the Router handles them as CREATE actions.
            """;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        System.out.println("[SUPERVISOR] Starting...");

        String apiKey = System.getenv("GROQ_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[SUPERVISOR] GROQ_API_KEY is not set.");
            return;
        }

        model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl("https://api.groq.com/openai/v1")
                .modelName(MODEL_NAME)
                .logRequests(false)
                .logResponses(false)
                .build();

        System.out.println("[SUPERVISOR] Model initialised: " + MODEL_NAME);

        var config = Event.loadConfig("plugin.json");

        Event.connectAndRun(SOCKET, config, (in, out) -> {
            sharedOut = out;
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    Event ev = Event.MAPPER.readValue(line, Event.class);
                    switch (ev.type()) {

                        case "memory.result" -> {
                            // Resolve a pending memory.read future
                            if (ev.correlationId() != null) {
                                var future = pendingMemory.remove(ev.correlationId());
                                if (future != null) future.complete(ev.payload());
                            }
                        }

                        case "chat.prompt", "supervisor.request" -> {
                            // Process in a virtual thread so the main loop stays free
                            // to receive memory.result while we await the memory read
                            final Event event = ev;
                            executor.submit(() -> handleDemand(event));
                        }

                        default -> System.out.println("[SUPERVISOR] Ignored: " + ev.type());
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Demand handler
    // -------------------------------------------------------------------------

    private static void handleDemand(Event ev) {
        try {
            // --- Extract goal and optional replan context ---
            String goal;
            String prevPlan    = null;
            String errorTrace  = null;

            if ("supervisor.request".equals(ev.type())) {
                // Replan payload: { "goal": "...", "prevPlan": "...", "errorTrace": "..." }
                JsonNode node = Event.MAPPER.readTree(ev.payload());
                goal       = node.path("goal").asText(ev.payload());
                prevPlan   = node.has("prevPlan")   ? node.get("prevPlan").asText()   : null;
                errorTrace = node.has("errorTrace") ? node.get("errorTrace").asText() : null;
            } else {
                goal = ev.payload();
            }

            System.out.println("[SUPERVISOR] Processing: " + goal);

            // --- 1. Read recent memory (async, non-blocking the kernel read loop) ---
            String history = readMemory(ev);

            // --- 2. Load recent successful workflows ---
            String recentWorkflows = loadRecentWorkflows();

            // --- 3. Build prompt ---
            String prompt = buildSupervisorPrompt(goal, history, recentWorkflows, prevPlan, errorTrace);

            // --- 4. Call LLM ---
            String llmResponse = callLLM(prompt);
            System.out.println("[SUPERVISOR] LLM raw response: " + llmResponse);

            if (llmResponse.isBlank()) {
                System.err.println("[SUPERVISOR] Empty LLM response — publishing SIMPLE fallback.");
                llmResponse = "{\"type\":\"SIMPLE\"}";
            }

            // --- 5. Parse and enrich plan ---
            Event.WorkflowPlan plan = parsePlan(llmResponse, goal, history, ev.payload());

            // --- 6. Publish supervisor.response ---
            String planJson = Event.MAPPER.writeValueAsString(plan);
            publish(Event.reply(ev, "supervisor.response", planJson, PLUGIN_ID));

            System.out.println("[SUPERVISOR] Published supervisor.response: type=" + plan.type());

        } catch (Exception e) {
            System.err.println("[SUPERVISOR] Error handling demand: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // -------------------------------------------------------------------------
    // Memory read (blocks virtual thread, not the main kernel loop)
    // -------------------------------------------------------------------------

    private static String readMemory(Event originEvent) {
        try {
            String corrId = UUID.randomUUID().toString();
            var future = new CompletableFuture<String>();
            pendingMemory.put(corrId, future);

            // Request last 10 entries
            publish(Event.request("memory.read", "10", PLUGIN_ID,
                    corrId, originEvent.workflowId(), "memory.result"));

            return future.get(10, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            System.err.println("[SUPERVISOR] Memory read timed out — continuing without history.");
            return "No previous context.";
        } catch (Exception e) {
            System.err.println("[SUPERVISOR] Memory read failed: " + e.getMessage());
            return "No previous context.";
        }
    }

    // -------------------------------------------------------------------------
    // Workflow loader
    // -------------------------------------------------------------------------

    private static String loadRecentWorkflows() {
        if (!Files.exists(WORKFLOWS_DIR)) return "No recent workflows.";
        try {
            Comparator<Path> byMtime = (a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (IOException e) { return 0; }
            };

            List<Path> workflows = Files.list(WORKFLOWS_DIR)
                    .filter(p -> p.toString().endsWith(".json") && !p.toString().endsWith(".fail.json"))
                    .sorted(byMtime)
                    .limit(MAX_WORKFLOWS_IN_CONTEXT)
                    .collect(Collectors.toList());

            if (workflows.isEmpty()) return "No recent workflows.";

            StringBuilder sb = new StringBuilder();
            for (Path wf : workflows) {
                try {
                    String content = Files.readString(wf);
                    // Read failure count from .fail.json sidecar
                    Path failFile = wf.resolveSibling(
                            wf.getFileName().toString().replace(".json", ".fail.json"));
                    int failCount = 0;
                    if (Files.exists(failFile)) {
                        try { failCount = Integer.parseInt(Files.readString(failFile).trim()); }
                        catch (Exception ignored) {}
                    }
                    sb.append("id: ").append(wf.getFileName())
                      .append(" | failures: ").append(failCount)
                      .append("\n").append(content).append("\n\n");
                } catch (IOException ignored) {}
            }
            return sb.toString().isBlank() ? "No recent workflows." : sb.toString();

        } catch (IOException e) {
            System.err.println("[SUPERVISOR] Error loading workflows: " + e.getMessage());
            return "No recent workflows.";
        }
    }

    // -------------------------------------------------------------------------
    // Prompt builder
    // -------------------------------------------------------------------------

    private static String buildSupervisorPrompt(String goal, String history,
            String recentWorkflows, String prevPlan, String errorTrace) {

        String clock = LocalDateTime.now().format(FMT_CLOCK)
                + " | Zone: " + java.time.ZoneId.systemDefault();

        StringBuilder sb = new StringBuilder();
        sb.append("[Local System Clock]\n").append(clock).append("\n\n");
        sb.append("[Recent Chat History]\n").append(history).append("\n\n");
        sb.append("[Recent Successful Workflows]\n").append(recentWorkflows).append("\n\n");

        if (prevPlan != null && !prevPlan.isBlank()) {
            sb.append("[Previous Plan That Failed]\n").append(prevPlan).append("\n\n");
        }
        if (errorTrace != null && !errorTrace.isBlank()) {
            sb.append("[Error Trace From Previous Plan]\n").append(errorTrace).append("\n\n");
        }

        sb.append("User Goal: ").append(goal).append("\n");
        sb.append("Classify and respond with JSON only.");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // LLM call
    // -------------------------------------------------------------------------

    private static String callLLM(String prompt) {
        try {
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(SUPERVISOR_INSTRUCTION),
                    UserMessage.from(prompt));
            AiMessage response = model.generate(messages).content();
            // Strip any accidental markdown fences
            return response.text()
                    .replace("```json", "").replace("```", "").trim();
        } catch (Exception e) {
            System.err.println("[SUPERVISOR] LLM call failed: " + e.getMessage());
            return "";
        }
    }

    // -------------------------------------------------------------------------
    // Plan parser
    // -------------------------------------------------------------------------

    private static Event.WorkflowPlan parsePlan(String llmResponse, String goal,
            String history, String originalPrompt) {
        try {
            // Extract outermost JSON object
            int start = llmResponse.indexOf('{');
            int end   = llmResponse.lastIndexOf('}');
            if (start == -1 || end == -1)
                return simplePlan(goal, history, originalPrompt);

            String json = llmResponse.substring(start, end + 1);
            JsonNode node = Event.MAPPER.readTree(json);
            String type = node.path("type").asText("SIMPLE");

            return switch (type) {
                case "SIMPLE" -> simplePlan(goal, history, originalPrompt);

                case "REUSE" -> {
                    String id = node.path("id").asText("");
                    yield loadReusePlan(id, goal, history, originalPrompt);
                }

                case "WORKFLOW" -> {
                    String wfGoal = node.path("goal").asText(goal);
                    List<Event.WorkflowStep> steps = new ArrayList<>();
                    JsonNode stepsNode = node.path("steps");
                    if (stepsNode.isArray()) {
                        for (JsonNode s : stepsNode) {
                            String id  = s.path("id").asText("");
                            String sg  = s.path("goal").asText("");
                            List<String> deps = new ArrayList<>();
                            JsonNode depsNode = s.path("dependsOn");
                            if (depsNode.isArray())
                                depsNode.forEach(d -> deps.add(d.asText()));
                            steps.add(new Event.WorkflowStep(id, sg, deps));
                        }
                    }
                    if (steps.isEmpty()) yield simplePlan(goal, history, originalPrompt);
                    yield new Event.WorkflowPlan("WORKFLOW", wfGoal, steps, history, originalPrompt);
                }

                default -> simplePlan(goal, history, originalPrompt);
            };

        } catch (Exception e) {
            System.err.println("[SUPERVISOR] Failed to parse plan: " + e.getMessage());
            return simplePlan(goal, history, originalPrompt);
        }
    }

    /** REUSE: load the referenced workflow JSON from disk, re-wrap as WorkflowPlan. */
    private static Event.WorkflowPlan loadReusePlan(String id, String goal,
            String history, String originalPrompt) {
        try {
            Path wfFile = WORKFLOWS_DIR.resolve(id);
            if (!Files.exists(wfFile)) {
                System.err.println("[SUPERVISOR] REUSE workflow not found: " + id + " — falling back to SIMPLE.");
                return simplePlan(goal, history, originalPrompt);
            }
            String content = Files.readString(wfFile);
            JsonNode node  = Event.MAPPER.readTree(content);
            String wfGoal  = node.path("goal").asText(goal);
            List<Event.WorkflowStep> steps = new ArrayList<>();
            JsonNode stepsNode = node.path("steps");
            if (stepsNode.isArray()) {
                for (JsonNode s : stepsNode) {
                    List<String> deps = new ArrayList<>();
                    s.path("dependsOn").forEach(d -> deps.add(d.asText()));
                    steps.add(new Event.WorkflowStep(
                            s.path("id").asText(""),
                            s.path("goal").asText(""),
                            deps));
                }
            }
            System.out.println("[SUPERVISOR] REUSE plan loaded: " + id + " (" + steps.size() + " steps)");
            return new Event.WorkflowPlan("WORKFLOW", wfGoal, steps, history, originalPrompt);

        } catch (Exception e) {
            System.err.println("[SUPERVISOR] Failed to load REUSE plan '" + id + "': " + e.getMessage());
            return simplePlan(goal, history, originalPrompt);
        }
    }

    /** Creates a trivial single-step SIMPLE plan (no steps — orchestrator routes directly). */
    private static Event.WorkflowPlan simplePlan(String goal, String history, String originalPrompt) {
        return new Event.WorkflowPlan("SIMPLE", goal, Collections.emptyList(), history, originalPrompt);
    }

    // -------------------------------------------------------------------------
    // Publishing
    // -------------------------------------------------------------------------

    private static void publish(Event event) {
        synchronized (sharedOut) {
            try {
                sharedOut.write(Event.MAPPER.writeValueAsString(event));
                sharedOut.newLine();
                sharedOut.flush();
            } catch (Exception e) {
                System.err.println("[SUPERVISOR] Error publishing event: " + e.getMessage());
            }
        }
    }
}
