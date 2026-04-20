///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//DEPS dev.langchain4j:langchain4j-core:0.36.2
//DEPS dev.langchain4j:langchain4j-open-ai:0.36.2
//SOURCES ../../shared/Event.java
//DESCRIPTION MK3 Coder Plugin — generates and saves JBang tool scripts (UDS)

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * CoderPlugin — the Java/JBang code generation layer.
 *
 * Subscribes: coder.request
 *   Payload (JSON):
 *   {
 *     "mode":         "CREATE" | "EDIT",
 *     "instruction":  "what to build",
 *     "existingCode": "current source (EDIT only)",
 *     "lastError":    "previous failure trace (optional)"
 *   }
 *
 * Publishes: coder.result
 *   Payload (JSON):
 *   {
 *     "success":         true | false,
 *     "fileName":        "ToolName.java",
 *     "metadataContent": "{ ... }",
 *     "error":           "failure reason (on failure)"
 *   }
 *
 * On success the generated .java and .meta.json are written to tools/.
 */
public class CoderPlugin {

    private static final String  SOCKET     = "../../kernel/kernel.sock";
    private static final String  PLUGIN_ID  = "coder-plugin";
    private static final String  MODEL_NAME = "llama-3.3-70b-versatile";
    private static final Path    TOOLS_DIR  = Path.of("../../tools");
    private static final Pattern SAFE_NAME  = Pattern.compile("[A-Za-z0-9_\\-]+\\.java");

    private static ChatLanguageModel model;

    // -------------------------------------------------------------------------
    // Workspace topology — absolute paths injected into every Coder prompt
    // -------------------------------------------------------------------------

    private static final String WORKSPACE_TOPOLOGY = String.format(
            """
            Workspace Architecture (Absolute Paths):
            - TOOLS:     %s
            - LOGS:      %s
            - ARTIFACTS: %s  (Instruct tools to use this EXACT ABSOLUTE PATH for temporary data)
            - PRODUCTS:  %s  (Instruct tools to save final output files using this EXACT ABSOLUTE PATH)

            MANDATORY RULE: When creating tools that write files, feed them the literal absolute
            path strings above. Do NOT use relative paths like './products'.
            """,
            Path.of("../../tools").toAbsolutePath(),
            Path.of("../../logs").toAbsolutePath(),
            Path.of("../../artifacts").toAbsolutePath(),
            Path.of("../../products").toAbsolutePath()
    );

    // -------------------------------------------------------------------------
    // Coder LLM instruction (ported from JForgeAgent.java)
    // -------------------------------------------------------------------------

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
            CRITICAL ERROR HANDLING: Do not swallow exceptions in empty try-catch blocks. If a fatal failure
            occurs (e.g. network error, bad API), the script MUST crash explicitly or call System.exit(1)
            so the orchestrator can detect the failure.
            GENERIC AND REUSABLE: Never hardcode values that may vary between invocations (city names,
            coordinates, amounts, currency symbols, dates, file names). Always accept them as args[0],
            args[1], etc. A tool must produce correct results for any valid input of the same type, not
            just the one from the original request. The tool name must reflect its generic purpose
            (e.g. WeatherFetcher.java, not RioWeatherFetcher.java).

            CRITICAL - NO SILENCING: Do not "fix" environmental errors (missing credentials/secrets)
            by making the tool exit successfully with a warning. It MUST fail explicitly so the
            orchestrator can detect the missing requirement.

            ── APPROVED LIBRARY REFERENCE ────────────────────────────────────────────
            Use ONLY the APIs shown below. Do NOT invent method names.

            ▸ CHARTS (XChart 3.8.7) — //DEPS org.knowm.xchart:xchart:3.8.7
              For BAR CHARTS use CategoryChart, NOT XYChart:
                import org.knowm.xchart.*;
                import org.knowm.xchart.style.Styler;
                import org.knowm.xchart.style.CategoryStyler;
                import java.util.Arrays;

