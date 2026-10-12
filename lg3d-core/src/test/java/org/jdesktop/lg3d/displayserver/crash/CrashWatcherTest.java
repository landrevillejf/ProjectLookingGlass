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
package org.jdesktop.lg3d.displayserver.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for the uncaught-exception hook. This one runs on the thread that
 * is dying, often as the last code the desktop executes, so the two properties
 * under proof are that it always records and that it always gets out of the way
 * afterwards: the console, the reporter and the host's own handler each have to
 * be served whether or not the other two are working.
 */
class CrashWatcherTest {

    @TempDir
    Path root;

    /** What the watcher echoed, captured in memory instead of the real stderr. */
    private final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private final PrintStream err = new PrintStream(console, true, StandardCharsets.UTF_8);

    private final List<String> chained = new CopyOnWriteArrayList<>();

    private CrashStore store;
    private AtomicInteger delivered;
    private CrashReporter reporter;

    /** The handler the JVM had before any of this ran, restored afterwards. */
    private Thread.UncaughtExceptionHandler originalHandler;

    @BeforeEach
    void wire() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler();
        store = new CrashStore(root, CrashStore.DEFAULT_MAX_REPORTS,
                CrashStore.DEFAULT_MAX_BYTES);
        delivered = new AtomicInteger();
        reporter = new CrashReporter(store,
                new IssuePrefill.Target("acme/widgets", List.of("crash"), ""),
                (report, prefill) -> {
                    delivered.incrementAndGet();
                    return "delivered";
                },
                CrashReporter.DEFAULT_MAX_PER_SESSION);
    }

    @AfterEach
    void restoreJvm() {
        CrashWatcher.uninstall();
        reporter.shutdown();
        Thread.setDefaultUncaughtExceptionHandler(originalHandler);
        System.clearProperty(CrashReporter.ENABLED_PROPERTY);
        System.clearProperty("lg.crashreport.dir");
    }

    private static Thread named(String name) {
        // Never started: only the name matters, and isFatal is deliberately not
        // allowed to look at anything but the name.
        return new Thread(() -> {
        }, name);
    }

    /** A stream that refuses to be written to - a closed pipe, a reaped service. */
    private static PrintStream brokenConsole() {
        return new PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {
                throw new IllegalStateException("stream closed");
            }
        });
    }

    /** Polls the spool for an entry, the only honest way to await an async capture. */
    private CrashReport awaitReport(String message) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            for (CrashReport report : store.list()) {
                if (report.message().contains(message)) {
                    return report;
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

    @Test
    @DisplayName("fatal is decided by the thread's name, not by who is asking")
    void fatalIsTheMainThreadOrTheEdt() {
        assertTrue(CrashWatcher.isFatal(named("main")));
        assertTrue(CrashWatcher.isFatal(named("AWT-EventQueue-0")));
        assertTrue(CrashWatcher.isFatal(named("AWT-EventQueue-1")));
        assertFalse(CrashWatcher.isFatal(named("lg-loader-3")));
        assertFalse(CrashWatcher.isFatal(null), "an unnamed death is not a dead desktop");
    }

    @Test
    @DisplayName("the dying thread is not the test - every handler runs on it")
    void currentThreadIsNeverTheCriterion() throws InterruptedException {
        // An uncaught-exception handler runs on the thread that is dying, so
        // comparing against Thread.currentThread() would be true for every single
        // exception and would mark a broken thumbnail as a dead desktop.
        AtomicBoolean fatalFromInside = new AtomicBoolean(true);
        Thread worker = new Thread(
                () -> fatalFromInside.set(CrashWatcher.isFatal(Thread.currentThread())),
                "lg-worker-inside");
        worker.start();
        worker.join(10_000);
        assertFalse(fatalFromInside.get());
    }

    @Test
    @DisplayName("a dying main thread is recorded before handle() returns")
    void fatalDeathIsCapturedSynchronously() {
        IllegalStateException thrown = new IllegalStateException("display server died");
        CrashWatcher.handle(named("main"), thrown, reporter, null, err);
        // No polling here, and that is the assertion: the JVM may exit the moment
        // this method returns, so the report has to be on disk already.
        CrashReport report = store.list().get(0);
        assertEquals(CrashReport.Severity.BLOCKER, report.severity());
        assertEquals(CrashReport.Kind.CRASH, report.kind());
        assertEquals(CrashWatcher.SOURCE, report.reporter());
        assertTrue(report.message().contains("display server died"), report.message());
        assertEquals(1, delivered.get(), "the first sighting of a fatal crash is opened");
        String echoed = console.toString(StandardCharsets.UTF_8);
        assertTrue(echoed.contains("[lg3d] uncaught exception in thread \"main\""), echoed);
        assertTrue(echoed.contains("IllegalStateException"),
                "installing a handler stops the JDK printing the dump, so we must: "
                        + echoed);
    }

    @Test
    @DisplayName("a worker thread dies without blocking the thread that noticed")
    void workerDeathIsQueued() throws InterruptedException {
        CrashWatcher.handle(named("lg-loader-3"),
                new IllegalStateException("thumbnail failed"), reporter, null, err);
        // The echo already happened, the file has not: the capture is on another
        // thread, so the EDT is never the one doing disk I/O for a dying worker.
        CrashReport report = awaitReport("thumbnail failed");
        assertNotNull(report, "the queued capture must still land");
        assertEquals(CrashReport.Severity.MAJOR, report.severity());
        assertEquals(CrashWatcher.SOURCE, report.reporter());
    }

    @Test
    @DisplayName("the host's handler still runs, with the same thread and failure")
    void previousHandlerIsChained() {
        Thread dying = named("main");
        IllegalStateException thrown = new IllegalStateException("chained");
        AtomicReference<Thread> sawThread = new AtomicReference<>();
        AtomicReference<Throwable> sawThrown = new AtomicReference<>();
        CrashWatcher.handle(dying, thrown, reporter, (thread, failure) -> {
            sawThread.set(thread);
            sawThrown.set(failure);
            chained.add("previous");
        }, err);
        assertSame(dying, sawThread.get());
        assertSame(thrown, sawThrown.get());
        assertEquals(List.of("previous"), chained);
    }

    @Test
    @DisplayName("a previous handler that throws is reported, not propagated")
    void previousHandlerFailureIsContained() {
        CrashWatcher.handle(named("main"), new IllegalStateException("outer"), reporter,
                (thread, failure) -> {
                    throw new ArithmeticException("/ by zero in the host");
                }, err);
        String echoed = console.toString(StandardCharsets.UTF_8);
        assertTrue(echoed.contains("the previous uncaught-exception handler failed"), echoed);
        assertTrue(echoed.contains("/ by zero in the host"), echoed);
        assertEquals(1, store.list().size(), "the report was made before the host failed");
    }

    @Test
    @DisplayName("a broken console cannot skip the chained handler")
    void brokenConsoleStillChains() {
        // The console is the last channel left when the spool and the browser have
        // both failed - and it can fail too, on a closed pipe. The host's handler
        // must still be reached, so an echo that throws must not abort the chain.
        AtomicBoolean previousRan = new AtomicBoolean();
        CrashWatcher.handle(named("main"), new IllegalStateException("no console"),
                reporter, (thread, failure) -> previousRan.set(true), brokenConsole());
        assertTrue(previousRan.get(), "the chain survived a console that refused to write");
        assertEquals(1, delivered.get(), "the capture happened even though nothing was echoed");
    }

    @Test
    @DisplayName("a null throwable is not a report, but the chain still runs")
    void nullThrowableIsIgnored() {
        CrashWatcher.handle(named("main"), null, reporter,
                (thread, failure) -> chained.add("previous"), err);
        assertTrue(store.list().isEmpty(), "nothing died, so nothing is filed");
        assertEquals(List.of("previous"), chained);
        assertEquals("", console.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("install chains, is idempotent, and hands the host's handler back")
    void installAndUninstall() {
        Thread.UncaughtExceptionHandler host = (thread, failure) -> chained.add("host");
        Thread.setDefaultUncaughtExceptionHandler(host);
        assertFalse(CrashWatcher.isInstalled());

        Thread.UncaughtExceptionHandler watcher = CrashWatcher.install(reporter);
        assertSame(watcher, Thread.getDefaultUncaughtExceptionHandler());
        assertTrue(CrashWatcher.isInstalled());
        assertSame(reporter, CrashWatcher.installedReporter());
        assertSame(watcher, CrashWatcher.install(reporter), "the same reporter does not stack");

        // A second reporter replaces the pipeline; the chain target stays the host's
        // handler, so one crash is filed once and the host still sees it.
        CrashReporter other = new CrashReporter(store,
                new IssuePrefill.Target("acme/widgets", List.of(), ""),
                CrashReporter.SILENT_SINK, 1);
        Thread.UncaughtExceptionHandler replaced = CrashWatcher.install(other);
        assertNotSame(watcher, replaced, "the new watcher is the installed one");
        assertSame(other, CrashWatcher.installedReporter());

        assertTrue(CrashWatcher.uninstall());
        assertFalse(CrashWatcher.isInstalled());
        assertSame(host, Thread.getDefaultUncaughtExceptionHandler(),
                "installing a watcher must not delete what it outranked");
        assertFalse(CrashWatcher.uninstall(), "uninstall is idempotent");
        assertNull(CrashWatcher.installedReporter());
        other.shutdown();
    }

    @Test
    @DisplayName("installing with nothing, or with the switch off, changes nothing")
    void installCanRefuse() {
        Thread.UncaughtExceptionHandler host = (thread, failure) -> chained.add("host");
        Thread.setDefaultUncaughtExceptionHandler(host);
        assertSame(host, CrashWatcher.install(null));
        assertFalse(CrashWatcher.isInstalled());

        System.setProperty(CrashReporter.ENABLED_PROPERTY, "false");
        assertSame(host, CrashWatcher.install(reporter));
        assertFalse(CrashWatcher.isInstalled(), "the kill switch means no hook at all");
    }

    @Test
    @DisplayName("an exception that really escapes a thread is captured")
    void theRealJvmPathCapturesAnEscapedException() throws InterruptedException {
        // End to end, through the JVM's own dispatch: nothing here calls handle().
        AtomicBoolean previousRan = new AtomicBoolean();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> previousRan.set(true));
        CrashWatcher.install(reporter);
        Thread victim = new Thread(() -> {
            throw new IllegalStateException("escaped the worker");
        }, "lg-victim");
        victim.start();
        victim.join(10_000);
        assertTrue(previousRan.get(), "the watcher outranks the host, it does not replace it");
        CrashReport report = awaitReport("escaped the worker");
        assertNotNull(report, "the JVM called the watcher and nothing was filed");
        assertEquals(CrashReport.Severity.MAJOR, report.severity(),
                "a worker died, the desktop did not");
        assertEquals(CrashWatcher.SOURCE, report.reporter());
        assertTrue(CrashWatcher.uninstall());
    }

    @Test
    @DisplayName("captureNow writes on the calling thread, for a JVM that is ending")
    void captureNowIsSynchronous() {
        // Installed first: captureNow must use the injected reporter, because the
        // shared one is wired to the browser sink and a test has no business
        // launching a browser to prove a threading rule.
        CrashWatcher.install(reporter);
        CrashReporter.Outcome outcome = CrashWatcher.captureNow(
                new IllegalStateException("bye"), true);
        assertTrue(outcome.notified());
        assertEquals(1, delivered.get(), "the installed pipeline was the one used");
        assertEquals(CrashWatcher.SOURCE, outcome.report().reporter());
        assertEquals(CrashReport.Severity.BLOCKER, outcome.report().severity());
        assertFalse(store.list().isEmpty(), "the spool was written before the call returned");
    }

    @Test
    @DisplayName("the source is named so a tracker can tell a catch from a click")
    void sourceIsStated() {
        assertEquals("CrashWatcher", CrashWatcher.SOURCE);
        assertEquals("lg.crashwatch", CrashWatcher.LOGGER_NAME);
    }

    @Test
    @DisplayName("the console and the archive tell the same story about one crash")
    void echoAndSpoolAgree() throws IOException {
        IllegalStateException thrown = new IllegalStateException("readable");
        CrashWatcher.handle(named("main"), thrown, reporter, null, err);
        Path log = root.resolve("echo.log");
        Files.writeString(log, console.toString(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        String text = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(text.contains("[lg3d] uncaught exception in thread \"main\": "
                + "java.lang.IllegalStateException: readable"), text);
        assertTrue(text.contains("CrashWatcherTest.echoAndSpoolAgree"), text);
        // The two records of the same failure must not disagree about what it was:
        // one is what the operator saw on the console, the other what a maintainer
        // is quoted weeks later.
        CrashReport stored = store.list().get(0);
        assertTrue(stored.message().contains("readable"), stored.message());
        assertTrue(stored.stack().contains("CrashWatcherTest"), stored.stack());
    }
}
