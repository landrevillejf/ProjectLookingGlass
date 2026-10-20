/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server: the executable entry point, exercised headless.
 *
 * The @SpringBootTest MOCK context proves the beans assemble; this proves the
 * one line a released jar actually runs -- main() -- boots a real context and
 * hands it back so it can be closed. It runs the boot with web-application-type
 * =none so no port is bound (the headless rule every other test here follows),
 * and closes the context in a finally so the @Scheduled autosave beat's
 * non-daemon thread never outlives the test JVM.
 */
package org.jdesktop.wonderland.server;

import org.jdesktop.wonderland.server.world.WorldModel;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

class WonderlandServerApplicationMainTest {

    @Test
    void mainBootsAContextThatCarriesTheWorldAndCanBeClosed() {
        WonderlandServerApplication.main(new String[] {
                "--spring.main.web-application-type=none",
                "--spring.main.banner-mode=off"});
        ConfigurableApplicationContext context = WonderlandServerApplication.booted;
        try {
            assertThat(context).isNotNull();
            assertThat(context.isActive()).isTrue();
            // the same singletons the MOCK context proves, now via the real boot
            assertThat(context.getBean(WorldModel.class)).isNotNull();
            assertThat(context.getBean(WorldModel.class).hierarchy()).hasSize(1);
        } finally {
            context.close(); // stops the scheduler thread main() started
        }
    }
}
