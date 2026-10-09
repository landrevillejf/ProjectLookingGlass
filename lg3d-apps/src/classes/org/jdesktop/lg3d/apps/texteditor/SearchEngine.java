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
package org.jdesktop.lg3d.apps.texteditor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The pure search-and-replace engine behind the editor's Find bar: literal or
 * regular-expression matching, optional case sensitivity, forward/backward
 * navigation with wrap-around, match counting and replace-one / replace-all.
 *
 * <p>Everything here works on plain {@code String}s and never touches Swing,
 * so the full semantics (including the regex edge cases) are unit-testable
 * headless; the UI layer only maps offsets back onto the document. Literal
 * queries are compiled with {@link Pattern#quote} and literal replacements
 * escaped with {@link Matcher#quoteReplacement}, so a user's {@code $1} or
 * {@code (} is always taken at face value unless the regex toggle is on. An
 * invalid regex surfaces as {@link PatternSyntaxException} from
 * {@link #compile} for the UI to report; a zero-length regex match advances
 * one character so scanning can never livelock.</p>
 */
public final class SearchEngine {

    /** One search hit: the {@code [start, end)} span of a match. */
    public record Match(int start, int end) {

        /** The length of the matched span. */
        public int length() {
            return end - start;
        }
    }

    /** The outcome of a replace-all: the new text and the replacement count. */
    public record ReplaceResult(String text, int count) {
    }

    /** A hard cap so a pathological regex cannot freeze the EDT for minutes. */
    public static final int MAX_MATCHES = 100_000;

    private SearchEngine() {
        // Static utility.
    }

    /**
     * Compiles the query into a pattern: quoted when literal, raw when regex.
     *
     * @throws PatternSyntaxException if {@code regex} and the query is invalid
     */
    public static Pattern compile(String query, boolean regex,
            boolean matchCase) throws PatternSyntaxException {
        String source = regex ? query : Pattern.quote(query);
        int flags = Pattern.DOTALL;
        if (!matchCase) {
            flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        }
        return Pattern.compile(source, flags);
    }

    /**
     * Every match of {@code query} in {@code text}, in order, stopping at
     * {@link #MAX_MATCHES}. Returns an empty list for a null/empty query or
     * an invalid regex (the UI validates separately through {@link #compile}).
     */
    public static List<Match> findAll(String text, String query,
            boolean regex, boolean matchCase) {
        List<Match> out = new ArrayList<>();
        if (text == null || query == null || query.isEmpty()) {
            return out;
        }
        try {
            Matcher matcher = compile(query, regex, matchCase).matcher(text);
            int at = 0;
            while (at <= text.length() && out.size() < MAX_MATCHES
                    && matcher.find(at)) {
                int start = matcher.start();
                int end = matcher.end();
                if (start < at) {
                    // find(at) may report an earlier zero-width match; skip.
                    start = at;
                }
                out.add(new Match(start, Math.max(end, start)));
                at = Math.max(end, start + 1);
            }
        } catch (PatternSyntaxException pse) {
            out.clear();
        } catch (StackOverflowError soe) {
            // Catastrophic backtracking on a huge document: report "no match"
            // rather than killing the desktop JVM.
            out.clear();
        }
        return out;
    }

    /**
     * The next match at or after {@code from} (forward) or at or before it
     * (backward), wrapping around the document when {@code wrap} is set.
     * Returns null when nothing matches in the requested direction.
     */
    public static Match find(String text, String query, int from,
            boolean regex, boolean matchCase, boolean backward, boolean wrap) {
        if (text == null || query == null || query.isEmpty()) {
            return null;
        }
        List<Match> all = findAll(text, query, regex, matchCase);
        if (all.isEmpty()) {
            return null;
        }
        int origin = Math.max(0, Math.min(from, text.length()));
        if (!backward) {
            for (Match match : all) {
                if (match.start() >= origin) {
                    return match;
                }
            }
            return wrap ? all.get(0) : null;
        }
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).start() < origin) {
                return all.get(i);
            }
        }
        return wrap ? all.get(all.size() - 1) : null;
    }

    /**
     * Replaces every match in one pass. In literal mode the replacement is
     * inserted verbatim; in regex mode {@code $n} / {@code ${name}} group
     * references and {@code \} escapes are honoured, tolerantly: a dangling
     * {@code $} or a reference to a non-existent group is kept verbatim
     * rather than failing the whole operation.
     */
    public static ReplaceResult replaceAll(String text, String query,
            String replacement, boolean regex, boolean matchCase) {
        if (text == null || query == null || query.isEmpty()) {
            return new ReplaceResult(text, 0);
        }
        String with = (replacement != null) ? replacement : "";
        try {
            Matcher matcher = compile(query, regex, matchCase).matcher(text);
            StringBuilder out = new StringBuilder();
            int count = 0;
            int last = 0;
            int at = 0;
            while (at <= text.length() && count < MAX_MATCHES
                    && matcher.find(at)) {
                int start = matcher.start();
                int end = Math.max(matcher.end(), start);
                out.append(text, last, start);
                out.append(regex ? expandSpec(with, matcher) : with);
                count++;
                last = end;
                at = Math.max(end, start + 1);
            }
            out.append(text.substring(last));
            return new ReplaceResult(out.toString(), count);
        } catch (PatternSyntaxException | StackOverflowError err) {
            return new ReplaceResult(text, 0);
        }
    }

    /**
     * Replaces a single previously located match. The replacement goes
     * through the same literal/regex group semantics as
     * {@link #replaceAll}. A stale {@code match} (outside the text, or no
     * longer matching the query at that offset) leaves the text unchanged.
     */
    public static String replaceOne(String text, Match match,
            String replacement, String query, boolean regex,
            boolean matchCase) {
        if (text == null || match == null || query == null || query.isEmpty()
                || match.start() < 0 || match.end() > text.length()
                || match.end() < match.start()) {
            return text;
        }
        String with = (replacement != null) ? replacement : "";
        String expanded;
        if (regex) {
            try {
                Matcher matcher =
                        compile(query, regex, matchCase).matcher(text);
                if (!matcher.find(match.start())
                        || matcher.start() != match.start()
                        || matcher.end() != match.end()) {
                    return text;
                }
                expanded = expandSpec(with, matcher);
            } catch (PatternSyntaxException | StackOverflowError err) {
                return text;
            }
        } else {
            // Literal stale guard: the span must still hold the query.
            String slice = text.substring(match.start(), match.end());
            boolean same = matchCase ? slice.equals(query)
                    : slice.equalsIgnoreCase(query);
            if (!same) {
                return text;
            }
            expanded = with;
        }
        return text.substring(0, match.start()) + expanded
                + text.substring(match.end());
    }

    /**
     * Expands {@code \}-escapes, {@code $n} and {@code ${name}} group
     * references against the matcher's current match. Tolerant by design:
     * anything unresolvable is copied through literally, so a user's stray
     * {@code $} can never throw mid-replace-all.
     */
    static String expandSpec(String spec, Matcher matcher) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = spec.length();
        while (i < n) {
            char c = spec.charAt(i);
            if (c == '\\' && i + 1 < n) {
                out.append(spec.charAt(i + 1));
                i += 2;
            } else if (c == '$' && i + 1 < n) {
                String ref = null;
                int next;
                if (spec.charAt(i + 1) == '{') {
                    int close = spec.indexOf('}', i + 2);
                    if (close < 0) {
                        out.append(c);
                        i++;
                        continue;
                    }
                    ref = spec.substring(i + 2, close);
                    next = close + 1;
                } else {
                    int j = i + 1;
                    while (j < n && Character.isDigit(spec.charAt(j))) {
                        j++;
                    }
                    if (j == i + 1) {
                        out.append(c);
                        i++;
                        continue;
                    }
                    ref = spec.substring(i + 1, j);
                    next = j;
                }
                String value = groupValue(matcher, ref);
                if (value == null) {
                    out.append(spec, i, next); // keep "$3" verbatim
                } else {
                    out.append(value);
                }
                i = next;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The group's value, or null when the reference does not resolve. */
    private static String groupValue(Matcher matcher, String ref) {
        try {
            String value = Character.isDigit(ref.charAt(0))
                    ? matcher.group(Integer.parseInt(ref))
                    : matcher.group(ref);
            return (value != null) ? value : "";
        } catch (RuntimeException rte) {
            return null;
        }
    }
}
