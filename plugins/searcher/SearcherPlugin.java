///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS dev.langchain4j:langchain4j-core:0.36.2
//DEPS dev.langchain4j:langchain4j-open-ai:0.36.2
//SOURCES ../../shared/Event.java
//DESCRIPTION MK3 Searcher Plugin — web search via DuckDuckGo + LLM synthesis (UDS)

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.io.BufferedWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SearcherPlugin — web search layer.
 *
 * Strategy (two-tier, no external API key required by default):
 *
 *   Tier 1 — DuckDuckGo Instant Answer API (JSON, free, no key)
 *             Returns AbstractText + RelatedTopics snippets.
 *
 *   Tier 2 — DuckDuckGo HTML scrape as fallback
 *             Extracts result snippets with lightweight regex.
 *
 *   Both tiers feed raw text to the LLM (Groq) which synthesises a
 *   structured plain-text report following the SEARCHER_INSTRUCTION.
 *
 * Optional: set TAVILY_API_KEY env var to upgrade to Tavily search
 *   (higher quality, handles APIs/docs queries better).
 *
 * Subscribes: search.request   (payload = plain query string)
 * Publishes:  search.result    (payload = plain-text report)
 */
public class SearcherPlugin {

    private static final String SOCKET     = "../../kernel/kernel.sock";
    private static final String PLUGIN_ID  = "searcher-plugin";
    private static final String MODEL_NAME = "llama-3.1-8b-instant";

    // DuckDuckGo endpoints
    private static final String DDG_API_URL  =
            "https://api.duckduckgo.com/?q=%s&format=json&no_html=1&skip_disambig=1";
    private static final String DDG_HTML_URL =
            "https://html.duckduckgo.com/html/?q=%s";

    // Tavily endpoint (optional)
    private static final String TAVILY_URL =
            "https://api.tavily.com/search";

    private static final int HTTP_TIMEOUT_S = 15;

    private static ChatLanguageModel model;
    private static HttpClient        httpClient;

    // -------------------------------------------------------------------------
    // Searcher LLM instruction (ported from JForgeAgent.java)
    // -------------------------------------------------------------------------

    private static final String SEARCHER_INSTRUCTION = """
            You are a web search assistant. You receive raw search results and a query.
            Synthesise a structured plain-text report following these rules:

            1. If the query is about finding an API or data endpoint: report the exact free API URL,
               the HTTP method, required parameters, and a sample response or response structure.
               This is the most important output.
            2. If the query is about a factual topic: report key facts, figures, and dates.
            3. Always include the source URL for any specific data point or API endpoint you report.
            4. Do NOT summarise tracker websites or dashboards as an answer — report the underlying
               API instead.
            5. Return plain text only. No markdown, no preamble, no commentary.
            """;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        System.out.println("[SEARCHER] Starting...");

