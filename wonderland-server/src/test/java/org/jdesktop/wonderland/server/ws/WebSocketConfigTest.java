/*
 * wonderland-server M3 unit test: the WebSocket endpoint wiring. The 2006 stack
 * hung the protocol off GlassFish/MINA socket plumbing; the rebuilt surface is
 * one handler at /comms (the address NetworkAddress deferred to M3 resolves
 * against this path in M3b). Driven through the real WebSocketHandlerRegistry
 * contract so the assertion covers the handler instance, the /comms path AND the
 * allowed-origins policy -- the three things that route a socket to the handler.
 * (Spring builds the url->handler mapping lazily inside its DispatcherServlet
 * lifecycle, so a bare registry unit test reaches the registration, not the
 * post-init map; the full routing is exercised by the @SpringBootTest context.)
 */
package org.jdesktop.wonderland.server.ws;

import org.jdesktop.wonderland.server.comms.ProtocolRouter;
import org.jdesktop.wonderland.server.comms.SessionRegistry;
import org.jdesktop.wonderland.server.world.WorldModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketConfigTest {

    @Test
    void registersTheCommsHandlerAtTheCommsPath() {
        WorldModel world = new WorldModel();
        SessionRegistry registry = new SessionRegistry();
        ProtocolRouter router = new ProtocolRouter(world, registry);
        WebSocketConfig config = new WebSocketConfig(router, registry);

        WebSocketHandlerRegistry handlers = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
        when(handlers.addHandler(any(WebSocketHandler.class), any())).thenReturn(registration);
        when(registration.setAllowedOrigins(any())).thenReturn(registration);

        config.registerWebSocketHandlers(handlers);

        ArgumentCaptor<WebSocketHandler> handler = ArgumentCaptor.forClass(WebSocketHandler.class);
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(handlers).addHandler(handler.capture(), path.capture());

        assertThat(path.getAllValues()).containsExactly("/comms");
        assertThat(handler.getValue()).isInstanceOf(CommsWebSocketHandler.class);
        // the endpoint is open to any origin (the 2006 client is not a browser
        // same-origin context), pinned through the fluent registration call
        verify(registration).setAllowedOrigins("*");
    }

    @Test
    void theHandlerBeanIsTheOneTheRouterAndRegistryBuild() {
        WorldModel world = new WorldModel();
        SessionRegistry registry = new SessionRegistry();
        ProtocolRouter router = new ProtocolRouter(world, registry);
        WebSocketConfig config = new WebSocketConfig(router, registry);

        assertThat(config.commsWebSocketHandler())
                .isInstanceOf(CommsWebSocketHandler.class);
    }
}
