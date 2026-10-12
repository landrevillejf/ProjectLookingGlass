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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the grouping identity. Everything a crash reporter does -
 * dedupe, the archive counter, "nothing opened for a bug you already filed" - is
 * a consequence of this hash, so a signature that over-merges hides bugs and one
 * that over-splits buries the tracker in duplicates. The tests assert on
 * {@link CrashSignature#material} as well as the digest: a wrong group is
 * invisible inside a hash, and visible in the text it was computed from.
 */
class CrashSignatureTest {

    /** A frame whose class and method are both controlled, for filter tests. */
    private static StackTraceElement frame(String className, String method) {
        return new StackTraceElement(className, method, "Source.java", 1);
    }

    private static Throwable withFrames(Throwable thrown, StackTraceElement... frames) {
        thrown.setStackTrace(List.of(frames).toArray(new StackTraceElement[0]));
        return thrown;
    }

    @Test
    @DisplayName("two crashes in the same method sign the same, whatever their line")
    void sameSiteIsOneBug() {
        Throwable first = boom("called twice");
        Throwable second = boom("called twice");
        assertEquals(CrashSignature.of(first), CrashSignature.of(second),
                "the line number is not in the material, so an unrelated edit above "
                        + "the failing call must not open a second issue");
    }

    @Test
    @DisplayName("different exception classes at one site are different bugs")
    void exceptionTypeIsPartOfTheIdentity() {
        assertNotEquals(CrashSignature.of(new NullPointerException("x")),
                CrashSignature.of(new IllegalArgumentException("x")));
    }

    @Test
    @DisplayName("the root cause is signed, not the wrapper")
    void wrapperDoesNotChangeTheIdentity() {
        Throwable root = new IllegalStateException("disk gone");
        Throwable wrapped = new RuntimeException("while saving", root);
        root.setStackTrace(new StackTraceElement[] {frame("com.acme.Storage", "flush")});
        wrapped.setStackTrace(new StackTraceElement[] {frame("com.acme.Unrelated", "wrap")});
        assertEquals(CrashSignature.of(root), CrashSignature.of(wrapped),
                "the same failure wrapped by a different layer is the same bug");
    }

    @Test
    @DisplayName("synthetic and plumbing frames are dropped from the material")
    void plumbingIsNotTheBug() {
        Throwable thrown = withFrames(new NullPointerException("npe"),
                frame("org.jdesktop.lg3d.apps.Editor", "lambda$open$0"),
                frame("java.lang.reflect.Method", "invoke"),
                frame("java.util.concurrent.ThreadPoolExecutor", "runWorker"),
                frame("java.awt.EventDispatchThread", "pumpOneEventForFilters"),
                frame("org.jdesktop.lg3d.wg.LayoutManager", "doLayout"));
        String material = CrashSignature.material(thrown);
        assertFalse(material.contains("lambda$"), "a counter javac renumbers on edit");
        assertFalse(material.contains("reflect"), "reflection is plumbing");
        assertFalse(material.contains("ThreadPoolExecutor"), "the pool is plumbing");
        assertFalse(material.contains("EventDispatchThread"), "the EDT is plumbing");
        assertTrue(material.contains("LayoutManager#doLayout"),
                "the first real frame is the bug's address");
    }

    @Test
    @DisplayName("at most six frames make the identity")
    void frameCountIsBounded() {
        StackTraceElement[] frames = new StackTraceElement[12];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = frame("com.acme.Caller" + i, "run" + i);
        }
        String material = CrashSignature.material(withFrames(
                new NullPointerException("npe"), frames));
        // The class name plus the six frames it keeps, and nothing of the six below.
        assertEquals(7, material.split("\\|").length,
                "deeper frames make the identity fragile to an edit far below the "
                        + "failing line");
        assertFalse(material.contains("Caller6"));
    }

    @Test
    @DisplayName("a stackless throwable signs on its message")
    void stacklessStillSeparates() {
        Throwable a = new IllegalStateException("cache overflow");
        Throwable b = new IllegalStateException("font not found");
        a.setStackTrace(new StackTraceElement[0]);
        b.setStackTrace(new StackTraceElement[0]);
        assertNotEquals(CrashSignature.of(a), CrashSignature.of(b),
                "with no frames, the message is the only discriminator left");
        assertTrue(CrashSignature.material(a).contains("<no-frames>"));
    }

    @Test
    @DisplayName("a severe record with no throwable still gets a stable bucket")
    void noThrowableIsStable() {
        assertEquals(CrashSignature.of(null), CrashSignature.of(null));
        assertEquals("<no-throwable>", CrashSignature.material(null));
        assertEquals("severe error", CrashSignature.describe(null));
    }

    @Test
    @DisplayName("describe names the class and the failing frame for the title")
    void describeIsHumanReadable() {
        Throwable thrown = withFrames(new NullPointerException("nothing here"),
                frame("org.jdesktop.lg3d.wg.LayoutManager", "doLayout"));
        assertEquals("NullPointerException at LayoutManager#doLayout",
                CrashSignature.describe(thrown));
    }

    @Test
    @DisplayName("a report re-read from text produces the same title as the live one")
    void describeTextRoundTrips() {
        Throwable thrown = new RuntimeException("wrapper",
                new IllegalArgumentException("bad color"));
        String rendered = CrashReport.render(thrown);
        assertEquals(CrashSignature.describe(thrown), CrashSignature.describeText(rendered),
                "the spool keeps text; re-filing an archived crash must not open a "
                        + "near-duplicate issue with a different title");
    }

    @Test
    @DisplayName("describeText refuses prose it cannot read as a trace")
    void describeTextOnJunk() {
        assertEquals("severe error", CrashSignature.describeText(null));
        assertEquals("severe error", CrashSignature.describeText("   "));
        assertEquals("severe error", CrashSignature.describeText("the disk is on fire"));
    }

    @Test
    @DisplayName("elided frames mean the ones printed above, and describeText knows it")
    void describeTextFollowsElision() {
        // printStackTrace collapses a cause thrown where it is wrapped to
        // "... 11 more"; a title that then said "no stack" would lose the location
        // the document prints four lines earlier.
        String rendered = "java.lang.RuntimeException: wrapper\n"
                + "\tat com.acme.Editor.open(Editor.java:88)\n"
                + "\tat java.base/java.lang.Thread.run(Thread.java:1583)\n"
                + "Caused by: java.lang.IllegalArgumentException: bad color\n"
                + "\t... 11 more\n";
        assertEquals("IllegalArgumentException at Editor#open",
                CrashSignature.describeText(rendered));
    }

    @Test
    @DisplayName("a trace with no frame line at all says so instead of guessing")
    void describeTextWithoutAnyFrame() {
        assertEquals("NullPointerException at no stack",
                CrashSignature.describeText("java.lang.NullPointerException: nothing\n"));
    }

    @Test
    @DisplayName("a cyclic cause chain is walked, not followed forever")
    void cycleCannotHangTheLogger() {
        Cyclic a = new Cyclic();
        Cyclic b = new Cyclic();
        a.other = b;
        b.other = a;
        // Without the depth bound this call never returns, on the thread that is
        // trying to tell the user about a crash.
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertFalse(CrashSignature.of(a).isBlank()));
    }

    @Test
    @DisplayName("the digest is a short lowercase hex string usable as a file name")
    void signatureIsPathSafe() {
        String signature = CrashSignature.of(boom("name"));
        assertTrue(signature.matches("[0-9a-f]{12}"), signature);
    }

    // ---------------------------------------------------------------- fixtures

    /** A throwable created inside this method, so its frames are reproducible. */
    private static Throwable boom(String message) {
        try {
            java.util.Objects.requireNonNull(null, message);
            throw new IllegalStateException("unreachable");
        } catch (NullPointerException e) {
            return e;
        }
    }

    /** A cause chain that loops, which {@code initCause} refuses to build. */
    private static final class Cyclic extends RuntimeException {
        private Cyclic other;

        @Override
        public synchronized Throwable getCause() {
            return other;
        }
    }
}
