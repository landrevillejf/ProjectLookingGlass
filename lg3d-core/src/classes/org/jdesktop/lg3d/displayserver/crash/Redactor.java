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

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes from a report everything a crash dump has no business carrying.
 *
 * <p>A stack trace is the most leaky artefact a program can produce. It prints
 * absolute paths, which name the user; it prints the strings a method was holding
 * when it failed, which for a mail client, a password manager, an SSH terminal, an
 * FTP panel or a VPN dialog is exactly the data a user never wanted sent anywhere;
 * and since this tool's job is to move that text onto a <em>public</em> issue
 * tracker, the removal happens before the spool write, not before display - a
 * report that is redacted only on screen is a report that leaked to disk.</p>
 *
 * <p>Two rules shape the implementation. First, <b>the pattern list is a deny
 * list, and the report still survives it</b>: a scrub that empties a trace is
 * useless to the maintainer, so each substitution keeps the shape
 * ({@code ~/…}, {@code <token>}) that let the reader see what kind of value was
 * there. Second, <b>no pattern may backtrack catastrophically</b>: this runs on
 * the thread that just caught an error, on attacker-influenced message text, so
 * every expression below is linear - one character class, one anchor, no nested
 * quantifier and no alternation over an unbounded run. A regular expression that
 * can hang the crash reporter is worse than the leak it prevents.</p>
 *
 * <p>Pure: the home directory and user name are passed in rather than read from
 * {@code System}, so a test can prove the substitution on a synthetic path instead
 * of on the machine that runs the suite.</p>
 */
public final class Redactor {

    /** Replacement for a masked secret, keeping the kind of value visible. */
    public static final String TOKEN_MASK = "<token>";

    /** Replacement for an absolute path under the user's home. */
    public static final String HOME_MASK = "~";

    /** Replacement for a user name, wherever it leaked into a message. */
    public static final String USER_MASK = "<user>";

    /** Replacement for an e-mail address. */
    public static final String EMAIL_MASK = "<email>";

    /** Replacement for a runtime directory such as {@code /run/user/1000}. */
    public static final String RUNTIME_MASK = "<runtime-dir>";

