///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS dev.langchain4j:langchain4j-core:0.36.2
//DEPS dev.langchain4j:langchain4j-open-ai:0.36.2
//SOURCES ../../shared/Event.java
//DESCRIPTION MK3 LLM Plugin — LangChain4j / Groq (UDS)

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.io.BufferedWriter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class LLMPlugin {

    private static final String SOCKET   = "../../kernel/kernel.sock";
    private static final int    MAX_HIST = 10;

    /**
     * History keyed by sessionId (for chat.prompt from ConsolePlugin)
     * or by workflowId (for llm.invoke from ToolRunner).
     * Falls back to "global" when both are null.
     */
    private static final Map<String, LinkedList<ChatMessage>> histories = new ConcurrentHashMap<>();
    private static ChatLanguageModel model;

    public static void main(String[] args) throws Exception {
        System.out.println("[LLM] Starting...");

        String apiKey = System.getenv("GROQ_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("[LLM] GROQ_API_KEY environment variable is not set.");
            return;
        }

        model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl("https://api.groq.com/openai/v1")
                .modelName("llama-3.3-70b-versatile")
                .logRequests(true)
                .logResponses(true)
                .build();

        System.out.println("[LLM] Groq model initialized.");

        var config = Event.loadConfig("plugin.json");

        Event.connectAndRun(SOCKET, config, (in, out) -> {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    String eventJson = line;
                    executor.submit(() -> processEvent(eventJson, out));
                }
            } finally {
                executor.shutdownNow();
            }
        });
    }

    private static void processEvent(String eventJson, BufferedWriter out) {
        try {
            Event ev = Event.MAPPER.readValue(eventJson, Event.class);

            switch (ev.type()) {
                case "chat.prompt" -> {
                    // Conversation keyed by sessionId — each user has isolated history
                    String key = ev.sessionId() != null ? ev.sessionId() : "global";
                    String response = generate(ev.payload(), key);
                    publish(Event.reply(ev, "chat.response", response, "llm-plugin"), out);
                }
                case "llm.invoke" -> {
                    // Tool workflow call — keyed by workflowId for workflow-scoped context
                    String key = ev.workflowId() != null ? ev.workflowId() : "global";
                    String response = generate(ev.payload(), key);
                    // Use replyTo if specified, otherwise default to llm.result
                    String replyType = ev.replyTo() != null ? ev.replyTo() : "llm.result";
                    publish(Event.reply(ev, replyType, response, "llm-plugin"), out);
                }
                default -> System.out.println("[LLM] Ignored event type: " + ev.type());
            }

        } catch (Exception e) {
            System.err.println("[LLM] Error processing event: " + e.getMessage());
        }
    }

    /** Calls the LLM using the history for the given key, then updates it. */
    private static String generate(String prompt, String historyKey) {
        addToHistory(historyKey, UserMessage.from(prompt));
        List<ChatMessage> history = getHistory(historyKey);

        System.out.println("[LLM] Prompt [" + historyKey + "]: " + prompt);
        AiMessage response = model.generate(history).content();

        addToHistory(historyKey, response);
        System.out.println("[LLM] Response [" + historyKey + "]: " + response.text());
        return response.text();
    }

    private static synchronized void addToHistory(String key, ChatMessage msg) {
        LinkedList<ChatMessage> history = histories.computeIfAbsent(key, k -> new LinkedList<>());
        history.add(msg);
        while (history.size() > MAX_HIST) history.removeFirst();
    }

    private static synchronized List<ChatMessage> getHistory(String key) {
        return new ArrayList<>(histories.getOrDefault(key, new LinkedList<>()));
    }

    private static void publish(Event event, BufferedWriter out) throws Exception {
        synchronized (out) {
            out.write(Event.MAPPER.writeValueAsString(event));
            out.newLine();
            out.flush();
        }
    }
}
