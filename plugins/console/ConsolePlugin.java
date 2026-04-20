///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../shared/Event.java
//DESCRIPTION MK3 Console Plugin — interactive terminal for the orchestrated agent system (UDS)

import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ConsolePlugin — the interactive terminal UI for the MK3 agent system.
 *
 * Message routing:
 *
 *   User text input
 *     └─ chat.prompt ──────────────────► SupervisorPlugin
 *                                           └─ supervisor.response ──► Orchestrator
 *                                                                          └─ (full pipeline)
 *                                                                             └─ chat.response ──► here
 *
 *   User "!ToolName arg" input  (direct bypass — skips Supervisor/Router)
 *     └─ tool.invoke ──────────────────► ToolRunner
 *                                           └─ tool.result ───────────► here
 *
 * The ConsolePlugin does NOT know about SupervisorPlugin, RouterPlugin, etc.
 * It only publishes chat.prompt / tool.invoke and displays chat.response / tool.result.
 * All orchestration is transparent to the user.
 *
 * Multi-user: sessionId isolates each terminal's LLM conversation history.
 *             Prompts from other sessions are shown as [OTHER USER].
 */
public class ConsolePlugin {

    private static final String SOCKET = "../../kernel/kernel.sock";
    private static final String BANNER = """
            ╔══════════════════════════════════════════════╗
            ║         JForge Agent  ·  MK3 Microkernel     ║
            ║  Type your request, or !ToolName arg to run  ║
            ║  a tool directly. Type 'exit' to quit.       ║
            ╚══════════════════════════════════════════════╝
            """;

    public static void main(String[] args) throws Exception {
        System.out.println("[CONSOLE] Starting...");

        var config    = Event.loadConfig("plugin.json");
        var sessionId = UUID.randomUUID().toString();

        // Tracks whether we're waiting for a response (for the "thinking" indicator)
        AtomicBoolean waiting = new AtomicBoolean(false);

        Event.connectAndRun(SOCKET, config, (in, out) -> {
            System.out.println(BANNER);
            System.out.println("[CONSOLE] Session: " + sessionId.substring(0, 8) + "...");

            // ── Receiver thread — reads events from the kernel ───────────────
            var receiver = Executors.newVirtualThreadPerTaskExecutor();
            receiver.submit(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        Event ev = Event.MAPPER.readValue(line, Event.class);
                        switch (ev.type()) {

                            case "chat.response" -> {
                                waiting.set(false);
                                System.out.println("\n[AI] " + ev.payload());
                                System.out.print("\nYou: ");
                                System.out.flush();
                            }

                            case "tool.result" -> {
                                waiting.set(false);
                                System.out.println("\n[TOOL RESULT]\n" + ev.payload());
                                System.out.print("\nYou: ");
                                System.out.flush();
                            }

                            case "chat.prompt" -> {
                                // Show messages from other users in a multi-user session
                                if (!config.id().equals(ev.source())) {
                                    System.out.println("\n[OTHER USER] " + ev.payload());
                                    System.out.print("You: ");
                                    System.out.flush();
                                }
                            }

                            default -> { /* ignore other event types */ }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[CONSOLE] Receiver error: " + e.getMessage());
                }
            });

            // ── Main input loop ──────────────────────────────────────────────
            try (var scanner = new Scanner(System.in)) {
                while (true) {
                    System.out.print("You: ");
                    System.out.flush();

                    String input = scanner.nextLine().trim();
                    if (input.isEmpty()) continue;
                    if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) break;

                    Event event;

                    if (input.startsWith("!")) {
                        // ── Direct tool invocation — bypasses Supervisor/Router ──
                        // Format: !ToolName optional input text
                        String[] parts    = input.substring(1).split(" ", 2);
                        String toolName   = parts[0];
                        String toolInput  = parts.length > 1 ? parts[1] : "";
                        String payload    = Event.MAPPER.writeValueAsString(
                                Map.of("tool", toolName, "input", toolInput));
                        event = Event.withSession("tool.invoke", payload, config.id(), sessionId);
                        System.out.println("[CONSOLE] → ToolRunner: " + toolName);

                    } else {
                        // ── Orchestrated demand — routes through Supervisor ──────
                        event = Event.withSession("chat.prompt", input, config.id(), sessionId);
                        System.out.println("[CONSOLE] → Supervisor (processing...)");
                    }

                    waiting.set(true);
                    out.write(Event.MAPPER.writeValueAsString(event));
                    out.newLine();
                    out.flush();
                }
            } finally {
                receiver.shutdownNow();
            }
        });

        System.out.println("[CONSOLE] Exited.");
    }
}
