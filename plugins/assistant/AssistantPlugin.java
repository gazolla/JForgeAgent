///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS dev.langchain4j:langchain4j-core:0.36.2
//DEPS dev.langchain4j:langchain4j-open-ai:0.36.2
//SOURCES ../../shared/Event.java
//DESCRIPTION MK3 Assistant Plugin — conversational responses for DELEGATE_CHAT actions (UDS)

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.io.BufferedWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * AssistantPlugin — the conversational response layer.
 *
 * Invoked whenever the Router decides DELEGATE_CHAT: a direct LLM answer
 * is the best action (pure knowledge question, creative request, or a
 * failure requiring human intervention for missing env vars/secrets).
 *
 * Subscribes: assistant.request
 *   Payload (JSON):
 *   {
 *     "prompt":     "user's original question",
 *     "ragContext": "search results injected for factual grounding (may be empty)",
 *     "toolList":   "comma-separated list of cached tool names (may be empty)"
 *   }
 *
 * Publishes: assistant.response
 *   Payload: Markdown-formatted plain text answer.
 */
public class AssistantPlugin {

    private static final String SOCKET     = "../../kernel/kernel.sock";
    private static final String PLUGIN_ID  = "assistant-plugin";
    private static final String MODEL_NAME = "llama-3.1-8b-instant";

    private static final DateTimeFormatter FMT_CLOCK =
            DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy HH:mm:ss");

    private static ChatLanguageModel model;

    // -------------------------------------------------------------------------
    // Assistant LLM instruction (ported from JForgeAgent.java)
    // -------------------------------------------------------------------------

    private static final String ASSISTANT_INSTRUCTION = """
            You are JForge Assistant, a highly intelligent conversational interface within a CLI application.
            Your role is to strictly answer the user's questions or help them conceptually.

            TEMPORAL ANCHOR: Always use the provided [Local System Clock] as the absolute ground truth
            for "today", "now", or any date-based logic. If your internal training data conflicts with
            the provided clock (e.g., regarding the current year, month, or public figures), prioritise
            the [Local System Clock] and conclude that the world state has changed since your last update.

            If [RAG Context for Factual Accuracy] search results are provided to you, USE THEM rigorously
            to ensure your factual answers are perfectly up-to-date and accurate. Do not hallucinate.

            Never generate entire Java code files. Code automation is handled by another agent.

            DO NOT mention specific tool filenames (like Tool.java) from the cached list unless you are
            explicitly asked about your available tools.

            Keep your text crisp, beautifully formatted (Markdown is allowed here), and highly helpful.

            CLARIFICATION & INTERVENTION: If you are called because a tool failed due to missing
            configuration (keys, env vars), focus your response on explaining what is missing and
            provide clear instructions on how the user can resolve it.
            """;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        System.out.println("[ASSISTANT] Starting...");

        String apiKey = System.getenv("GROQ_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[ASSISTANT] GROQ_API_KEY is not set.");
            return;
        }

        model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl("https://api.groq.com/openai/v1")
                .modelName(MODEL_NAME)
                .logRequests(false)
                .logResponses(false)
                .build();

        System.out.println("[ASSISTANT] Model initialised: " + MODEL_NAME);

        var config = Event.loadConfig("plugin.json");

        Event.connectAndRun(SOCKET, config, (in, out) -> {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    Event ev = Event.MAPPER.readValue(line, Event.class);
                    if ("assistant.request".equals(ev.type())) {
                        final Event event = ev;
                        executor.submit(() -> handleRequest(event, out));
                    } else {
                        System.out.println("[ASSISTANT] Ignored: " + ev.type());
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
            // Deserialise the request payload
            JsonNode req      = Event.MAPPER.readTree(ev.payload());
            String userPrompt = req.path("prompt").asText(ev.payload());
            String ragContext = req.path("ragContext").asText("");
            String toolList   = req.path("toolList").asText("");

            System.out.println("[ASSISTANT] Responding to: " + truncate(userPrompt, 80));

            String fullPrompt = buildPrompt(userPrompt, ragContext, toolList);
            String response   = callLLM(fullPrompt);

            if (response.isBlank()) {
                response = "I'm sorry, I was unable to generate a response. Please try again.";
            }

            System.out.println("[ASSISTANT] Response ready (" + response.length() + " chars).");
            publish(Event.reply(ev, "assistant.response", response, PLUGIN_ID), out);

        } catch (Exception e) {
            System.err.println("[ASSISTANT] Error: " + e.getMessage());
            publish(Event.reply(ev, "assistant.response",
                    "Error generating response: " + e.getMessage(), PLUGIN_ID), out);
        }
    }

    // -------------------------------------------------------------------------
    // Prompt builder (mirrors JForgeAgent.buildAssistantPrompt)
    // -------------------------------------------------------------------------

    private static String buildPrompt(String userPrompt, String ragContext, String toolList) {
        String clock = LocalDateTime.now().format(FMT_CLOCK)
                + " | Zone: " + java.time.ZoneId.systemDefault();

        StringBuilder sb = new StringBuilder();
        sb.append("[Local System Clock]\n").append(clock).append("\n\n");
        sb.append("Original Request: ").append(userPrompt);

        if (toolList != null && !toolList.isBlank() && !"Empty".equals(toolList)) {
            sb.append("\n\n[System State - Available Cached Tools]:\n").append(toolList);
        }

        if (ragContext != null && !ragContext.isBlank()) {
            sb.append("\n\n[RAG Context for Factual Accuracy]:\n").append(ragContext);
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // LLM call
    // -------------------------------------------------------------------------

    private static String callLLM(String prompt) {
        try {
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(ASSISTANT_INSTRUCTION),
                    UserMessage.from(prompt));
            AiMessage response = model.generate(messages).content();
            return response.text().trim();
        } catch (Exception e) {
            System.err.println("[ASSISTANT] LLM call failed: " + e.getMessage());
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
                System.err.println("[ASSISTANT] Error publishing: " + e.getMessage());
            }
        }
    }
}
