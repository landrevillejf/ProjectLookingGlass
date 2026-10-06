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

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tames a known upstream JavaFX defect so the browser degrades gracefully
 * instead of shouting on every affected page.
 *
 * <p><b>The defect (OpenJDK JDK-8346250).</b> On Linux the WebKit native library
 * shipped with JavaFX 21 ({@code libjfxwebkit.so}) omits the JNI implementations
 * of {@code com.sun.webkit.network.SocketStreamHandle.twkDidOpen}/{@code twkDidClose}.
 * Any page that opens a <em>WebSocket</em> therefore raises
 * {@link UnsatisfiedLinkError} on the JavaFX Application Thread, from deep inside
 * JavaFX's own networking callback &mdash; far from any {@code try/catch} this app
 * controls. The error is benign (the socket simply never opens; the rest of the
 * page renders and stays interactive), but it escapes to the thread's
 * uncaught-exception handler and prints a full stack trace on every socket event,
 * which reads like a crash.</p>
 *
 * <p><b>Why not just fix JavaFX.</b> WebSockets cannot work without a JavaFX
 * build that ships those natives. The releases that add them (JavaFX 24 and
 * later) require JDK 22+, and this desktop is pinned to the JDK 21 toolchain, so
 * the fix is out of reach without a much larger toolchain migration. The
 * JDK-21-compatible line (JavaFX 21.0.x / 23.x) still lacks the natives.</p>
 *
 * <p><b>What this guard does.</b> It installs a scoped
 * {@link Thread.UncaughtExceptionHandler} on the JavaFX Application Thread that
 * recognises exactly this one error &mdash; an {@link UnsatisfiedLinkError} whose
 * trace passes through {@code com.sun.webkit} &mdash; logs a single concise
 * warning the first time, and swallows it so the toolkit stays quiet and stable.
 * <em>Every</em> other throwable is handed straight to the previously installed
 * handler, so a genuine defect is never masked.</p>
 */
final class WebKitThreadGuard {

    private static final Logger LOG = LoggerFactory.getLogger(WebKitThreadGuard.class);

    /** Stack-frame prefix that identifies the missing-native WebKit code path. */
    private static final String WEBKIT_PACKAGE = "com.sun.webkit.";

    private WebKitThreadGuard() {
        // no instances
    }

    /**
     * Installs the guard on the calling thread, chaining to whatever handler was
     * already there. Intended to run once on the JavaFX Application Thread before
     * the first page loads. Idempotent: a second call on an already-guarded thread
     * is a no-op, so it is safe to call from every {@code BrowserPanel} boot.
     */
    static void installOnCurrentThread() {
        Thread thread = Thread.currentThread();
        Thread.UncaughtExceptionHandler previous = thread.getUncaughtExceptionHandler();
        if (previous instanceof Handler) {
            return;
        }
        thread.setUncaughtExceptionHandler(new Handler(previous));
    }

    /**
     * Builds a guard that chains to {@code delegate} (may be null). Package-private
     * for tests; production code uses {@link #installOnCurrentThread()}.
     *
     * @param delegate the handler to fall back to for non-WebKit throwables
     * @return the guarding handler
     */
    static Thread.UncaughtExceptionHandler newHandler(Thread.UncaughtExceptionHandler delegate) {
        return new Handler(delegate);
    }

    /**
     * True when {@code t} is the JDK-8346250 {@link UnsatisfiedLinkError} raised
     * by the missing Linux WebKit socket natives. Recognised by type <em>plus</em>
     * a {@code com.sun.webkit} frame in the trace, so an unrelated link error (or
     * any other throwable) is never mistaken for it.
     *
     * @param t the throwable to classify (null is not a match)
     * @return true only for the known WebKit native-link error
     */
    static boolean isKnownWebKitLinkError(Throwable t) {
        if (!(t instanceof UnsatisfiedLinkError)) {
            return false;
        }
        for (StackTraceElement frame : t.getStackTrace()) {
            if (frame.getClassName().startsWith(WEBKIT_PACKAGE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The handler: swallow the known WebKit native-link error (warning once), and
     * delegate everything else untouched.
     */
    private static final class Handler implements Thread.UncaughtExceptionHandler {

        private final Thread.UncaughtExceptionHandler delegate;
        private final AtomicBoolean warned = new AtomicBoolean();

        Handler(Thread.UncaughtExceptionHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable t) {
            if (isKnownWebKitLinkError(t)) {
                if (warned.compareAndSet(false, true)) {
                    LOG.warn("WebView WebSockets are unavailable on this platform: the "
                            + "JavaFX Linux WebKit native library is missing the "
                            + "com.sun.webkit SocketStreamHandle methods (JDK-8346250). "
                            + "Pages still render and stay interactive; only WebSocket "
                            + "connections are affected. Further occurrences are "
                            + "suppressed.");
                }
                return; // benign, upstream, and out of our control: do not propagate
            }
            if (delegate != null) {
                delegate.uncaughtException(thread, t);
            }
        }
    }
}
