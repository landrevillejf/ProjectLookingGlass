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
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WonderlandServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WonderlandServerApplication.class, args);
    }
}
