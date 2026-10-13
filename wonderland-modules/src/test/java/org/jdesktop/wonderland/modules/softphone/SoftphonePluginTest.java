/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d: the plugin's ServiceLoader registration, the
 * lifecycle, and the two backend paths — no factory (headless) lands on the
 * honest Noop, an installed factory's backend actually drives the control.
 * Mirrors PdfViewerPluginTest so the whole M7 seam family reads alike.
 */
package org.jdesktop.wonderland.modules.softphone;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ServiceLoader;
import org.jdesktop.wonderland.modules.api.PluginContext;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SoftphonePluginTest {

    /** The loader only hands a context to initialize(); none is needed here. */
    private static PluginContext context() {
        return null;
    }

    @AfterEach
    void uninstallFactory() {
        SoftphonePlugin.installBackendFactory(null);
    }

    @Test
    @DisplayName("the loader discovers the softphone module by name")
    void discoveredByServiceLoader() {
        boolean found = false;
        for (WonderlandPlugin plugin : ServiceLoader.load(WonderlandPlugin.class)) {
            if (SoftphonePlugin.MODULE_NAME.equals(plugin.getModuleInfo().getName())) {
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("the module descriptor carries the replacement's identity")
    void moduleInfo() {
        SoftphonePlugin plugin = new SoftphonePlugin();
        assertThat(plugin.getModuleInfo().getName()).isEqualTo("softphone");
        assertThat(plugin.getModuleInfo().getDescription())
                .contains("ice4j").contains("Java Sound").contains("G.711");
    }

    @Test
    @DisplayName("headless: initialize lands on the Noop, start fails honestly")
    void headlessUsesNoopBackend() {
        SoftphonePlugin plugin = new SoftphonePlugin();
        plugin.initialize(context());
        plugin.initialize(context());                    // idempotent
        SoftphoneControl control = plugin.getControl();
        assertThat(control).isNotNull();
        assertThat(control.isRunning()).isFalse();
        assertThat(control.startSoftphone("h", "u", 5060, "1")).isNull();
        plugin.dispose();
        assertThat(plugin.getControl()).isNull();
    }

    @Test
    @DisplayName("an installed factory's backend drives the live control")
    void installedFactoryBacksTheControl() {
        RecordingBackend probe = new RecordingBackend();
        SoftphonePlugin.installBackendFactory(handler -> {
            probe.handler = handler;
            return probe;
        });
        SoftphonePlugin plugin = new SoftphonePlugin();
        plugin.initialize(context());
        SoftphoneControl control = plugin.getControl();
        String callId = control.startSoftphone("h", "u", 5060, "7");
        assertThat(callId).isNotNull();
        assertThat(control.isRunning()).isTrue();
        // an event the backend fires reaches the control through the handler
        probe.handler.onEvent(new SoftphoneEvent.Connected(true));
        assertThat(control.isConnected()).isTrue();
        plugin.dispose();
        assertThat(control.isRunning()).isFalse();
        assertThat(plugin.getControl()).isNull();
    }

    @Test
    @DisplayName("the factory slot reads back what is installed")
    void factorySlotIsReadable() {
        assertThat(SoftphonePlugin.backendFactory()).isNull();   // headless
        SoftphonePlugin.BackendFactory f = handler -> new RecordingBackend();
        SoftphonePlugin.installBackendFactory(f);
        assertThat(SoftphonePlugin.backendFactory()).isSameAs(f);
        SoftphonePlugin.installBackendFactory(null);             // uninstalled
        assertThat(SoftphonePlugin.backendFactory()).isNull();
    }

    @Test
    @DisplayName("a factory that hands back null degrades to the Noop")
    void nullBackendFromFactoryDegradesToNoop() {
        SoftphonePlugin.installBackendFactory(handler -> null);
        SoftphonePlugin plugin = new SoftphonePlugin();
        plugin.initialize(context());
        assertThat(plugin.getControl().startSoftphone("h", "u", 5060, "1")).isNull();
        plugin.dispose();
    }

    @Test
    @DisplayName("dispose without initialize is a no-op")
    void disposeBeforeInitialize() {
        SoftphonePlugin plugin = new SoftphonePlugin();
        plugin.dispose();                                       // never thrown
        assertThat(plugin.getControl()).isNull();
    }

    @Test
    @DisplayName("an event fired during construction is dropped, not crashed")
    void earlyEventIsDroppedBeforeControlExists() {
        // the factory runs BEFORE the control field is assigned; a backend
        // that shouts during its own construction must not NPE the module
        SoftphonePlugin.installBackendFactory(handler -> {
            handler.onEvent(new SoftphoneEvent.Connected(true));
            return null;
        });
        SoftphonePlugin plugin = new SoftphonePlugin();
        plugin.initialize(context());
        assertThat(plugin.getControl()).isNotNull();
        assertThat(plugin.getControl().isConnected()).isFalse();
        plugin.dispose();
    }
}
