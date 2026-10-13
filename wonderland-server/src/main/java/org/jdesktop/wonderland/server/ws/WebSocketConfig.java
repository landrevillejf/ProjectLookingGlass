/*
 * wonderland-server (M3): wires the CommsWebSocketHandler into Spring's WebSocket
 * infrastructure at /comms. This is the endpoint that, in M3b, a browser- or
 * client-launched socket connects to; M3a registers it and unit-tests the handler
 * directly, so the socket path is compiled and wired but not yet bound to a port.
 */
package org.jdesktop.wonderland.server.ws;

import org.jdesktop.wonderland.server.comms.ProtocolRouter;
import org.jdesktop.wonderland.server.comms.SessionRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ProtocolRouter router;
    private final SessionRegistry registry;

    public WebSocketConfig(ProtocolRouter router, SessionRegistry registry) {
        this.router = router;
        this.registry = registry;
    }

    @Bean
    public CommsWebSocketHandler commsWebSocketHandler() {
        return new CommsWebSocketHandler(router, registry);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(commsWebSocketHandler(), "/comms").setAllowedOrigins("*");
    }
}
