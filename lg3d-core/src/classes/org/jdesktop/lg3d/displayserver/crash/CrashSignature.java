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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * The stable identity of a crash: what makes two reports the same bug.
 *
 * <p>A desktop that files an issue for every {@code SEVERE} record would bury the
 * maintainer: one broken display config logs the same trace a hundred times before
 * the user reaches OK. The answer is not to throttle by time - a slow leak of ten
 * distinct bugs must still produce ten issues - but to group by <em>identity</em>
 * and let the count ride along inside one issue. Identity is what this class
 * computes.</p>
 *
 * <p>Three deliberate choices define it:</p>
 *
 * <ul>
 *   <li><b>The root cause, not the wrapper.</b> lg3d re-throws through
 *   {@code SevereRuntimeError}, {@code RuntimeException} and the scene graph's own
 *   event dispatch, so the outermost class names the plumbing rather than the bug.
 *   Walking to the last {@code getCause()} and signing <em>that</em> trace makes a
 *   {@code NullPointerException} inside a layout pass sign identically whether it
 *   surfaced through a paint event or through an X11 reply decoder.</li>
 *   <li><b>Synthetic frames are skipped.</b> {@code lambda$foo$0},
 *   {@code access$100} and {@code $$Lambda$} carry a counter assigned by javac at
 *   the point in the file where the lambda was written, so adding an unrelated
 *   lambda earlier in the class <em>renumbers</em> the frames and would split one
 *   bug into several signatures across builds. The method that actually failed is
 *   in the enclosing real frame, which is what gets signed.</li>
 *   <li><b>The build version is excluded.</b> Including it sounds precise but is
 *   an anti-pattern for a tracker: the same unfixed crash after a version bump
 *   would open a fresh issue and silently orphan the older one, where the
 *   discussion and the diagnosis already live. The version belongs in the issue
 *   <em>body</em>, where a maintainer can see the report spans releases - which is
 *   the strongest signal that a bug was never fixed.</li>
 * </ul>
 *
 * <p>Pure: no I/O, no clock, no system properties, so it runs in a headless test
 * and is safe to call from the logging thread that found the error.</p>
 */
public final class CrashSignature {

    /**
     * How many real frames make up the identity. Six is a measured compromise:
     * fewer (three) collides distinct callers of one shared helper, and more
     * (twelve) makes the signature fragile to any edit below the failing line.
     */
    private static final int FRAME_COUNT = 6;

    /** Prefixes whose classes are plumbing, not the place the bug lives. */
    private static final List<String> NOISE_PREFIXES = List.of(
            "java.lang.reflect.",
            "jdk.internal.reflect.",
            "sun.reflect.",
            "java.util.concurrent.ThreadPoolExecutor",
            "java.awt.EventDispatchThread");

    private CrashSignature() {
    }

    /**
     * Computes the 12-hex-character identity of a throwable.
     *
     * @param thrown the failure, may be {@code null} (an unlogged severe record
     *               still deserves a stable bucket, so it signs on its class name)
     * @return a short lowercase hex digest, safe as a file name
     */
    public static String of(Throwable thrown) {
        return digest(material(thrown));
    }

    /**
     * Builds the canonical text the digest is taken over. Exposed because a
     * regression in grouping is invisible in a hash: a test that reads this
     * string sees <em>why</em> two crashes signed the same way.
     *
     * @param thrown the failure, may be {@code null}
     * @return the signature material, frame-separated by {@code |}
     */
    public static String material(Throwable thrown) {
        Throwable root = rootOf(thrown);
        if (root == null) {
            // A severe record with no exception at all: sign on the message's
            // first line rather than inventing a bucket every occurrence would
            // fall into. Absent that, everything unthrown shares one signature,
            // which is what a maintainer wants for "the desktop complained here".
            return "<no-throwable>";
        }
        StringBuilder sb = new StringBuilder(root.getClass().getName());
        int taken = 0;
        for (StackTraceElement frame : framesOf(root)) {
            sb.append('|').append(frame.getClassName())
              .append('#').append(frame.getMethodName());
            if (++taken >= FRAME_COUNT) {
                break;
            }
        }
        if (taken == 0) {
            // A stackless Throwable (an omitted-stack-trace optimisation, or a
            // hand-built one) has no frames to sign, and signing on the class
            // alone would merge every site that throws it. The message's first
            // line is the only remaining discriminator that survives a rebuild.
            String message = root.getMessage();
            sb.append("|<no-frames>");
            if (message != null) {
                sb.append('|').append(firstLine(message));
            }
        }
        return sb.toString();
    }

    /**
     * A one-line human-readable form of the identity, used as the issue title so
     * the tracker page itself shows the grouping instead of hiding it in a hash.
     *
     * @param thrown the failure, may be {@code null}
     * @return e.g. {@code NullPointerException at LayoutManager#doLayout}
     */
    public static String describe(Throwable thrown) {
        Throwable root = rootOf(thrown);
        if (root == null) {
            return "severe error";
        }
        List<StackTraceElement> frames = framesOf(root);
        String where = frames.isEmpty()
                ? "no stack"
                : simpleName(frames.get(0).getClassName()) + "#" + frames.get(0).getMethodName();
        return simpleName(root.getClass().getName()) + " at " + where;
    }