                CategoryChart chart = new CategoryChartBuilder()
                    .width(800).height(500).title("Title")
                    .xAxisTitle("X").yAxisTitle("Y").build();
                chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNW);
                // horizontal bar: rotate category axis labels
                chart.addSeries("label",
                    Arrays.asList("A","B","C"),
                    Arrays.asList(1.0, 2.0, 3.0));
                // SAVE — the ONLY correct method:
                BitmapEncoder.saveBitmap(chart, "/abs/path/file.png",
                    BitmapEncoder.BitmapFormat.PNG);
                System.out.println("Chart saved to /abs/path/file.png");

              For LINE / SCATTER use XYChart:
                XYChart chart = new XYChartBuilder()
                    .width(800).height(500).title("T").build();
                chart.addSeries("s", xList, yList);
                BitmapEncoder.saveBitmap(chart, path, BitmapEncoder.BitmapFormat.PNG);

              NEVER use: saveChartAsBitmap(), XYChart.SeriesRenderStyle, Styler.ChartTheme

            ▸ HTTP JSON (no extra dep — use java.net.http built-in Java 21):
                import java.net.http.*;
                import java.net.URI;
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("User-Agent","Mozilla/5.0").build();
                String body = client.send(req,
                    HttpResponse.BodyHandlers.ofString()).body();

            ▸ JSON parsing (org.json) — //DEPS org.json:json:20231013
                JSONObject obj = new JSONObject(body);
                double val = obj.getDouble("field");
                JSONArray arr = obj.getJSONArray("items");

            ▸ CoinGecko (free, no API key) — preferred for crypto prices:
                URL: https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum,solana&vs_currencies=usd
                Response: {"bitcoin":{"usd":XXXXX},"ethereum":{"usd":YYYY},"solana":{"usd":ZZZ}}
                Parse:  double price = obj.getJSONObject("bitcoin").getDouble("usd");
            ──────────────────────────────────────────────────────────────────────────
            """;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        System.out.println("[CODER] Starting...");

        String apiKey = System.getenv("GROQ_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[CODER] GROQ_API_KEY is not set.");
            return;
        }

        model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl("https://api.groq.com/openai/v1")
                .modelName(MODEL_NAME)
                .logRequests(false)
                .logResponses(false)
                .build();

        Files.createDirectories(TOOLS_DIR);
        System.out.println("[CODER] Model initialised: " + MODEL_NAME);

        var config = Event.loadConfig("plugin.json");

        Event.connectAndRun(SOCKET, config, (in, out) -> {
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    Event ev = Event.MAPPER.readValue(line, Event.class);
                    if ("coder.request".equals(ev.type())) {
                        final Event event = ev;
                        executor.submit(() -> handleRequest(event, out));
                    } else {
                        System.out.println("[CODER] Ignored: " + ev.type());
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
            String mode     = req.path("mode").asText("CREATE");
            String instr    = req.path("instruction").asText("");
            String existing = req.path("existingCode").asText("");
            String lastErr  = req.path("lastError").asText("");

            System.out.println("[CODER] " + mode + ": " + truncate(instr, 80));

            String prompt = "CREATE".equals(mode)
                    ? buildCreatePrompt(instr, lastErr)
                    : buildEditPrompt(instr, existing, lastErr);

            String generated = callLLM(prompt);

            if (generated.isBlank()) {
                publishFailure(ev, "Coder LLM returned empty response.", out);
                return;
            }

            CodeGenResult result = generateCode(generated);
            publishSuccess(ev, result, out);

        } catch (IOException e) {
            System.err.println("[CODER] Code generation failed: " + e.getMessage());
            publishFailure(ev, e.getMessage(), out);
        } catch (Exception e) {
            System.err.println("[CODER] Unexpected error: " + e.getMessage());
            publishFailure(ev, "Unexpected error: " + e.getMessage(), out);
        }
    }

    // -------------------------------------------------------------------------
    // Code generation pipeline (ported from JForgeAgent.handleCodeGeneration)
    // -------------------------------------------------------------------------

    private static CodeGenResult generateCode(String raw) throws IOException {
        // Strip accidental markdown fences
        String code = raw.replace("```java", "").replace("```json", "").replace("```", "").trim();

        // --- Extract metadata block ---
        String metadataContent = "";
        int metaStart = code.indexOf("//METADATA_START");
        int metaEnd   = code.indexOf("//METADATA_END");
        if (metaStart != -1 && metaEnd != -1) {
            metadataContent = code.substring(metaStart + 16, metaEnd).trim();
            code = code.substring(metaEnd + 14).trim();
        }

        // --- Extract file name from //FILE: directive ---
        if (!code.startsWith("//FILE:")) {
            throw new IOException(
                "LLM output missing //FILE: directive — incomplete or malformed generation.");
        }
        int nl = code.indexOf('\n');
        if (nl == -1) {
            throw new IOException(
                "LLM output has //FILE: directive but no code body after it.");
        }
        String fileName = code.substring(7, nl).trim();
        code = code.substring(nl).trim();

        // --- Security: validate tool name ---
        if (!isToolNameSafe(fileName)) {
            throw new IOException("Rejected unsafe file name from LLM: '" + fileName + "'");
        }

        // --- Structural validation (4 checks) ---
        validateCodeStructure(code, fileName);

        // --- Write to disk ---
        Files.writeString(TOOLS_DIR.resolve(fileName), code);
        System.out.println("[CODER] Saved: " + fileName);

        if (!metadataContent.isBlank()) {
            String metaFile = toMetaName(fileName);
            Files.writeString(TOOLS_DIR.resolve(metaFile), metadataContent);
            System.out.println("[CODER] Metadata saved: " + metaFile);
        }

        return new CodeGenResult(fileName, metadataContent);
    }

    // -------------------------------------------------------------------------
    // Validation (ported from JForgeAgent.validateCodeStructure)
    // -------------------------------------------------------------------------

    private static void validateCodeStructure(String code, String fileName) throws IOException {
        record Check(boolean fail, String msg) {}
        for (var c : new Check[]{
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
                throw new IOException(
                    "Validation failed for '" + fileName + "': " + c.msg());
            }
        }
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

    private static String toMetaName(String javaFile) {
        int dot = javaFile.lastIndexOf(".java");
        return dot == -1 ? javaFile + ".meta.json" : javaFile.substring(0, dot) + ".meta.json";
    }

    // -------------------------------------------------------------------------
    // Prompt builders
    // -------------------------------------------------------------------------

    private static String buildCreatePrompt(String instruction, String lastError) {
        String prompt = WORKSPACE_TOPOLOGY + "\n" + instruction;
        return appendError(prompt, lastError);
    }

    private static String buildEditPrompt(String changes, String existingCode, String lastError) {
        String prompt = WORKSPACE_TOPOLOGY
                + "\nRewrite the following tool applying these changes: " + changes
                + "\n\n[EXISTING CODE]\n" + existingCode;
        return appendError(prompt, lastError);
    }

    private static String appendError(String prompt, String lastError) {
        return (lastError == null || lastError.isBlank()) ? prompt
                : prompt + "\nImportant: Last execution crashed. CORRECT these issues:\n" + lastError;
    }

    // -------------------------------------------------------------------------
    // LLM call
    // -------------------------------------------------------------------------

    private static String callLLM(String prompt) {
        try {
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(CODER_INSTRUCTION),
                    UserMessage.from(prompt));
            AiMessage response = model.generate(messages).content();
            return response.text().trim();
        } catch (Exception e) {
            System.err.println("[CODER] LLM call failed: " + e.getMessage());
            return "";
        }
    }

    // -------------------------------------------------------------------------
    // Publishing
    // -------------------------------------------------------------------------

    private static void publishSuccess(Event origin, CodeGenResult r, BufferedWriter out) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success",         true);
        payload.put("fileName",        r.fileName());
        payload.put("metadataContent", r.metadataContent());
        publish(Event.reply(origin, "coder.result",
                toJson(payload), PLUGIN_ID), out);
        System.out.println("[CODER] Success → " + r.fileName());
    }

    private static void publishFailure(Event origin, String reason, BufferedWriter out) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("error",   reason);
        publish(Event.reply(origin, "coder.result",
                toJson(payload), PLUGIN_ID), out);
        System.err.println("[CODER] Failure: " + reason);
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
                System.err.println("[CODER] Error publishing: " + e.getMessage());
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // -------------------------------------------------------------------------
    // Internal record
    // -------------------------------------------------------------------------

    private record CodeGenResult(String fileName, String metadataContent) {}
}
