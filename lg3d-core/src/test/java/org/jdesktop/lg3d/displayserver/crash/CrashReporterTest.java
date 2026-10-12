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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for the facade - the four rules the class states about itself.
 * Every test injects a recording sink, so nothing here opens a browser or touches
 * the network: what is under proof is the decision of <em>whether</em> to deliver,
 * which is the difference between a tool that reports crashes and one that
 * interferes with the user.
 */
class CrashReporterTest {

    @TempDir
    Path root;

    /** A sink that counts calls and remembers the last link it was handed. */
    private static final class RecordingSink implements CrashReporter.Sink {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> lastUrl = new AtomicReference<>();

        @Override
        public String deliver(CrashReport report, IssuePrefill.PrefilledUrl prefill) {
            calls.incrementAndGet();
            lastUrl.set(prefill.url());
            return "delivered " + report.signature();
        }
    }

    private CrashStore store;
    private IssuePrefill.Target target;
    private RecordingSink sink;
    private CrashReporter reporter;

    @BeforeEach
    void wire() {
        store = new CrashStore(root, CrashStore.DEFAULT_MAX_REPORTS,
                CrashStore.DEFAULT_MAX_BYTES);
        target = new IssuePrefill.Target("acme/widgets", List.of("crash"), "maintainer");
        sink = new RecordingSink();
        reporter = new CrashReporter(store, target, sink, CrashReporter.DEFAULT_MAX_PER_SESSION);
    }

    @AfterEach
    void tearDown() {
        reporter.shutdown();
        CrashReporter.setShared(null);
        System.clearProperty(CrashReporter.ENABLED_PROPERTY);
        System.clearProperty(CrashReporter.REPO_PROPERTY);
        System.clearProperty(CrashReporter.LABELS_PROPERTY);
        System.clearProperty(CrashReporter.ASSIGNEE_PROPERTY);
        System.clearProperty(CrashReporter.MAX_PER_SESSION_PROPERTY);
        System.clearProperty("lg.crashreport.dir");
    }

    /** A distinct failure each call, so signatures do not collide by accident. */
    private static IllegalStateException failure(String message) {
        return new IllegalStateException(message);
    }