    /**
     * One pass per pattern, in this order: a path is masked before the user-name
     * sweep so the home-directory form survives as {@code ~/} rather than turning
     * into {@code /home/<user>/}, and tokens are masked last-to-first by
     * specificity - the shaped credentials (GitHub, bearer) run before the
     * key=value form, which would otherwise consume the marker and leave the
     * secret body intact.
     */
    private static final List<Pattern> SHAPED_SECRETS = List.of(
            // GitHub: classic gh{p,u,s,r}_{36+} and the fine-grained github_pat_ form.
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9_]{20,}\\b"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"),
            // An Authorization header or a Bearer credential, value included.
            Pattern.compile("(?i)\\bauthorization\\b\\s*[:=]\\s*[^\\s,;]+(?:\\s+[^\\s,;]+)?"),
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{8,}"),
            // AWS and Google API keys, both fixed-shape. The AWS alphabet is every
            // capital letter and digit: a class narrowed to "[0-9A]" silently leaves
            // a real key such as AKIAIOSFODNN7EXAMPLE unmasked.
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{30,}\\b"));

    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            // password / passwd / secret / token / apikey / private_key = value
            // The value runs to the first delimiter, so a whole line is never eaten.
            "(?i)\\b(password|passwd|pwd|secret|secret-key|token|api[-_]?key|access[-_]?key"
                    + "|private[-_]?key|client[-_]?secret)\\b(\\s*[=:]\\s*)([^\\s,;'\"&]+)");

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** Appended to any clipped text so a reader cannot mistake it for a full trace. */
    public static final String CLIP_MARK = "… [truncated]";

    private Redactor() {
    }

    /**
     * Masks a secret-shaped substring and returns the text unchanged when nothing
     * matched.
     *
     * @param text          the raw message or trace, {@code null} treated as empty
     * @param homeDirectory the absolute home path to shorten, may be {@code null}
     * @param userName      the account name to mask, may be {@code null}
     * @return the report-safe text, never {@code null}
     */
    public static String redact(String text, String homeDirectory, String userName) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String out = text;
        if (homeDirectory != null && !homeDirectory.isBlank()) {
            out = out.replace(homeDirectory, HOME_MASK);
            // A path built with a trailing separator, and the Windows form with a
            // different separator direction, both shorten to the same marker.
            out = out.replace(fallbackHome(homeDirectory), HOME_MASK);
        }
        out = RUNTIME_PATTERN.matcher(out).replaceAll(RUNTIME_MASK);
        if (userName != null && !userName.isBlank() && userName.length() > 1) {
            // Pattern.quote, not Matcher.quoteReplacement: the name is the pattern
            // here, and an account name holding a metacharacter would otherwise
            // throw PatternSyntaxException inside the redactor - a crash in the
            // component whose whole job is to survive a crash.
            out = out.replaceAll("(?<![A-Za-z0-9._-])" + Pattern.quote(userName)
                    + "(?![A-Za-z0-9._-])", Matcher.quoteReplacement(USER_MASK));
        }
        for (Pattern pattern : SHAPED_SECRETS) {
            out = pattern.matcher(out).replaceAll(TOKEN_MASK);
        }
        out = KEY_VALUE_SECRET.matcher(out)
                .replaceAll("$1$2" + Matcher.quoteReplacement(TOKEN_MASK));
        out = EMAIL.matcher(out).replaceAll(EMAIL_MASK);
        return out;
    }

    /**
     * Keeps at most {@code maxLines} of a stack trace, from the top, and says so.
     *
     * <p>The head is what matters: the failing frame is first, and the tail is a
     * runic sequence of dispatchers identical in every crash. Truncating the head
     * instead would keep the noise and discard the bug.</p>
     *
     * @param trace    the stack text, may be {@code null}
     * @param maxLines the budget, counted in lines
     * @return the kept lines plus a truncation note when lines were dropped
     */
    public static String clipStack(String trace, int maxLines) {
        if (trace == null || trace.isEmpty()) {
            return "";
        }
        String[] lines = trace.split("\r?\n", -1);
        if (lines.length <= maxLines) {
            return trace;
        }
        // Idempotent on purpose: a spooled report is read back and rebuilt, so a
        // trace that already says how much it lost must keep the first figure. A
        // second pass would count the dropped lines as one, and the number a
        // maintainer is told would quietly describe the clipper instead of the bug.
        if (lines[lines.length - 1].startsWith(CLIP_MARK)) {
            return trace;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]).append('\n');
        }
        sb.append(CLIP_MARK).append(' ').append(lines.length - maxLines).append(" more line(s)");
        return sb.toString();
    }

    /**
     * Keeps at most {@code maxChars} of a free-text field, cutting on a word
     * boundary when one is nearby.
     *
     * @param text     the user's narrative or a message, may be {@code null}
     * @param maxChars the budget, in characters
     * @return the clipped text, never longer than {@code maxChars + CLIP_MARK}
     */
    public static String clipText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        // Same rule as the trace clipper: a field already cut is left alone, so a
        // report copied through the archive cannot grow a second marker (and the
        // bound stays "budget + one marker" however many times it is rebuilt).
        if (text.endsWith(CLIP_MARK)) {
            return text;
        }
        int cut = text.lastIndexOf(' ', Math.min(maxChars, text.length() - 1));
        // A single 4 000-character word (a base64 blob pasted into the form) has
        // no space to land on; cutting mid-token beats searching the whole string.
        if (cut < maxChars / 2) {
            cut = maxChars;
        }
        return text.substring(0, cut) + CLIP_MARK;
    }

    /** The {@code /run/user/<uid>} family, which names a session and a uid. */
    private static final Pattern RUNTIME_PATTERN =
            Pattern.compile("/run/user/\\d+|/tmp/\\.(?:X11|wayland)-[^\\s/]+");

    /**
     * The second home form: {@code /home/alex} must shorten when the property says
     * {@code /home/alex/}, and vice versa, because message text is written by
     * whoever formatted it and neither author consulted the other.
     */
    private static String fallbackHome(String home) {
        return home.endsWith("/") ? home.substring(0, home.length() - 1) : home + "/";
    }
}
