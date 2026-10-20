/*
 * wonderland-server (M3): the Spring Boot entry point. The class itself is the
 * replacement for the retired GlassFish Embedded deployment descriptor -- the one
 * lifecycle that owns embedded Tomcat, the WebSocket endpoint and the REST
 * controllers. M3a never boots it over a socket: the loopback integration test
 * and the MockMvc/direct-handler unit tests drive the real beans (SessionRegistry,
 * ProtocolRouter, WorldModel) inside a plain Spring context, so the whole server
 * is exercised headless. When M3b puts it behind real sockets, this is the class
 * the boot repackage makes executable.
 */
package org.jdesktop.wonderland.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WonderlandServerApplication {

    /**
     * The context the last {@link #main} booted, held so a headless test can
     * close it: main() returns void, and an unclosed context leaves the
     * {@code @Scheduled} autosave beat running on a non-daemon thread that
     * would otherwise outlive the test JVM. Production never reads this -- the
     * container owns the context's lifetime through its own shutdown hook.
     */
    static volatile ConfigurableApplicationContext booted;

    public static void main(String[] args) {
        booted = SpringApplication.run(WonderlandServerApplication.class, args);
    }
}
