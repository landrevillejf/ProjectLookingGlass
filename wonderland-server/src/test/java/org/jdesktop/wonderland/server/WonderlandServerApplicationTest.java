/*
 * wonderland-server M3 context test: the Spring Boot wiring itself. @SpringBootTest
 * with the MOCK web environment (no port bound -- M3a's headless rule) proves the
 * one lifecycle that replaces the retired GlassFish deployment descriptor actually
 * assembles: the world/registry/router singletons resolve, the REST controller and
 * the WebSocket config are live beans, and the /comms handler bean is the class the
 * sockets will hang off in M3b.
 */
package org.jdesktop.wonderland.server;

import org.jdesktop.wonderland.server.comms.ProtocolRouter;
import org.jdesktop.wonderland.server.comms.SessionRegistry;
import org.jdesktop.wonderland.server.web.WorldRestController;
import org.jdesktop.wonderland.server.world.WorldModel;
import org.jdesktop.wonderland.server.ws.CommsWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class WonderlandServerApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void theProtocolBeansAreSingletonsOnTheContext() {
        assertThat(context.getBean(WorldModel.class))
                .isSameAs(context.getBean(WorldModel.class));
        assertThat(context.getBean(SessionRegistry.class).count()).isZero();

        ProtocolRouter router = context.getBean(ProtocolRouter.class);
        assertThat(router).isSameAs(context.getBean(ProtocolRouter.class));
        // the fresh world carries exactly the environment root
        assertThat(context.getBean(WorldModel.class).hierarchy()).hasSize(1);
    }

    @Test
    void theTransportAndRestSurfacesAreWired() {
        assertThat(context.getBean(WorldRestController.class)).isNotNull();
        assertThat(context.getBean(CommsWebSocketHandler.class)).isNotNull();
    }
}
