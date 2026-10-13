/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c: headless tests for the plugin entry point.
 *
 * The plugin is pure: its initialize()/dispose() bookkeeping is a lifecycle
 * flag, and its ServiceLoader registration is a resource on the classpath. Both
 * are asserted here. The live WonderlandDesktop construction the boot triggers
 * needs a running lg3d session and is left to the desktop probe.
 */
package org.jdesktop.wonderland.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ServiceLoader;
import org.jdesktop.wonderland.modules.api.PluginContext;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;
import org.junit.jupiter.api.Test;

/**
 * Headless assertions on {@link WonderlandDesktopPlugin}: ServiceLoader
 * discovery and idempotent lifecycle transitions.
 */
class WonderlandDesktopPluginTest {

    private static PluginContext emptyContext() {
        return new PluginContext(java.util.Collections.emptyMap());
    }

    @Test
    void discoveredByServiceLoader() {
        boolean found = false;
        for (WonderlandPlugin plugin : ServiceLoader.load(WonderlandPlugin.class)) {
            if (plugin instanceof WonderlandDesktopPlugin) {
                found = true;
                break;
            }
        }
        assertThat(found).as("wonderland-desktop plugin registered via META-INF/services").isTrue();
    }

    @Test
    void moduleInfoIsStable() {
        WonderlandDesktopPlugin plugin = new WonderlandDesktopPlugin();
        assertThat(plugin.getModuleInfo()).isNotNull();
        assertThat(plugin.getModuleInfo().getName()).isEqualTo("wonderland-desktop");
        // getModuleInfo() returns the same immutable descriptor every call.
        assertThat(plugin.getModuleInfo()).isSameAs(plugin.getModuleInfo());
    }

    @Test
    void initializeIsIdempotent() {
        WonderlandDesktopPlugin plugin = new WonderlandDesktopPlugin();
        assertThat(plugin.isInitialized()).isFalse();

        plugin.initialize(emptyContext());
        assertThat(plugin.isInitialized()).isTrue();

        // A second initialize must not throw and must leave the flag set.
        plugin.initialize(emptyContext());
        assertThat(plugin.isInitialized()).isTrue();
    }

    @Test
    void disposeResetsLifecycle() {
        WonderlandDesktopPlugin plugin = new WonderlandDesktopPlugin();
        plugin.initialize(emptyContext());
        plugin.dispose();
        assertThat(plugin.isInitialized()).isFalse();
    }

    @Test
    void disposeBeforeInitializeIsNoOp() {
        WonderlandDesktopPlugin plugin = new WonderlandDesktopPlugin();
        plugin.dispose();
        assertThat(plugin.isInitialized()).isFalse();
    }
}