    /**
     * A failure from a different place, which is what makes it a different bug.
     * {@code setStackTrace} is the honest way to build that: the identity is read
     * off the frames, so a test that needs six distinct bugs needs six distinct
     * sites, and writing six methods to get them would say nothing about the code.
     */
    private static IllegalStateException failureAt(String message, int site) {
        IllegalStateException thrown = failure(message);
        thrown.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("com.acme.Thing", "method" + site, "Thing.java", site),
            new StackTraceElement("com.acme.Thing", "run", "Thing.java", 1),
        });
        return thrown;
    }

    @Test
    @DisplayName("a first sighting is delivered, the same bug again is only counted")
    void oneTabPerBugPerSession() {
        CrashReporter.Outcome first = reporter.report(failure("layout loop"),
                CrashReport.Kind.CRASH, false, "CrashWatcherTest");
        assertFalse(first.duplicate());
        assertTrue(first.notified());
        assertEquals(1, first.occurrences());
        assertNotNull(first.spoolFile());
        assertTrue(sink.lastUrl.get().startsWith("https://github.com/acme/widgets/issues/new?"),
                sink.lastUrl.get());

        CrashReporter.Outcome again = reporter.report(failure("layout loop"),
                CrashReport.Kind.CRASH, false, "CrashWatcherTest");
        assertTrue(again.duplicate(), "the same signature must not open a second tab");
        assertFalse(again.notified());
        assertEquals(2, again.occurrences());
        assertEquals(1, sink.calls.get(), "the sink was asked exactly once");
        assertEquals(2, store.list().get(0).occurrences(),
                "the archive counts the repeat even though nothing was opened");
    }

    @Test
    @DisplayName("a crash loop costs one tab and a bounded number of interruptions")
    void budgetCapsDistinctBugs() {
        CrashReporter limited = new CrashReporter(store, target, sink, 2);
        for (int i = 0; i < 6; i++) {
            limited.report(failureAt("bug " + i, i + 1),
                    CrashReport.Kind.CRASH, false, "test");
        }
        assertEquals(2, sink.calls.get(),
                "only two distinct bugs may interrupt the user");
        assertEquals(6, store.list().size(),
                "capture is cheap and local: the budget limits the noise, never the record");
        assertTrue(limited.report(failureAt("bug 6", 7), CrashReport.Kind.CRASH, false, "test")
                .note().contains("per-session limit"));
    }

    @Test
    @DisplayName("one crash site is one bug, whatever its message says each time")
    void aLoopOfDifferentMessagesIsOneReport() {
        // The signature ignores the message on purpose: a cache key or a file name
        // inside the text would split one crash loop into forty issues and forty
        // browser tabs, which is the failure mode this tool exists to prevent.
        CrashReporter loop = new CrashReporter(store, target, sink,
                CrashReporter.DEFAULT_MAX_PER_SESSION);
        for (int i = 0; i < 20; i++) {
            loop.report(failure("missing entry " + i),
                    CrashReport.Kind.CRASH, false, "test");
        }
        assertEquals(1, sink.calls.get(), "one tab for twenty sightings");
        assertEquals(1, loop.store().list().size(), "one entry in the archive");
        assertEquals(20, loop.store().list().get(0).occurrences());
        loop.shutdown();
    }

    @Test
    @DisplayName("a report someone asked for overrides every noise rule")
    void explicitDeliveryIgnoresDedupeAndBudget() {
        CrashReporter limited = new CrashReporter(store, target, sink, 1);
        Throwable thrown = failure("layout loop");
        CrashReport report = CrashReport.of(thrown, CrashReport.Kind.CRASH, false, "test");
        limited.report(thrown, CrashReport.Kind.CRASH, false, "test");
        int afterAutomatic = sink.calls.get();

        // Same signature, same session, budget already spent: a person pressed the
        // button, which is the one case where suppressing the report would make the
        // tool untrustworthy.
        CrashReporter.Outcome clicked = limited.report(report);
        assertTrue(clicked.notified());
        assertEquals(afterAutomatic + 1, sink.calls.get());
    }

    @Test
    @DisplayName("a bug already in the archive is not reopened by a new session")
    void archiveSuppressesADuplicateIssue() {
        // The same throwable twice: one identity, two sessions of the tally.
        Throwable thrown = failure("printer offline");
        store.store(CrashReport.of(thrown, CrashReport.Kind.CRASH, false, "test"));
        CrashReporter second = new CrashReporter(store, target, sink,
                CrashReporter.DEFAULT_MAX_PER_SESSION);
        CrashReporter.Outcome outcome = second.report(thrown,
                CrashReport.Kind.CRASH, false, "test");
        assertTrue(outcome.duplicate(), "the spool already knows this bug");
        assertFalse(outcome.notified());
        assertTrue(outcome.note().contains("archive"), outcome.note());
        assertEquals(0, sink.calls.get());
        second.shutdown();
    }

    @Test
    @DisplayName("the kill switch stops capture, but never a report a person wrote")
    void enabledGateAppliesToTheAutomaticPathOnly() {
        System.setProperty(CrashReporter.ENABLED_PROPERTY, "false");
        assertFalse(CrashReporter.isEnabled());
        CrashReporter.Outcome outcome = reporter.report(failure("anything"),
                CrashReport.Kind.CRASH, true, "test");
        assertFalse(outcome.notified());
        assertNull(outcome.report());
        assertTrue(outcome.note().contains(CrashReporter.ENABLED_PROPERTY), outcome.note());
        assertEquals(0, sink.calls.get());
        assertNull(reporter.reportAsync(failure("anything"),
                CrashReport.Kind.CRASH, false, "test"));
        assertNull(reporter.capture(failure("anything"), CrashReport.Kind.CRASH, false,
                "test", ready -> sink.calls.incrementAndGet()));

        // A hand-written report is a person's decision, not the desktop nagging.
        CrashReporter.Outcome hand = reporter.report(CrashReport.builder()
                .message("the taskbar is off by one pixel")
                .kind(CrashReport.Kind.MANUAL).build());
        assertTrue(hand.notified());
    }

    @Test
    @DisplayName("only an explicit false disables - a broken property still reports")
    void enabledDefaultsToOn() {
        assertTrue(CrashReporter.isEnabled());
        System.setProperty(CrashReporter.ENABLED_PROPERTY, "no");
        assertTrue(CrashReporter.isEnabled(), "not the literal word false, so still on");
        System.setProperty(CrashReporter.ENABLED_PROPERTY, " FALSE ");
        assertFalse(CrashReporter.isEnabled());
    }

    @Test
    @DisplayName("capture runs off the caller's thread and calls the listener back")
    void captureIsAsynchronous() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<CrashReporter.Outcome> delivered = new AtomicReference<>();
        AtomicReference<String> thread = new AtomicReference<>();
        Future<CrashReporter.Outcome> future = reporter.capture(
                failure("socket closed"), CrashReport.Kind.SEVERE, false, "LogHandler",
                outcome -> {
                    delivered.set(outcome);
                    thread.set(Thread.currentThread().getName());
                    ready.countDown();
                });
        assertNotNull(future);
        assertTrue(ready.await(10, TimeUnit.SECONDS), "the listener never ran");
        assertNotNull(delivered.get());
        assertEquals("lg-crash-reporter", thread.get(),
                "the dialog thread must not do the file I/O");
        future.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a listener that throws does not lose the report")
    void listenerFailureIsContained() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        Future<CrashReporter.Outcome> future = reporter.capture(
                failure("texture lost"), CrashReport.Kind.SEVERE, false, "test",
                outcome -> {
                    called.countDown();
                    throw new IllegalStateException("the dialog went away");
                });
        assertNotNull(future);
        assertTrue(called.await(10, TimeUnit.SECONDS));
        CrashReporter.Outcome outcome = future.get(10, TimeUnit.SECONDS);
        assertNotNull(outcome);
        assertTrue(outcome.notified(), "the report exists whether or not a listener survived");
    }

    @Test
    @DisplayName("a sink that throws cannot propagate into the dying desktop")
    void sinkFailureIsContained() {
        CrashReporter hostile = new CrashReporter(store, target,
                (report, prefill) -> {
                    throw new IllegalStateException("no browser here");
                }, CrashReporter.DEFAULT_MAX_PER_SESSION);
        CrashReporter.Outcome outcome = assertOutcome(hostile.report(failure("printer jam"),
                CrashReport.Kind.CRASH, true, "test"));
        assertFalse(outcome.notified());
        assertTrue(outcome.note().contains("capture failed"), outcome.note());
        assertNull(outcome.prefill(), "the link is lost with the sink");
        // The archive is written before delivery is attempted, so the report of the
        // crash survives the reporter's own failure - which is the whole point.
        assertEquals(1, store.list().size());
        assertTrue(store.list().get(0).message().contains("printer jam"));
        hostile.shutdown();
    }

    /** The reporter must never throw; a test that lets it would fail on the spot. */
    private static CrashReporter.Outcome assertOutcome(CrashReporter.Outcome outcome) {
        assertNotNull(outcome);
        return outcome;
    }

    @Test
    @DisplayName("an unwritable spool still delivers the link")
    void brokenSpoolStillReports() throws IOException {
        Path blocked = root.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        CrashStore broken = new CrashStore(blocked, 4, 4096L);
        CrashReporter withBrokenSpool = new CrashReporter(broken, target, sink,
                CrashReporter.DEFAULT_MAX_PER_SESSION);
        CrashReporter.Outcome outcome = withBrokenSpool.report(failure("disk full"),
                CrashReport.Kind.CRASH, true, "test");
        assertNull(outcome.spoolFile(), "there is no path to quote");
        assertTrue(outcome.notified(), "a missing archive must not stop the bug being filed");
        assertNotNull(outcome.prefill());
        withBrokenSpool.shutdown();
    }

    @Test
    @DisplayName("a null throwable is still a report")
    void messageOnlyCapture() {
        CrashReporter.Outcome outcome = reporter.report((Throwable) null,
                CrashReport.Kind.MANUAL, false, "test");
        assertNotNull(outcome.report());
        assertEquals(CrashReport.Kind.MANUAL, outcome.report().kind());
        assertTrue(outcome.notified());
    }

    @Test
    @DisplayName("prefill builds a link without capturing or delivering")
    void prefillIsSideEffectFree() {
        CrashReport report = CrashReport.of(failure("just a link"),
                CrashReport.Kind.CRASH, false, "test");
        IssuePrefill.PrefilledUrl prefill = reporter.prefill(report);
        assertTrue(prefill.url().contains("labels=crash"), prefill.url());
        assertTrue(prefill.url().contains("assignees=maintainer"), prefill.url());
        assertEquals(0, sink.calls.get());
        assertTrue(store.list().isEmpty(), "nothing was spooled");
    }

    @Test
    @DisplayName("the crash label cannot be configured away")
    void targetAlwaysCarriesTheCrashLabel() {
        System.setProperty(CrashReporter.LABELS_PROPERTY, "3d, taskbar");
        System.setProperty(CrashReporter.REPO_PROPERTY, "someone/else");
        System.setProperty(CrashReporter.ASSIGNEE_PROPERTY, "bot");
        IssuePrefill.Target configured = CrashReporter.targetFromProperties();
        assertEquals("someone/else", configured.repo());
        assertEquals(List.of("3d", "taskbar", "crash"), configured.labels());
        assertEquals("bot", configured.assignee());
        assertTrue(CrashReporter.targetFromProperties().labels().contains("crash"),
                "without it the queue cannot tell a captured crash from a written issue");
    }

    @Test
    @DisplayName("a garbage budget falls back, and zero is floored to one")
    void budgetIsFloored() {
        // fromProperties() wires the browser sink, so what a test can reach is the
        // configuration it reads, not the delivery it would perform: the spool and
        // the tracker are both taken from the properties, and a nonsense count is
        // ignored rather than parsed as zero, which would mute every report.
        System.setProperty("lg.crashreport.dir", root.resolve("from-props").toString());
        System.setProperty(CrashReporter.MAX_PER_SESSION_PROPERTY, "not-a-number");
        CrashReporter configured = CrashReporter.fromProperties();
        assertEquals(root.resolve("from-props"), configured.store().root());
        assertEquals(IssuePrefill.DEFAULT_REPO, configured.target().repo());
        assertTrue(configured.target().labels().contains(CrashReporter.DEFAULT_LABEL));
        configured.shutdown();

        CrashReporter one = new CrashReporter(store, target, sink, 0);
        one.report(failure("budget floor"), CrashReport.Kind.CRASH, false, "test");
        assertEquals(1, sink.calls.get(), "a budget of zero still lets one report through");
        one.shutdown();
    }

    @Test
    @DisplayName("the shared reporter is created once and can be replaced by a host")
    void sharedInstanceIsReplaceable() {
        System.setProperty("lg.crashreport.dir", root.resolve("shared").toString());
        CrashReporter first = CrashReporter.shared();
        assertSame(first, CrashReporter.shared(), "one desktop, one tally");
        CrashReporter injected = new CrashReporter(store, target, sink, 1);
        CrashReporter.setShared(injected);
        assertSame(injected, CrashReporter.shared());
        CrashReporter.setShared(null);
        assertNotNull(CrashReporter.shared(), "null means fall back to the properties");
        CrashReporter.shared().shutdown();
    }

    @Test
    @DisplayName("a target that cannot be built is refused before any report exists")
    void targetIsValidated() {
        assertThrows(IllegalArgumentException.class,
                () -> new IssuePrefill.Target(" ", List.of("crash"), ""));
    }
}