    /**
     * The same one-line identity, rebuilt from rendered stack <em>text</em>.
     *
     * <p>This exists for the round trip. A report written to the spool keeps text,
     * not a {@code Throwable}; when it is re-filed a week later - after a restart,
     * or from the Bug Reporter's archive list - the title must come out identical,
     * because a maintainer scanning the tracker groups by title with their eyes
     * exactly the way {@link #of} groups by hash. Reconstructing a live throwable
     * instead would either lose the frames or invent them.</p>
     *
     * <p>The root cause is read the same way {@link #material} reads it: the last
     * {@code Caused by:} line when the chain survives in the text, otherwise the
     * first line. The frame is the first {@code at } line after that - or, when the
     * JVM elided them as {@code ... 11 more}, the first frame in the document, which
     * is what the elision means: those frames are shared with the exception above.
     * Reading only the section would report "no stack" for a crash whose location is
     * printed a few lines earlier, and a cause thrown where it is wrapped is exactly
     * the common case.</p>
     *
     * @param stackText the rendered trace, may be {@code null} or empty
     * @return e.g. {@code NullPointerException at LayoutManager#doLayout}, or
     *         {@code severe error} when the text carries neither a class nor a frame
     */
    public static String describeText(String stackText) {
        if (stackText == null || stackText.isBlank()) {
            return "severe error";
        }
        String[] lines = stackText.split("\r?\n");
        int start = 0;
        for (int i = lines.length - 1; i >= 0; i--) {
            // The last "Caused by:" is the root cause, which is the one signed.
            if (lines[i].startsWith("Caused by:")) {
                start = i;
                break;
            }
        }
        String exception = null;
        String frame = null;
        for (int i = start; i < lines.length; i++) {
            String line = lines[i].trim();
            if (exception == null) {
                String text = line.startsWith("Caused by:")
                        ? line.substring("Caused by:".length()).trim() : line;
                // A frame line is not an exception header; anything else is
                // "<class>: <message>" or a bare "<class>".
                if (!text.startsWith("at ") && !text.startsWith("...")) {
                    int colon = text.indexOf(':');
                    String candidate = (colon < 0) ? text : text.substring(0, colon);
                    if (looksLikeClassName(candidate)) {
                        exception = simpleName(candidate.trim());
                    }
                }
                continue;
            }
            if (frame == null && line.startsWith("at ")) {
                frame = frameFromLine(line);
            }
        }
        if (exception == null) {
            return "severe error";
        }
        if (frame == null) {
            // The root's own frames were elided because they are the wrapper's, so
            // take the first frame the document does print - above the cause line.
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("at ")) {
                    frame = frameFromLine(trimmed);
                    break;
                }
            }
        }
        return exception + " at " + ((frame == null) ? "no stack" : frame);
    }

    /** Reads {@code at com.acme.Foo.bar(Foo.java:12)} into {@code Foo#bar}. */
    private static String frameFromLine(String line) {
        String body = line.substring(3).trim();
        int paren = body.indexOf('(');
        if (paren > 0) {
            body = body.substring(0, paren);
        } else {
            int colon = body.indexOf(':'); // a frames-less "at Unknown Source"
            if (colon > 0) {
                body = body.substring(0, colon);
            }
        }
        int dot = body.lastIndexOf('.');
        if (dot < 0) {
            return body;
        }
        return simpleName(body.substring(0, dot)) + '#' + body.substring(dot + 1);
    }

    /** A dotted identifier, which is what separates a header from prose. */
    private static boolean looksLikeClassName(String text) {
        if (text.isEmpty() || !text.contains(".") || text.contains(" ")) {
            return false;
        }
        char first = text.charAt(0);
        return Character.isLetter(first) || first == '[' || first == '_';
    }

    /**
     * Walks the cause chain to its end, refusing to loop: a malformed or
     * self-referential cause chain would otherwise spin forever on the logging
     * thread, turning a crash dialog into a hung desktop.
     *
     * @param thrown the outermost throwable, may be {@code null}
     * @return the deepest distinct cause, or {@code thrown} itself
     */
    static Throwable rootOf(Throwable thrown) {
        Throwable current = thrown;
        // The guard is a depth bound, not identity tracking: a cycle is what
        // `initCause(this)` creates, and 64 links is far past any real chain in
        // this codebase while still costing nothing.
        for (int depth = 0; depth < 64 && current != null; depth++) {
            Throwable next = current.getCause();
            if (next == null || next == current) {
                return current;
            }
            current = next;
        }
        return current;
    }

    /**
     * The frames that count: the VM's synthetic glue and the reflection/thread-pool
     * plumbing are dropped, the rest are returned in throw order.
     *
     * @param thrown the throwable whose stack is read (never {@code null})
     * @return the meaningful frames, possibly empty
     */
    static List<StackTraceElement> framesOf(Throwable thrown) {
        List<StackTraceElement> kept = new ArrayList<>();
        for (StackTraceElement frame : thrown.getStackTrace()) {
            String className = frame.getClassName();
            String methodName = frame.getMethodName();
            if (isSynthetic(methodName) || isSynthetic(className) || isNoise(className)) {
                continue;
            }
            kept.add(frame);
        }
        return kept;
    }

    /**
     * A compiler-generated frame, whose name contains a counter javac assigns by
     * source position and therefore renumbers on an unrelated edit.
     */
    private static boolean isSynthetic(String name) {
        return name == null
                || name.contains("lambda$")
                || name.contains("access$")
                || name.contains("$$")
                || name.startsWith("-{" );
    }

    /** A frame in infrastructure between the bug and the reporter. */
    private static boolean isNoise(String className) {
        for (String prefix : NOISE_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String digest(String material) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK crypto spec, so this is unreachable
            // on any JVM that ran the rest of this module. Falling back to the
            // material's hashCode keeps grouping alive on a stripped-down VM
            // rather than crashing the error path - which is the wrong thing to
            // do while reporting an error.
            return Integer.toHexString(material.hashCode());
        }
    }

    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        String line = (nl < 0) ? text : text.substring(0, nl);
        return line.length() > 120 ? line.substring(0, 120) : line;
    }

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return (dot < 0) ? className : className.substring(dot + 1);
    }
}
