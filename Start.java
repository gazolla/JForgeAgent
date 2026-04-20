///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DESCRIPTION MK3 system entry point — starts the kernel and all plugins in order

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MK3 system orchestrator — single entry point for the entire agent system.
 *
 * Usage:
 *   jbang Start.java            (interactive mode)
 *
 * Required environment variable:
 *   GROQ_API_KEY                (Groq API key for all LLM-backed plugins)
 *
 * Optional:
 *   TAVILY_API_KEY              (upgrades SearcherPlugin to Tavily for better web search)
 *
 * Startup sequence:
 *   1.  Kernel                  — event bus; all plugins wait for kernel.sock
 *   2.  MemoryPlugin            — persistent conversation context
 *   3.  LoggerPlugin            — audit log (logs/system.log)
 *   4.  LLMPlugin               — generic LLM for ToolRunner pipeline mode
 *   5.  SupervisorPlugin        — classifies demands: SIMPLE / REUSE / WORKFLOW
 *   6.  RouterPlugin            — decides action per step: EXECUTE/CREATE/EDIT/SEARCH/CHAT
 *   7.  CoderPlugin             — generates JBang tool source code
 *   8.  TesterPlugin            — auto-validates generated tools after CREATE
 *   9.  SearcherPlugin          — web search via DuckDuckGo / Tavily
 *   10. AssistantPlugin         — conversational responses (DELEGATE_CHAT)
 *   11. WorkflowOrchestratorPlugin — central coordinator: workflow execution, router loop
 *   12. GarbageCollectorPlugin  — evicts stale tools and workflows
 *   13. WorkflowPersistencePlugin — persists workflow plans for REUSE
 *   14. ToolRunner              — secure JBang subprocess execution
 *   15. ConsolePlugin           — interactive terminal (inheritIO — user types here)
 *
 * Log files (all under logs/):
 *   logs/kernel.log             — kernel event routing
 *   logs/llm.log                — LLM plugin (generic)
 *   logs/supervisor.log         — supervisor decisions
 *   logs/router.log             — router decisions
 *   logs/coder.log              — code generation
 *   logs/tester.log             — test validation
 *   logs/searcher.log           — search queries
 *   logs/assistant.log          — conversational responses
 *   logs/orchestrator.log       — workflow execution trace
 *   logs/toolrunner.log         — tool subprocess execution
 *   logs/system.log             — all events (written by LoggerPlugin)
 *
 * Console commands:
 *   <text>           → orchestrated demand (Supervisor → Router → action)
 *   !ToolName input  → direct tool invocation via ToolRunner (bypasses Supervisor)
 *   exit             → shuts down the entire system gracefully
 */
public class Start {

    static final Path ROOT = Path.of(".").toAbsolutePath().normalize();

    public static void main(String[] args) throws Exception {
        System.out.println("[START] Starting MK3 agent system...");
        System.out.println("[START] Root: " + ROOT);

        // Guard: GROQ_API_KEY must be set before spending time booting plugins
        if (System.getenv("GROQ_API_KEY") == null || System.getenv("GROQ_API_KEY").isBlank()) {
            System.err.println("[START] ERROR: GROQ_API_KEY environment variable is not set.");
            System.err.println("        Export it and restart: export GROQ_API_KEY=gsk_...");
            System.exit(1);
        }

        // Ensure all workspace directories exist before any plugin starts
        for (String dir : List.of("logs", "tools", "memory", "workflows",
                                  "artifacts", "products")) {
            Files.createDirectories(ROOT.resolve(dir));
        }

        // ── 1. Kernel ────────────────────────────────────────────────────────
        List<Process> processes = new ArrayList<>();
        processes.add(spawn("Kernel.java", "kernel", "logs/kernel.log"));

        System.out.print("[START] Waiting for kernel socket...");
        waitForFile(ROOT.resolve("kernel/kernel.sock"), 15_000);
        System.out.println(" ready.\n");

        // ── 2–14. Background plugins (log-redirected) ────────────────────────
        processes.add(spawn("MemoryPlugin.java",             "plugins/memory",              "logs/memory.log"));
        processes.add(spawn("LoggerPlugin.java",             "plugins/logger",              null));           // writes its own logs/system.log
        processes.add(spawn("LLMPlugin.java",                "plugins/llm",                 "logs/llm.log"));
        processes.add(spawn("SupervisorPlugin.java",         "plugins/supervisor",          "logs/supervisor.log"));
        processes.add(spawn("RouterPlugin.java",             "plugins/router",              "logs/router.log"));
        processes.add(spawn("CoderPlugin.java",              "plugins/coder",               "logs/coder.log"));
        processes.add(spawn("TesterPlugin.java",             "plugins/tester",              "logs/tester.log"));
        processes.add(spawn("SearcherPlugin.java",           "plugins/searcher",            "logs/searcher.log"));
        processes.add(spawn("AssistantPlugin.java",          "plugins/assistant",           "logs/assistant.log"));
        processes.add(spawn("WorkflowOrchestratorPlugin.java","plugins/orchestrator",       "logs/orchestrator.log"));
        processes.add(spawn("GarbageCollectorPlugin.java",   "plugins/gc",                  "logs/gc.log"));
        processes.add(spawn("WorkflowPersistencePlugin.java","plugins/workflowpersistence", "logs/workflowpersistence.log"));
        processes.add(spawn("ToolRunner.java",               "plugins/toolrunner",          "logs/toolrunner.log"));

        // Wait for all background plugins to connect and register with the kernel
        System.out.println("\n[START] Waiting for all plugins to connect (5s)...");
        Thread.sleep(5_000);
        System.out.println("[START] System ready — " + (processes.size()) + " components running.\n");

        // ── 15. Console — user-facing, inherits terminal I/O ─────────────────
        Process console = new ProcessBuilder("jbang", "ConsolePlugin.java")
                .directory(ROOT.resolve("plugins/console").toFile())
                .inheritIO()
                .start();

        processes.add(console);

        // Graceful shutdown when the user types 'exit' (or sends SIGINT)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[START] Shutting down system...");
            // Destroy in reverse startup order
            for (int i = processes.size() - 1; i >= 0; i--) {
                Process p = processes.get(i);
                if (p.isAlive()) {
                    p.destroy();
                    try { p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) {}
                }
            }
            System.out.println("[START] All processes stopped.");
        }));

        System.exit(console.waitFor());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Starts a JBang process with its own working directory.
     * If logFile is null the output is discarded (plugin manages its own log).
     */
    private static Process spawn(String script, String relDir, String logFile) throws Exception {
        var pb = new ProcessBuilder("jbang", script)
                .directory(ROOT.resolve(relDir).toFile());

        if (logFile != null) {
            File log = ROOT.resolve(logFile).toFile();
            pb.redirectOutput(log).redirectError(log);
        } else {
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD);
        }

        Process p = pb.start();
        System.out.println("[START]   + " + script
                + "  (pid " + p.pid() + ")"
                + (logFile != null ? "  →  " + logFile : ""));
        return p;
    }

    /** Polls until a file appears on the filesystem (e.g. kernel.sock). */
    private static void waitForFile(Path path, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!Files.exists(path)) {
            if (System.currentTimeMillis() > deadline)
                throw new RuntimeException("Timeout waiting for: " + path);
            Thread.sleep(300);
            System.out.print(".");
        }
    }
}
