/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.webbrowser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link WebKitThreadGuard}: the JDK-8346250 classification
 * and the swallow-vs-delegate behaviour of the installed handler. None of this
 * needs a display, a JavaFX toolkit or a network.
 */
class WebKitThreadGuardTest {

    /** An {@link UnsatisfiedLinkError} shaped like the real JDK-8346250 one. */
    private static UnsatisfiedLinkError webKitLinkError() {
        UnsatisfiedLinkError e = new UnsatisfiedLinkError(
                "'void com.sun.webkit.network.SocketStreamHandle.twkDidOpen(long)'");
        e.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("com.sun.webkit.network.SocketStreamHandle",
                    "twkDidOpen", "SocketStreamHandle.java", 361),
            new StackTraceElement("com.sun.glass.ui.InvokeLaterDispatcher$Future",
                    "run", "InvokeLaterDispatcher.java", 95),
        });
        return e;
    }

    /** An {@link UnsatisfiedLinkError} from somewhere unrelated to WebKit. */
    private static UnsatisfiedLinkError otherLinkError() {
        UnsatisfiedLinkError e = new UnsatisfiedLinkError("no jogamp_native in java.library.path");
        e.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("jogamp.common.jvm.JVMUtil", "initialize",
                    "JVMUtil.java", 100),
        });
        return e;
    }

    @Test
    @DisplayName("the known WebKit socket link error is recognised")
    void recognisesKnownError() {
        assertTrue(WebKitThreadGuard.isKnownWebKitLinkError(webKitLinkError()));
    }

    @Test
    @DisplayName("unrelated throwables are never mistaken for the known error")
    void rejectsUnrelated() {
        assertFalse(WebKitThreadGuard.isKnownWebKitLinkError(null), "null is not a match");
        assertFalse(WebKitThreadGuard.isKnownWebKitLinkError(otherLinkError()),
                "a non-WebKit link error is not the known bug");
        assertFalse(WebKitThreadGuard.isKnownWebKitLinkError(new RuntimeException("boom")),
                "a plain exception is not the known bug");

        NoClassDefFoundError linkage = new NoClassDefFoundError("com/sun/webkit/Foo");
        linkage.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("com.sun.webkit.Foo", "bar", "Foo.java", 1),
        });
        assertFalse(WebKitThreadGuard.isKnownWebKitLinkError(linkage),
                "only UnsatisfiedLinkError qualifies, not any WebKit LinkageError");
    }

    @Test
    @DisplayName("the handler swallows the known error and delegates everything else")
    void swallowVsDelegate() {
        List<Throwable> delegated = new ArrayList<>();
        Thread.UncaughtExceptionHandler handler =
                WebKitThreadGuard.newHandler((thread, t) -> delegated.add(t));
        Thread current = Thread.currentThread();

        handler.uncaughtException(current, webKitLinkError());
        handler.uncaughtException(current, webKitLinkError());
        assertTrue(delegated.isEmpty(), "the known WebKit error is swallowed, never delegated");

        RuntimeException boom = new RuntimeException("real defect");
        handler.uncaughtException(current, boom);
        assertEquals(1, delegated.size(), "an unrelated throwable is passed straight through");
        assertSame(boom, delegated.get(0));

        UnsatisfiedLinkError other = otherLinkError();
        handler.uncaughtException(current, other);
        assertEquals(2, delegated.size(), "a non-WebKit link error is still delegated");
        assertSame(other, delegated.get(1));
    }

    @Test
    @DisplayName("a null delegate is tolerated for non-WebKit throwables")
    void nullDelegateIsSafe() {
        Thread.UncaughtExceptionHandler handler = WebKitThreadGuard.newHandler(null);
        Thread current = Thread.currentThread();
        // Neither call may throw: the known error is swallowed, the other has no
        // delegate and is simply dropped.
        handler.uncaughtException(current, webKitLinkError());
        handler.uncaughtException(current, new IllegalStateException("no delegate"));
    }

    @Test
    @DisplayName("installOnCurrentThread is idempotent and installs a swallowing guard")
    void installIsIdempotent() {
        Thread current = Thread.currentThread();
        Thread.UncaughtExceptionHandler original = current.getUncaughtExceptionHandler();
        try {
            WebKitThreadGuard.installOnCurrentThread();
            Thread.UncaughtExceptionHandler first = current.getUncaughtExceptionHandler();
            WebKitThreadGuard.installOnCurrentThread();
            assertSame(first, current.getUncaughtExceptionHandler(),
                    "a second install on a guarded thread is a no-op");
            // The installed guard swallows the known error without propagating.
            first.uncaughtException(current, webKitLinkError());
        } finally {
            current.setUncaughtExceptionHandler(original);
        }
    }
}
