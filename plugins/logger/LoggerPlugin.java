///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../shared/Event.java
//DESCRIPTION MK3 Logger Plugin (UDS)

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LoggerPlugin {

    private static final String SOCKET   = "../../kernel/kernel.sock";
    private static final Path   LOG_FILE = Path.of("../../logs/system.log");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static void main(String[] args) throws Exception {
        System.out.println("[LOGGER] Starting...");

        var config = Event.loadConfig("plugin.json");

        Event.connectAndRun(SOCKET, config, (in, out) -> {
            String line;
            while ((line = in.readLine()) != null) {
                log(line);
            }
        });
    }

    private static void log(String eventJson) {
        try {
            String entry = "[%s] %s%n".formatted(LocalDateTime.now().format(FMT), eventJson);
            Files.writeString(LOG_FILE, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("[LOGGER] " + eventJson);
        } catch (Exception e) {
            System.err.println("[LOGGER] Error writing to log file: " + e.getMessage());
        }
    }
}
