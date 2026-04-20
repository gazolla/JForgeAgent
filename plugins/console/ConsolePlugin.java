///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../shared/Event.java
//DESCRIPTION MK3 Console Plugin (UDS)

import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.Executors;

public class ConsolePlugin {

    private static final String SOCKET = "../../kernel/kernel.sock";

    public static void main(String[] args) throws Exception {
        System.out.println("[CONSOLE] Starting...");

        var config    = Event.loadConfig("plugin.json");
        var sessionId = UUID.randomUUID().toString(); // isolates this user's LLM conversation history

        Event.connectAndRun(SOCKET, config, (in, out) -> {
            System.out.println("[CONSOLE] Session: " + sessionId);
            System.out.println("[CONSOLE] Type 'exit' to quit. Prefix with '!' to invoke a tool (e.g. !WeatherTool London).");

            // Separate thread to receive events from kernel without blocking input
            var receiver = Executors.newVirtualThreadPerTaskExecutor();
            receiver.submit(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        Event ev = Event.MAPPER.readValue(line, Event.class);
                        switch (ev.type()) {
                            case "chat.response" -> {
                                System.out.println("\n[AI] " + ev.payload());
                                System.out.print("You: ");
                            }
                            case "tool.result" -> {
                                System.out.println("\n[TOOL] " + ev.payload());
                                System.out.print("You: ");
                            }
                            case "chat.prompt" -> {
                                if (!config.id().equals(ev.source())) {
                                    System.out.println("\n[OTHER USER] " + ev.payload());
                                    System.out.print("You: ");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[CONSOLE] Error reading from kernel: " + e.getMessage());
                }
            });

            try (var scanner = new Scanner(System.in)) {
                while (true) {
                    System.out.print("You: ");
                    String input = scanner.nextLine().trim();
                    if (input.isEmpty()) continue;
                    if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) break;

                    Event event;
                    if (input.startsWith("!")) {
                        // Tool invocation: !ToolName optional input
                        String[] parts = input.substring(1).split(" ", 2);
                        String toolName  = parts[0];
                        String toolInput = parts.length > 1 ? parts[1] : "";
                        String payload   = Event.MAPPER.writeValueAsString(
                                Map.of("tool", toolName, "input", toolInput));
                        event = Event.withSession("tool.invoke", payload, config.id(), sessionId);
                    } else {
                        event = Event.withSession("chat.prompt", input, config.id(), sessionId);
                    }

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