        String apiKey = System.getenv("GROQ_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[SEARCHER] GROQ_API_KEY is not set.");
            return;
        }

        model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl("https://api.groq.com/openai/v1")
                .modelName(MODEL_NAME)
                .logRequests(false)
                .logResponses(false)
                .build();

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_S))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        System.out.println("[SEARCHER] Model initialised: " + MODEL_NAME);

        var config = Event.loadConfig("plugin.json");

        Event.connectAndRun(SOCKET, config, (in, out) -> {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    Event ev = Event.MAPPER.readValue(line, Event.class);
                    if ("search.request".equals(ev.type())) {
                        final Event event = ev;
                        executor.submit(() -> handleRequest(event, out));
                    } else {
                        System.out.println("[SEARCHER] Ignored: " + ev.type());
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
        String query = ev.payload() == null ? "" : ev.payload().trim();
        if (query.isBlank()) {
            publish(Event.reply(ev, "search.result", "Empty search query.", PLUGIN_ID), out);
            return;
        }

        System.out.println("[SEARCHER] Query: " + query);

        try {
            String rawResults = fetchResults(query);
            String report     = synthesise(query, rawResults);
            System.out.println("[SEARCHER] Report ready (" + report.length() + " chars).");
            publish(Event.reply(ev, "search.result", report, PLUGIN_ID), out);
        } catch (Exception e) {
            System.err.println("[SEARCHER] Search failed: " + e.getMessage());
            publish(Event.reply(ev, "search.result",
                    "[SEARCH ERROR] " + e.getMessage(), PLUGIN_ID), out);
        }
    }

    // -------------------------------------------------------------------------
    // Fetch strategy
    // -------------------------------------------------------------------------

    /**
     * Returns raw search text for the LLM to synthesise.
     * Tries Tavily first (if key present), then DDG Instant Answer, then DDG HTML scrape.
     */
    private static String fetchResults(String query) {
        String tavilyKey = System.getenv("TAVILY_API_KEY");
        if (tavilyKey != null && !tavilyKey.isBlank()) {
            try {
                String r = fetchTavily(query, tavilyKey);
                if (!r.isBlank()) return r;
            } catch (Exception e) {
                System.err.println("[SEARCHER] Tavily failed, falling back: " + e.getMessage());
            }
        }

        // Tier 1: DDG Instant Answer API
        try {
            String r = fetchDdgInstant(query);
            if (!r.isBlank()) return r;
        } catch (Exception e) {
            System.err.println("[SEARCHER] DDG Instant failed, falling back: " + e.getMessage());
        }

        // Tier 2: DDG HTML scrape
        try {
            return fetchDdgHtml(query);
        } catch (Exception e) {
            System.err.println("[SEARCHER] DDG HTML failed: " + e.getMessage());
            return "No results found for: " + query;
        }
    }

    // -------------------------------------------------------------------------
    // Tavily (optional — needs TAVILY_API_KEY)
    // -------------------------------------------------------------------------

    private static String fetchTavily(String query, String apiKey) throws Exception {
        String body = Event.MAPPER.writeValueAsString(
                java.util.Map.of(
                        "api_key",        apiKey,
                        "query",          query,
                        "search_depth",   "basic",
                        "include_answer", true,
                        "max_results",    5));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TAVILY_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_S))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return "";

        JsonNode root = Event.MAPPER.readTree(resp.body());
        StringBuilder sb = new StringBuilder();

        if (root.has("answer") && !root.get("answer").asText().isBlank())
            sb.append("Answer: ").append(root.get("answer").asText()).append("\n\n");

        JsonNode results = root.path("results");
        if (results.isArray()) {
            for (JsonNode r : results) {
                sb.append("Title: ").append(r.path("title").asText()).append("\n");
                sb.append("URL:   ").append(r.path("url").asText()).append("\n");
                sb.append("Snippet: ").append(r.path("content").asText()).append("\n\n");
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // DuckDuckGo Instant Answer API
    // -------------------------------------------------------------------------

    private static String fetchDdgInstant(String query) throws Exception {
        String url = String.format(DDG_API_URL,
                URLEncoder.encode(query, StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 JForgeAgent/1.0")
                .GET()
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_S))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return "";

        JsonNode root = Event.MAPPER.readTree(resp.body());
        StringBuilder sb = new StringBuilder();

        String abstractText = root.path("AbstractText").asText("");
        String abstractUrl  = root.path("AbstractURL").asText("");
        if (!abstractText.isBlank()) {
            sb.append(abstractText).append("\n");
            if (!abstractUrl.isBlank()) sb.append("Source: ").append(abstractUrl).append("\n");
            sb.append("\n");
        }

        JsonNode topics = root.path("RelatedTopics");
        if (topics.isArray()) {
            int count = 0;
            for (JsonNode t : topics) {
                if (count++ >= 8) break;
                String text = t.path("Text").asText("");
                String url2 = t.path("FirstURL").asText("");
                if (!text.isBlank()) {
                    sb.append(text).append("\n");
                    if (!url2.isBlank()) sb.append("URL: ").append(url2).append("\n");
                    sb.append("\n");
                }
            }
        }

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // DuckDuckGo HTML scrape (fallback)
    // -------------------------------------------------------------------------

    private static String fetchDdgHtml(String query) throws Exception {
        String url = String.format(DDG_HTML_URL,
                URLEncoder.encode(query, StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (compatible; JForgeAgent/1.0)")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_S))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        String html = resp.body();

        // Extract snippets — DDG wraps them in <a class="result__snippet">
        List<String> snippets = new ArrayList<>();
        Pattern snippetPat = Pattern.compile(
                "class=\"result__snippet\"[^>]*>(.*?)</a>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher m = snippetPat.matcher(html);
        while (m.find() && snippets.size() < 8) {
            String text = m.group(1)
                    .replaceAll("<[^>]+>", "")   // strip inner tags
                    .replaceAll("&amp;",  "&")
                    .replaceAll("&lt;",   "<")
                    .replaceAll("&gt;",   ">")
                    .replaceAll("&quot;", "\"")
                    .replaceAll("&#x27;", "'")
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("\\s+",   " ")
                    .trim();
            if (!text.isBlank()) snippets.add(text);
        }

        // Also grab result URLs
        List<String> urls = new ArrayList<>();
        Pattern urlPat = Pattern.compile(
                "class=\"result__url\"[^>]*>\\s*(.*?)\\s*</",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher um = urlPat.matcher(html);
        while (um.find() && urls.size() < 8) {
            String u = um.group(1).replaceAll("\\s+", "").trim();
            if (!u.isBlank()) urls.add(u);
        }

        if (snippets.isEmpty()) return "No HTML results extracted for: " + query;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < snippets.size(); i++) {
            sb.append(snippets.get(i)).append("\n");
            if (i < urls.size()) sb.append("URL: https://").append(urls.get(i)).append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // LLM synthesis
    // -------------------------------------------------------------------------

    private static String synthesise(String query, String rawResults) {
        if (rawResults.isBlank()) return "No results found for: " + query;
        try {
            String prompt = "Query: " + query + "\n\nRaw search results:\n" + rawResults;
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(SEARCHER_INSTRUCTION),
                    UserMessage.from(prompt));
            AiMessage response = model.generate(messages).content();
            return response.text().replace("```", "").trim();
        } catch (Exception e) {
            System.err.println("[SEARCHER] LLM synthesis failed: " + e.getMessage());
            // Return raw results as fallback
            return rawResults;
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
                System.err.println("[SEARCHER] Error publishing: " + e.getMessage());
            }
        }
    }
}
