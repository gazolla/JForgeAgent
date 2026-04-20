///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2
//SOURCES ../../shared/Event.java
//DESCRIPTION MK3 Console Test Plugin (UDS)

import java.util.List;

public class TestConsolePlugin {

    private static final String SOCKET = "../../kernel/kernel.sock";

    public static void main(String[] args) throws Exception {
        System.out.println("[TEST CONSOLE] Starting...");

        // Inline config — test plugin does not need its own file
        var config = new Event.PluginConfig("test-plugin", List.of(), List.of("chat.prompt"));

        Event.connectAndRun(SOCKET, config, (in, out) -> {
            String json = Event.MAPPER.writeValueAsString(Event.of("chat.prompt", "/directory", config.id()));
            out.write(json);
            out.newLine();
            out.flush();
            System.out.println("[TEST CONSOLE] Message sent: " + json);
            Thread.sleep(1000);
        });
    }
}
