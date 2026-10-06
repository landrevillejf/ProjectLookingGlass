/**
 * Project Looking Glass
 *
 * Copyright (c) 2026 Project Looking Glass contributors.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JComponent;
import javax.swing.JPanel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link X11CompositorSession} — the discovery holder that
 * lets the 2D desktop find a live compositor session in the same JVM. Exercised
 * through the package-visible {@code publish(CompositedWindowHost,
 * WindowLifecycleRegistrar)} seam with fakes, since a live {@link X11Compositor}
 * needs a real {@code gnu.x11.Display}. The holder is a static singleton, so
 * every test {@linkplain X11CompositorSession#clear() clears} it afterwards to
 * keep the cases independent.
 */
class X11CompositorSessionTest {

    /** A do-nothing host; only its identity matters here. */
    private static final class FakeHost implements CompositedWindowHost {
        @Override
        public HostedWindow open(int windowId, String title, int width, int height) {
            final JComponent component = new JPanel();
            return new HostedWindow() {
                @Override
                public JComponent getComponent() {
                    return component;
                }

                @Override
                public void resized(int width, int height) {
                    // no-op
                }

                @Override
                public void dispose() {
                    // no-op
                }
            };
        }
    }

    /** Records the last listener installed, so a test can assert registration. */
    private static final class FakeRegistrar implements WindowLifecycleRegistrar {
        WindowLifecycleListener last;
        int calls;

        @Override
        public void setWindowLifecycleListener(WindowLifecycleListener listener) {
            this.last = listener;
            this.calls++;
        }
    }

    @AfterEach
    void tearDown() {
        X11CompositorSession.clear();
    }

    @Test
    @DisplayName("nothing is published by default")
    void emptyByDefault() {
        X11CompositorSession.clear();
        assertNull(X11CompositorSession.current());
        assertFalse(X11CompositorSession.isLive());
    }

    @Test
    @DisplayName("publishing a host and registrar makes them discoverable")
    void publishExposesCollaborators() {
        FakeHost host = new FakeHost();
        FakeRegistrar registrar = new FakeRegistrar();

        X11CompositorSession.publish(host, registrar);

        assertTrue(X11CompositorSession.isLive());
        X11CompositorSession.Session session = X11CompositorSession.current();
        assertSame(host, session.host());
        assertSame(registrar, session.registrar());
    }

    @Test
    @DisplayName("a null host clears the session rather than half-publishing")
    void nullHostClears() {
        X11CompositorSession.publish(new FakeHost(), new FakeRegistrar());
        assertTrue(X11CompositorSession.isLive());

        X11CompositorSession.publish((CompositedWindowHost) null, new FakeRegistrar());

        assertNull(X11CompositorSession.current());
        assertFalse(X11CompositorSession.isLive());
    }

    @Test
    @DisplayName("a null registrar clears the session rather than half-publishing")
    void nullRegistrarClears() {
        X11CompositorSession.publish(new FakeHost(), new FakeRegistrar());
        assertTrue(X11CompositorSession.isLive());

        X11CompositorSession.publish(new FakeHost(), null);

        assertNull(X11CompositorSession.current());
        assertFalse(X11CompositorSession.isLive());
    }

    @Test
    @DisplayName("the public compositor overload treats null as no-session")
    void nullCompositorClears() {
        X11CompositorSession.publish(new FakeHost(), new FakeRegistrar());
        assertTrue(X11CompositorSession.isLive());

        // A failed bring-up hands a null compositor; the holder must not keep a
        // stale session alive.
        X11CompositorSession.publish((X11Compositor) null, new FakeRegistrar());

        assertFalse(X11CompositorSession.isLive());
    }

    @Test
    @DisplayName("clear() drops a published session")
    void clearDrops() {
        X11CompositorSession.publish(new FakeHost(), new FakeRegistrar());
        assertTrue(X11CompositorSession.isLive());

        X11CompositorSession.clear();

        assertNull(X11CompositorSession.current());
        assertFalse(X11CompositorSession.isLive());
    }

    @Test
    @DisplayName("republishing replaces the previous session")
    void republishReplaces() {
        FakeHost firstHost = new FakeHost();
        X11CompositorSession.publish(firstHost, new FakeRegistrar());

        FakeHost secondHost = new FakeHost();
        FakeRegistrar secondRegistrar = new FakeRegistrar();
        X11CompositorSession.publish(secondHost, secondRegistrar);

        assertSame(secondHost, X11CompositorSession.current().host());
        assertSame(secondRegistrar, X11CompositorSession.current().registrar());
    }
}
