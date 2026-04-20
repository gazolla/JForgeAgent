///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../shared/Event.java
//DESCRIPTION MK3 Workflow Persistence Plugin — saves and audits workflow plans (UDS)

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * WorkflowPersistencePlugin — durable storage of workflow plans.
 *
 * Receives {@code workflow.saved} events published by the Orchestrator after
 * every completed (or failed) workflow plan and persists them to disk.
 *
 * ── Successful plans ─────────────────────────────────────────────────────────
 *   Saved to: workflows/workflow_<timestamp>.json
 *   Used by SupervisorPlugin for REUSE classification.
 *
 * ── Replan audit logs ────────────────────────────────────────────────────────
 *   Saved to: logs/workflow_<timestamp>_replan.json
 *   Not used for REUSE — kept for debugging only.
 *   Also bumps the failure counter in workflows/<latest>.fail.json so the
 *   Supervisor can deprioritise repeatedly-failing patterns.
 *
 * Subscribes: workflow.saved
 *   Payload (JSON):
 *   {
 *     "plan":     "<WorkflowPlan JSON string>",
 *     "isReplan": true | false
 *   }
 *
 * Publishes: (none — side-effect only)
 */
public class WorkflowPersistencePlugin {

    private static final String SOCKET        = "../../kernel/kernel.sock";
    private static final Path   WORKFLOWS_DIR = Path.of("../../workflows");
    private static final Path   LOGS_DIR      = Path.of("../../logs");

    private static final DateTimeFormatter FMT_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public static void main(String[] args) throws Exception {
        System.out.println("[WORKFLOW-PERSISTENCE] Starting...");

        Files.createDirectories(WORKFLOWS_DIR);
        Files.createDirectories(LOGS_DIR);

        var config = Event.loadConfig("plugin.json");

        Event.connectAndRun(SOCKET, config, (in, out) -> {
            String line;
            while ((line = in.readLine()) != null) {
                Event ev = Event.MAPPER.readValue(line, Event.class);
                if ("workflow.saved".equals(ev.type())) {
                    handleSave(ev);
                } else {
                    System.out.println("[WORKFLOW-PERSISTENCE] Ignored: " + ev.type());
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Handler
    // -------------------------------------------------------------------------

    private static void handleSave(Event ev) {
        try {
            JsonNode payload  = Event.MAPPER.readTree(ev.payload());
            String   planJson = payload.path("plan").asText("");
            boolean  isReplan = payload.path("isReplan").asBoolean(false);

            if (planJson.isBlank()) {
                System.err.println("[WORKFLOW-PERSISTENCE] Empty plan — skipping.");
                return;
            }

            String timestamp = LocalDateTime.now().format(FMT_TS);

            if (isReplan) {
                saveReplan(planJson, timestamp);
            } else {
                saveSuccess(planJson, timestamp);
            }

        } catch (Exception e) {
            System.err.println("[WORKFLOW-PERSISTENCE] Error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Successful workflow — persisted for REUSE
    // -------------------------------------------------------------------------

    private static void saveSuccess(String planJson, String timestamp) throws IOException {
        String filename = "workflow_" + timestamp + ".json";
        Path   target   = WORKFLOWS_DIR.resolve(filename);
        Files.writeString(target, planJson,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("[WORKFLOW-PERSISTENCE] Saved: " + filename);
    }

    // -------------------------------------------------------------------------
    // Replan / failed plan — audit log + failure counter
    // -------------------------------------------------------------------------

    private static void saveReplan(String planJson, String timestamp) throws IOException {
        // Write audit log to logs/
        String auditName = "workflow_" + timestamp + "_replan.json";
        Path   auditFile = LOGS_DIR.resolve(auditName);
        Files.writeString(auditFile, planJson,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("[WORKFLOW-PERSISTENCE] Replan audit: " + auditName);

        // Bump failure counter on the most recent successful workflow (if any)
        bumpFailCount();
    }

    /**
     * Increments the .fail.json sidecar of the most recently saved workflow.
     * This counter is read by SupervisorPlugin to deprioritise broken patterns.
     */
    private static void bumpFailCount() {
        try {
            // Find most recent workflow file
            Path latest = Files.list(WORKFLOWS_DIR)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("workflow_") && n.endsWith(".json")
                                && !n.endsWith(".fail.json");
                    })
                    .max((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(a)
                                        .compareTo(Files.getLastModifiedTime(b));
                        } catch (IOException e) { return 0; }
                    })
                    .orElse(null);

            if (latest == null) return;

            Path failFile = WORKFLOWS_DIR.resolve(
                    latest.getFileName().toString().replace(".json", ".fail.json"));

            int count = 0;
            if (Files.exists(failFile)) {
                try {
                    count = Integer.parseInt(Files.readString(failFile).trim());
                } catch (NumberFormatException ignored) {}
            }
            count++;
            Files.writeString(failFile, String.valueOf(count),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("[WORKFLOW-PERSISTENCE] Failure count for "
                    + latest.getFileName() + ": " + count);

        } catch (IOException e) {
            System.err.println("[WORKFLOW-PERSISTENCE] Could not bump fail count: " + e.getMessage());
        }
    }
}
