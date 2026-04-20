///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS dev.langchain4j:langchain4j-core:0.36.2
//DEPS dev.langchain4j:langchain4j-open-ai:0.36.2
//SOURCES ../../shared/Event.java
//DESCRIPTION MK3 Router Plugin — decides HOW to achieve each step (UDS)

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.io.BufferedWriter;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * RouterPlugin — the action-decision layer.
 *
 * Receives each step goal from WorkflowOrchestratorPlugin as a {@code router.request}
 * whose payload is a serialised {@link Event.RouterState} JSON object.
 * Builds a full state prompt, calls the LLM and responds with exactly one action:
 *
 *   EXECUTE: ToolName.java arg1 arg2 ...
 *   CREATE: <instruction for the Coder>
 *   EDIT: ToolName.java <change description>
 *   SEARCH: <search query>
 *   DELEGATE_CHAT
 *
 * The Orchestrator then dispatches to the corresponding handler plugin.
 */
public class RouterPlugin {

    private static final String SOCKET     = "../../kernel/kernel.sock";
    private static final String PLUGIN_ID  = "router-plugin";
    private static final String MODEL_NAME = "llama-3.3-70b-versatile";

    private static ChatLanguageModel model;

    // -------------------------------------------------------------------------
    // Router LLM instruction (ported from JForgeAgent.java)
    // -------------------------------------------------------------------------

    private static final String ROUTER_INSTRUCTION = """
            YOUR RESPONSE MUST BE EXACTLY ONE OF:
              'EXECUTE: ToolName.java arg1 arg2'
              'CREATE: <instruction>'
              'EDIT: ToolName.java <change description>'
              'SEARCH: <query>'
              'DELEGATE_CHAT'

            ── ACTION PRIORITY (apply in order) ─────────────────────────────────────
            1. CREATE   — If user says 'crie', 'build', 'develop', 'ferramenta', 'script'
                          OR the task involves repeatable logic/file generation → use CREATE.
            2. EXECUTE  — A cached tool already handles this specific task → use it.
            3. SEARCH   — You need current external data and no tool exists yet.
            4. DELEGATE_CHAT — Use for pure conversation, knowledge questions, or creative
                          one-off requests (jokes, poetry) where no tool is requested.

            TOOL PREFERENCE: Any repeatable operation (fetching data, generating files like
            PDF/ASCII/CSV) MUST result in CREATE, even if it seems creative.
            A WeatherFetcher.java reused tomorrow is better than a one-off answer today.

            TIME-SENSITIVITY RULE: Any question about "current" or "present" facts
            (President, Weather, Prices, Status) MUST be preceded by a SEARCH if
            [RAG Search Results] is empty or stale. Do NOT use DELEGATE_CHAT for
            time-sensitive facts without corroborating search results.

            ENVIRONMENTAL INTERVENTION RULE: If a FAILURE trace ([Last Error])
            indicates missing API keys, env vars, secrets, or unauthorized access,
            do NOT use EDIT or CREATE. Use DELEGATE_CHAT to inform the user and
            request the missing information.

            OUTPUT FORMAT: Respond with ONE line only. No explanation. No markdown.
            """;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        System.out.println("[ROUTER] Starting...");

        String apiKey = System.getenv("GROQ_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[ROUTER] GROQ_API_KEY is not set.");
            return;
        }

        model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl("https://api.groq.com/openai/v1")
                .modelName(MODEL_NAME)
                .logRequests(false)
                .logResponses(false)
                .build();

        System.out.println("[ROUTER] Model initialised: " + MODEL_NAME);

        var config = Event.loadConfig("plugin.json");

        Event.connectAndRun(SOCKET, config, (in, out) -> {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    Event ev = Event.MAPPER.readValue(line, Event.class);
                    if ("router.request".equals(ev.type())) {
                        final Event event = ev;
                        executor.submit(() -> handleRequest(event, out));
                    } else {
                        System.out.println("[ROUTER] Ignored: " + ev.type());
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
            // Deserialise the RouterState payload
            Event.RouterState state = Event.MAPPER.readValue(ev.payload(), Event.RouterState.class);

            String statePrompt = buildStatePrompt(state);
            System.out.println("[ROUTER] Deciding action for: " + truncate(state.goal(), 80));

            String action = callLLM(statePrompt);

            if (action.isBlank()) {
                System.err.println("[ROUTER] Empty LLM response — defaulting to DELEGATE_CHAT.");
                action = "DELEGATE_CHAT";
            }

            System.out.println("[ROUTER] Action: " + action);

            // Reply — correlationId and sessionId are automatically propagated
            publish(Event.reply(ev, "router.response", action, PLUGIN_ID), out);

        } catch (Exception e) {
            System.err.println("[ROUTER] Error handling request: " + e.getMessage());
            // Publish a safe fallback so the orchestrator loop doesn't hang
            try {
                publish(Event.reply(ev, "router.response", "DELEGATE_CHAT", PLUGIN_ID), out);
            } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Prompt builder  (mirrors JForgeAgent.buildStatePrompt)
    // -------------------------------------------------------------------------

    private static String buildStatePrompt(Event.RouterState s) {
        String fallback = (s.lastError() == null || s.lastError().isBlank())
                ? "No previous errors."
                : "A FAILURE OCCURRED IN THE LAST EXECUTION WITH THE FOLLOWING TRACE. REQUIRED FIX:\n"
                  + s.lastError();

        String rag = (s.ragContext() == null || s.ragContext().isBlank())
                ? "No recent searches."
                : s.ragContext();

        String history = (s.history() == null || s.history().isBlank())
                ? "No previous context."
                : s.history();

        String tools = (s.cachedTools() == null || s.cachedTools().isBlank())
                ? "Empty"
                : s.cachedTools();

        String clock = (s.clock() == null || s.clock().isBlank())
                ? java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy HH:mm:ss"))
                : s.clock();

        return String.format("""
                [Local System Clock]
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
                """,
                clock, history, tools, rag, fallback, s.goal());
    }

    // -------------------------------------------------------------------------
    // LLM call
    // -------------------------------------------------------------------------

    private static String callLLM(String prompt) {
        try {
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(ROUTER_INSTRUCTION),
                    UserMessage.from(prompt));
            AiMessage response = model.generate(messages).content();
            return response.text().replace("```", "").trim();
        } catch (Exception e) {
            System.err.println("[ROUTER] LLM call failed: " + e.getMessage());
            return "";
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
                System.err.println("[ROUTER] Error publishing event: " + e.getMessage());
            }
        }
    }
}
