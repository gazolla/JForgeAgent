// Shared file — included via //SOURCES in all plugins and the kernel
// No JBang header (not an entry point)

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Unified system event.
 *
 * Nullable fields are omitted from JSON when null (NON_NULL serialization),
 * so older plugins that don't produce them remain fully compatible.
 */
public record Event(
    String        id,            // UUID — always present
    String        type,          // event type
    String        payload,       // content (plain string or JSON string)
    LocalDateTime timestamp,     // creation time
    String        source,        // who created this event
    String        correlationId, // nullable — links a request to its response
    String        sessionId,     // nullable — isolates user conversations
    String        workflowId,    // nullable — groups all events of a workflow run
    String        replyTo        // nullable — expected response event type
) {

    /** Single ObjectMapper — configured once, shared across the entire system. */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    // -------------------------------------------------------------------------
    // Factory methods — pick the one that fits your context
    // -------------------------------------------------------------------------

    /** Simple event — no session, no workflow, no correlation. */
    public static Event of(String type, String payload, String source) {
        return new Event(uuid(), type, payload, now(), source, null, null, null, null);
    }

    /** Event carrying a user session (e.g., chat messages from ConsolePlugin). */
    public static Event withSession(String type, String payload, String source, String sessionId) {
        return new Event(uuid(), type, payload, now(), source, null, sessionId, null, null);
    }

    /**
     * Outbound request expecting a typed reply.
     * The receiver should copy correlationId and workflowId back in its response.
     */
    public static Event request(String type, String payload, String source,
                                String correlationId, String workflowId, String replyTo) {
        return new Event(uuid(), type, payload, now(), source, correlationId, null, workflowId, replyTo);
    }

    /**
     * Reply to a previous event — automatically propagates correlationId,
     * sessionId and workflowId from the original so the requester can match it.
     */
    public static Event reply(Event origin, String type, String payload, String source) {
        return new Event(uuid(), type, payload, now(), source,
                origin.correlationId(), origin.sessionId(), origin.workflowId(), null);
    }

    private static String        uuid() { return UUID.randomUUID().toString(); }
    private static LocalDateTime now()  { return LocalDateTime.now(); }

    // -------------------------------------------------------------------------
    // Workflow domain records — shared across all orchestration plugins
    // -------------------------------------------------------------------------

    /**
     * One task inside a WorkflowPlan.
     * The Router decides HOW to achieve the goal (SEARCH / CREATE / EXECUTE / CHAT).
     * The Supervisor only decides WHAT to achieve and in what order.
     */
    public record WorkflowStep(
        String       id,         // unique identifier: s1, s2, ...
        String       goal,       // sub-goal (may contain <<stepId>> placeholders)
        List<String> dependsOn   // step IDs that must complete before this one starts
    ) {}

    /** The full plan returned by the Supervisor. */
    public record WorkflowPlan(
        String           type,   // SIMPLE | REUSE | WORKFLOW
        String           goal,
        List<WorkflowStep> steps,
        String           history,        // recent memory snapshot (injected by SupervisorPlugin)
        String           originalPrompt  // verbatim user message
    ) {
        /** True when the Supervisor decided no workflow planning is needed. */
        public boolean isSimple()   { return "SIMPLE".equals(type); }
        public boolean isReuse()    { return "REUSE".equals(type);  }
        public boolean isWorkflow() { return "WORKFLOW".equals(type); }
    }

    /**
     * Mutable state for one Router loop (one step or the entire SIMPLE demand).
     * Serialised to JSON and sent as the payload of router.request so RouterPlugin
     * can build the full state prompt without needing a separate memory call.
     */
    public record RouterState(
        String              goal,
        String              cachedTools,
        String              ragContext,
        String              lastError,
        String              history,
        String              clock,
        int                 crashRetries,
        int                 searchCount,
        Map<String, String> stepResults  // previous steps' outputs for chaining
    ) {}

    // -------------------------------------------------------------------------
    // Declarative plugin contract
    // -------------------------------------------------------------------------

    /**
     * Represents each plugin's plugin.json.
     * The kernel uses 'subscribes' to build the routing table dynamically.
     * 'publishes' is metadata (documentation, future validation).
     */
    public record PluginConfig(String id, List<String> subscribes, List<String> publishes) {}

    /** Loads plugin.json from the given path (relative to the plugin's working directory). */
    public static PluginConfig loadConfig(String jsonPath) throws Exception {
        return MAPPER.readValue(Path.of(jsonPath).toFile(), PluginConfig.class);
    }

    // -------------------------------------------------------------------------
    // UDS connection boilerplate
    // -------------------------------------------------------------------------

    /** Plugin logic contract — throws Exception to simplify error handling. */
    @FunctionalInterface
    public interface PluginLogic {
        void run(BufferedReader in, BufferedWriter out) throws Exception;
    }

    /**
     * Connects to the kernel via UDS, auto-registers with the full PluginConfig
     * (kernel builds routing from this), then executes the plugin logic.
     *
     * Works equally for boot-time and dynamically started plugins.
     */
    public static void connectAndRun(String socketPath, PluginConfig config, PluginLogic logic) {
        var addr = UnixDomainSocketAddress.of(Path.of(socketPath));
        try (var ch  = SocketChannel.open(addr);
             var out = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(ch)));
             var in  = new BufferedReader(new InputStreamReader(Channels.newInputStream(ch)))) {

            System.out.println("[" + config.id().toUpperCase() + "] Connected to Kernel via UDS.");

            String configJson = MAPPER.writeValueAsString(config);
            out.write(MAPPER.writeValueAsString(Event.of("plugin.register", configJson, config.id())));
            out.newLine();
            out.flush();

            logic.run(in, out);

        } catch (Exception e) {
            System.err.println("[" + config.id().toUpperCase() + "] Error: " + e.getMessage());
        }
    }
}
