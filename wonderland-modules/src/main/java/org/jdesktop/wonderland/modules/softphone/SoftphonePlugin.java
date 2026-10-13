/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d (softphone): the module's WonderlandPlugin provider
 * plus the engine-backend seam, and the stun/winp/JMF-audio anchor.
 *
 * The 2006 softphone was three dead technologies braided together: the
 * bundled binary spoke SIP through stun.jar for NAT traversal (ice4j
 * replaces it), ran under winp process supervision (java.lang.ProcessHandle
 * replaces it), and carried voice through JMF RTP (Java Sound + the in-tree
 * G.711/RtpPacket/JitterBuffer stack replaces it). None of the three lives
 * in a headless module, so — exactly as M7b's rasteriser slot — the module
 * owns only the STATE (SoftphoneControl) and reaches the mechanism through
 * this static factory. The lg3d-apps ExternalSoftphoneBackend is installed
 * from the desktop boot; a headless JVM sees none and the control runs on
 * the shipped NoopSoftphoneBackend, where start() honestly fails, so every
 * control decision is CI-provable against a recording stub.
 */
package org.jdesktop.wonderland.modules.softphone;

import org.jdesktop.wonderland.common.modules.ModuleInfo;
import org.jdesktop.wonderland.modules.api.PluginContext;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;

/** The softphone (VoIP) feature module. */
public final class SoftphonePlugin implements WonderlandPlugin {

    /** The module name this plugin is registered under. */
    public static final String MODULE_NAME = "softphone";

    /**
     * Builds the engine backend for one control. The live implementation
     * spawns an external softphone through ProcessBuilder/ProcessHandle and
     * bridges voice with Java Sound; installed from the desktop JVM only.
     */
    @FunctionalInterface
    public interface BackendFactory {
        SoftphoneBackend create(SoftphoneBackend.EventHandler handler);
    }

    private static volatile BackendFactory factory;

    /**
     * Install the live backend factory. Idempotent by last-writer-wins,
     * like the M6b / M7a / M7b installer slots.
     *
     * @param backendFactory the factory, or null to uninstall (test isolation)
     */
    public static void installBackendFactory(BackendFactory backendFactory) {
        factory = backendFactory;
    }

    /** The currently-installed factory, or null in a headless JVM. */
    public static BackendFactory backendFactory() {
        return factory;
    }

    private final ModuleInfo info =
            new ModuleInfo(MODULE_NAME, 1, 0, 0,
                    "VoIP softphone (ice4j + Java Sound + G.711)");

    private SoftphoneControl control;

    @Override
    public ModuleInfo getModuleInfo() {
        return info;
    }

    @Override
    public void initialize(PluginContext context) {
        if (control != null) {
            return; // idempotent, like every M4 module
        }
        // a live backend is BUILT before the control that owns it, but it
        // must deliver events TO that control; the indirection resolves the
        // handler at send time, so the factory never needs a half-built peer
        SoftphoneBackend backend = NoopSoftphoneBackend.INSTANCE;
        if (factory != null) {
            SoftphoneBackend built = factory.create(new LazyHandler());
            if (built != null) {
                backend = built;
            }
        }
        control = new SoftphoneControl(backend);
    }

    @Override
    public void dispose() {
        if (control != null) {
            control.stopSoftphone();
        }
        control = null;
    }

    /** The live control, or {@code null} before initialize()/after dispose(). */
    public SoftphoneControl getControl() {
        return control;
    }

    /** Forwards to the control's handler once the control exists; drops
     *  (rather than crashes) the rare event a backend fires during its own
     *  construction, before the control field is assigned. */
    private final class LazyHandler implements SoftphoneBackend.EventHandler {
        @Override
        public void onEvent(SoftphoneEvent event) {
            SoftphoneControl live = control;
            if (live != null) {
                live.handleEvent(event);
            }
        }
    }
}
