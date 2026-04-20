///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DESCRIPTION MK3 system entry point — starts the kernel, plugins, tools, and console

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * MK3 system orchestrator.
 *
 * Usage:  jbang Start.java
 *         (run from the MK3/ directory)
 *
 * Log files produced (all under logs/):
 *   logs/kernel.log      — kernel event routing logs
 *   logs/llm.log         — LLM plugin logs
 *   logs/toolrunner.log  — ToolRunner orchestration logs
 *   logs/system.log      — all events recorded by the Logger plugin
 *
 * Console commands:
 *   <text>          → sends a chat.prompt to the LLM
 *   !ToolName input → invokes a tool via ToolRunner  (e.g. !WeatherTool London)
 *   exit            → shuts down the entire system
 */
public class Start {

    static final Path ROOT = Path.of(".").toAbsolutePath().normalize();

    public static void main(String[] args) throws Exception {
        System.out.println("[START] Starting MK3 system...");
        System.out.println("[START] Root: " + ROOT);

        // 1. Kernel — must be ready before any plugin connects
        Process kernel = spawn("Kernel.java", "kernel", "logs/kernel.log");
        System.out.print("[START] Waiting for kernel to be ready...");
        waitForFile(ROOT.resolve("kernel/kernel.sock"), 15_000);
        System.out.println(" ready.");

        // 2. Background plugins and tools (output redirected to logs/)
        Process llm        = spawn("LLMPlugin.java",    "plugins/llm",        "logs/llm.log");
        Process logger     = spawn("LoggerPlugin.java", "plugins/logger",     null);        // writes its own logs/system.log
        Process toolRunner = spawn("ToolRunner.java",   "plugins/toolrunner", "logs/toolrunner.log");

        // Wait for all background components to connect to the kernel
        System.out.println("[START] Waiting for plugins and tools to connect (3s)...");
        Thread.sleep(3_000);
        System.out.println("[START] System ready. Opening console...\n");

        // 3. Console with inherited terminal — user interacts here
        Process console = new ProcessBuilder("jbang", "ConsolePlugin.java")
                .directory(ROOT.resolve("plugins/console").toFile())
                .inheritIO()
                .start();

        // Shut everything down when the user types 'exit' in the console
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[START] Shutting down system...");
            for (Process p : List.of(console, toolRunner, llm, logger, kernel)) {
                p.destroy();
            }
        }));

        System.exit(console.waitFor());
    }

    /** Starts a JBang process with its own working directory and log file. */
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
        System.out.println("[START] " + script + " started (pid " + p.pid() + ")");
        return p;
    }

    /** Waits for a file to appear on the filesystem (e.g., kernel.sock). */
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
