///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../shared/Event.java
//DESCRIPTION MK3 Microkernel — LLM Chat System (UDS)

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TransferQueue;

/**
 * Fully plugin-agnostic kernel.
 *
 * Does not know any event types or specific plugins.
 * Routing is built 100% from plugin.register events received at runtime —
 * works for both boot-time plugins and plugins added dynamically.
 */
public class Kernel {

    private static final Path SOCKET_PATH = Path.of("kernel.sock");
    private static final TransferQueue<String> eventQueue = new LinkedTransferQueue<>();
    private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    // Dynamic routing: event type → writers subscribed to that type
    private static final Map<String, CopyOnWriteArrayList<BufferedWriter>> subscriptions = new ConcurrentHashMap<>();

    // Reverse index for efficient cleanup on disconnect: writer → subscribed types
    private static final Map<BufferedWriter, List<String>> writerIndex = new ConcurrentHashMap<>();

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[KERNEL] Starting...");

        try {
            Files.deleteIfExists(SOCKET_PATH);
        } catch (IOException e) {
            System.err.println("[KERNEL] Error deleting old socket: " + e.getMessage());
        }

        executor.submit(Kernel::startSocketServer);
        executor.submit(Kernel::startEventProcessor);

        System.out.println("[KERNEL] Listening for connections at: " + SOCKET_PATH.toAbsolutePath());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[KERNEL] Shutting down...");
            try { Files.deleteIfExists(SOCKET_PATH); } catch (IOException ignored) {}
            executor.shutdownNow();
        }));

        Thread.currentThread().join();
    }

    // -------------------------------------------------------------------------
    // Socket server
    // -------------------------------------------------------------------------

    private static void startSocketServer() {
        var address = UnixDomainSocketAddress.of(SOCKET_PATH);
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(address);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    SocketChannel client = server.accept();
                    System.out.println("[KERNEL] Plugin connected.");
                    executor.submit(() -> handleClient(client));
                } catch (IOException e) {
                    if (!Thread.currentThread().isInterrupted())
                        System.err.println("[KERNEL] Error accepting connection: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[KERNEL] Fatal socket error: " + e.getMessage());
        }
    }

    private static void handleClient(SocketChannel channel) {
        BufferedWriter writer = null;
        try {
            var in = new BufferedReader(new InputStreamReader(Channels.newInputStream(channel)));
            writer = new BufferedWriter(new OutputStreamWriter(Channels.newOutputStream(channel)));

            String line;
            while ((line = in.readLine()) != null) {
                System.out.println("[KERNEL] Received: " + line);
                Event event = Event.MAPPER.readValue(line, Event.class);

                if ("plugin.register".equals(event.type())) {
                    // Handled inline: we need to associate the writer with this connection
                    register(event, writer);
                } else {
                    eventQueue.put(line);
                }
            }
        } catch (IOException e) {
            System.err.println("[KERNEL] Error communicating with plugin: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            unregister(writer);
            try { channel.close(); } catch (IOException ignored) {}
            System.out.println("[KERNEL] Plugin disconnected.");
        }
    }

    // -------------------------------------------------------------------------
    // Dynamic registration and deregistration
    // -------------------------------------------------------------------------

    private static void register(Event event, BufferedWriter writer) {
        try {
            Event.PluginConfig config = Event.MAPPER.readValue(event.payload(), Event.PluginConfig.class);

            config.subscribes().forEach(type ->
                subscriptions.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(writer)
            );
            writerIndex.put(writer, config.subscribes());

            System.out.println("[KERNEL] Plugin '" + config.id() + "' registered."
                    + " Subscribes: " + config.subscribes()
                    + " | Publishes: " + config.publishes());
        } catch (Exception e) {
            System.err.println("[KERNEL] Failed to register plugin: " + e.getMessage());
        }
    }

    private static void unregister(BufferedWriter writer) {
        if (writer == null) return;
        var types = writerIndex.remove(writer);
        if (types != null) {
            types.forEach(type -> {
                var list = subscriptions.get(type);
                if (list != null) list.remove(writer);
            });
        }
    }

    // -------------------------------------------------------------------------
    // Event processing and routing
    // -------------------------------------------------------------------------

    private static void startEventProcessor() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String eventJson = eventQueue.take();
                System.out.println("[KERNEL] Processing: " + eventJson);

                Event event = Event.MAPPER.readValue(eventJson, Event.class);
                var targets = subscriptions.get(event.type());

                if (targets == null || targets.isEmpty()) {
                    System.out.println("[KERNEL] No subscribers for type: " + event.type());
                } else {
                    // Parallel dispatch: each subscriber receives in its own virtual thread.
                    // Queue consumption remains sequential (order guaranteed);
                    // only delivery to subscribers is parallelized.
                    targets.forEach(w -> executor.submit(() -> sendTo(w, eventJson)));
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[KERNEL] Error processing event: " + e.getMessage());
            }
        }
    }

    private static void sendTo(BufferedWriter writer, String message) {
        // synchronized per writer: ensures two events delivered in parallel
        // to the same subscriber do not corrupt the stream
        synchronized (writer) {
            try {
                writer.write(message);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                System.err.println("[KERNEL] Error sending to subscriber: " + e.getMessage());
            }
        }
    }
}
