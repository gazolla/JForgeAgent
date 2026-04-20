///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../shared/Event.java
//DESCRIPTION MK3 Garbage Collector Plugin — evicts stale tools and workflows (UDS)

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * GarbageCollectorPlugin — passive artifact lifecycle manager.
 *
 * Triggered by the Orchestrator once per minute (throttled at source) via
 * an {@code artifact.gc} event. Applies two-phase eviction independently to
 * the tools/ and workflows/ directories.
 *
 * Phase 1 — Age eviction:
 *   Delete files whose last-modified time exceeds {@code maxAgeDays}.
 *
 * Phase 2 — Count eviction:
 *   If the remaining file count still exceeds {@code maxCount}, delete the
 *   oldest files until the limit is satisfied.
 *
 * Sidecar cleanup:
 *   Every deleted {@code .java} tool also removes its {@code .meta.json}.
 *   Every deleted {@code .json} workflow also removes its {@code .fail.json}.
 *
 * Subscribes: artifact.gc
 *   Payload (JSON):
 *   {
 *     "toolAgeDays":      30,
 *     "maxTools":         10,
 *     "workflowAgeDays":  30,
 *     "maxWorkflows":     10
 *   }
 *
 * Publishes: (none — observer only)
 */
public class GarbageCollectorPlugin {

    private static final String SOCKET        = "../../kernel/kernel.sock";
    private static final String PLUGIN_ID     = "gc-plugin";
    private static final Path   TOOLS_DIR     = Path.of("../../tools");
    private static final Path   WORKFLOWS_DIR = Path.of("../../workflows");

    /** Internal throttle — GC runs at most once per minute regardless of trigger rate. */
    private static volatile long lastRun = 0L;
    private static final long    THROTTLE_MS = 60_000L;

    public static void main(String[] args) throws Exception {
        System.out.println("[GC] Starting...");

        var config = Event.loadConfig("plugin.json");

        Event.connectAndRun(SOCKET, config, (in, out) -> {
            String line;
            while ((line = in.readLine()) != null) {
                Event ev = Event.MAPPER.readValue(line, Event.class);
                if ("artifact.gc".equals(ev.type())) {
                    handleGC(ev);
                } else {
                    System.out.println("[GC] Ignored: " + ev.type());
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // GC handler
    // -------------------------------------------------------------------------

    private static synchronized void handleGC(Event ev) {
        long now = System.currentTimeMillis();
        if (now - lastRun < THROTTLE_MS) {
            System.out.println("[GC] Throttled — skipping (last run " + (now - lastRun) / 1000 + "s ago).");
            return;
        }
        lastRun = now;

        // Parse configuration from payload
        long toolAgeDays     = 30;
        int  maxTools        = 10;
        long workflowAgeDays = 30;
        int  maxWorkflows    = 10;

        try {
            JsonNode cfg = Event.MAPPER.readTree(ev.payload());
            toolAgeDays     = cfg.path("toolAgeDays").asLong(30);
            maxTools        = cfg.path("maxTools").asInt(10);
            workflowAgeDays = cfg.path("workflowAgeDays").asLong(30);
            maxWorkflows    = cfg.path("maxWorkflows").asInt(10);
        } catch (Exception e) {
            System.err.println("[GC] Failed to parse config — using defaults: " + e.getMessage());
        }

        System.out.println("[GC] Running — tools(age=" + toolAgeDays + "d, max=" + maxTools
                + ") | workflows(age=" + workflowAgeDays + "d, max=" + maxWorkflows + ")");

        // Evict tools (primary: .java files; sidecar: .meta.json)
        evictArtifacts(
                TOOLS_DIR,
                ".java",
                toolAgeDays,
                maxTools,
                p -> {
                    String name = p.getFileName().toString();
                    int dot = name.lastIndexOf(".java");
                    return dot == -1 ? null
                            : TOOLS_DIR.resolve(name.substring(0, dot) + ".meta.json");
                });

        // Evict workflows (primary: .json non-fail files; sidecar: .fail.json)
        evictArtifacts(
                WORKFLOWS_DIR,
                ".json",
                workflowAgeDays,
                maxWorkflows,
                p -> {
                    String name = p.getFileName().toString();
                    if (name.endsWith(".fail.json")) return null; // skip sidecar files themselves
                    return WORKFLOWS_DIR.resolve(name.replace(".json", ".fail.json"));
                });

        System.out.println("[GC] Done.");
    }

    // -------------------------------------------------------------------------
    // Two-phase eviction (ported from JForgeAgent.evictArtifacts)
    // -------------------------------------------------------------------------

    @FunctionalInterface
    interface SidecarFn {
        /** Returns the sidecar path for a primary artifact, or null if none. */
        Path sidecar(Path primary);
    }

    /**
     * @param dir        directory to scan
     * @param extension  primary file extension to target (e.g. ".java")
     * @param maxAgeDays files older than this are deleted in phase 1
     * @param maxCount   after phase 1, if remaining > maxCount → delete oldest
     * @param sidecarFn  derives the companion file path (may return null)
     */
    private static void evictArtifacts(Path dir, String extension,
            long maxAgeDays, int maxCount, SidecarFn sidecarFn) {

        if (!Files.exists(dir)) return;

        List<Path> files;
        try {
            files = Files.list(dir)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(extension) && !name.endsWith(".fail.json");
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            System.err.println("[GC] Cannot list " + dir + ": " + e.getMessage());
            return;
        }

        Instant cutoff = Instant.now().minus(maxAgeDays, ChronoUnit.DAYS);

        // ── Phase 1: age-based eviction ──────────────────────────────────────
        files.removeIf(p -> {
            try {
                FileTime mtime = Files.getLastModifiedTime(p);
                if (mtime.toInstant().isBefore(cutoff)) {
                    deleteWithSidecar(p, sidecarFn, "age");
                    return true; // remove from list
                }
            } catch (IOException e) {
                System.err.println("[GC] Cannot read mtime of " + p + ": " + e.getMessage());
            }
            return false;
        });

        // ── Phase 2: count-based eviction (oldest-first) ─────────────────────
        if (files.size() > maxCount) {
            Comparator<Path> byMtimeAsc = (a, b) -> {
                try {
                    return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
                } catch (IOException e) { return 0; }
            };
            files.sort(byMtimeAsc);

            int toDelete = files.size() - maxCount;
            for (int i = 0; i < toDelete; i++) {
                deleteWithSidecar(files.get(i), sidecarFn, "count");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Deletion helpers
    // -------------------------------------------------------------------------

    private static void deleteWithSidecar(Path primary, SidecarFn sidecarFn, String reason) {
        deleteSilently(primary, reason);
        try {
            Path sidecar = sidecarFn.sidecar(primary);
            if (sidecar != null && Files.exists(sidecar)) {
                deleteSilently(sidecar, reason + "-sidecar");
            }
        } catch (Exception e) {
            System.err.println("[GC] Sidecar error for " + primary + ": " + e.getMessage());
        }
    }

    private static void deleteSilently(Path path, String reason) {
        try {
            Files.deleteIfExists(path);
            System.out.println("[GC] Deleted (" + reason + "): " + path.getFileName());
        } catch (IOException e) {
            System.err.println("[GC] Failed to delete " + path + ": " + e.getMessage());
        }
    }
}
