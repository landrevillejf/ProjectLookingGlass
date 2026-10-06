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
package org.jdesktop.lg3d.utils.search;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compiles a query's name pattern once into a reusable matcher that both tests
 * a file name ({@link #matches}) and grades how good that match is
 * ({@link #score}). Keeping the compilation here (rather than per file) is what
 * makes the parallel walk fast: the pattern is parsed a single time and the
 * immutable matcher is shared, read-only, across every worker thread.
 *
 * <p>An invalid glob or regex degrades to "matches nothing" instead of
 * throwing, so a typo in the search box can never crash the walk.</p>
 */
final class NameMatcher {

    /** The relevance bands, exported so scoring stays tunable and testable. */
    static final int SCORE_EXACT = 100;
    static final int SCORE_PREFIX = 70;
    static final int SCORE_CONTAINS = 50;
    static final int SCORE_PATTERN = 45;
    static final int SCORE_NONE = 0;

    private final SearchQuery.NameMode mode;
    private final boolean caseSensitive;
    private final String needle;
    private final PathMatcher glob;
    private final Pattern regex;

    private NameMatcher(SearchQuery.NameMode mode, String pattern, boolean caseSensitive) {
        this.mode = mode;
        this.caseSensitive = caseSensitive;
        String p = (pattern == null) ? "" : pattern;
        this.needle = caseSensitive ? p : p.toLowerCase();
        PathMatcher g = null;
        Pattern r = null;
        if (mode == SearchQuery.NameMode.GLOB && !p.isEmpty()) {
            try {
                g = FileSystems.getDefault().getPathMatcher("glob:" + p);
            } catch (IllegalArgumentException | UnsupportedOperationException ex) {
                g = null; // bad glob -> matches nothing
            }
        } else if (mode == SearchQuery.NameMode.REGEX && !p.isEmpty()) {
            try {
                int flags = caseSensitive ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                r = Pattern.compile(p, flags);
            } catch (PatternSyntaxException ex) {
                r = null; // bad regex -> matches nothing
            }
        }
        this.glob = g;
        this.regex = r;
    }

    /** Builds the matcher for a query's name settings. Never null. */
    static NameMatcher of(SearchQuery.NameMode mode, String pattern, boolean caseSensitive) {
        return new NameMatcher(mode == null ? SearchQuery.NameMode.SUBSTRING : mode,
                pattern, caseSensitive);
    }

    /** True when {@code name} satisfies the compiled pattern. */
    boolean matches(String name) {
        if (name == null) {
            return false;
        }
        // A blank pattern means "any name".
        if (isBlankPattern()) {
            return true;
        }
        switch (mode) {
            case GLOB:
                return glob != null && glob.matches(Paths.get(name));
            case REGEX:
                return regex != null && regex.matcher(name).find();
            case SUBSTRING:
            default:
                String hay = caseSensitive ? name : name.toLowerCase();
                return hay.contains(needle);
        }
    }

    /**
     * The name-quality component of the relevance score: exact matches outrank
     * prefix matches, which outrank mid-string substring matches; a glob/regex
     * hit earns a flat pattern score. Returns {@link #SCORE_NONE} when the name
     * does not match, so the caller can treat 0 as "not a result".
     */
    int score(String name) {
        if (!matches(name)) {
            return SCORE_NONE;
        }
        if (isBlankPattern()) {
            return SCORE_PATTERN;
        }
        if (mode == SearchQuery.NameMode.SUBSTRING) {
            String lower = caseSensitive ? name : name.toLowerCase();
            if (lower.equals(needle)) {
                return SCORE_EXACT;
            }
            if (lower.startsWith(needle)) {
                return SCORE_PREFIX;
            }
            int idx = lower.indexOf(needle);
            int penalty = (idx < 0) ? 0 : Math.min(idx, SCORE_CONTAINS / 2);
            return SCORE_CONTAINS - penalty;
        }
        // Glob / regex: we know it matched, but not "where", so grade it flat.
        return SCORE_PATTERN;
    }

    private boolean isBlankPattern() {
        return needle.isEmpty();
    }
}
