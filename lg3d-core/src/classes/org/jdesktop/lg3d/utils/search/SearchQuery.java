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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * An immutable description of one advanced file-system search: where to look
 * (the {@linkplain #getRoots() roots}), how names are matched
 * ({@linkplain NameMode substring / glob / regex}, case sensitivity), whether
 * file <em>contents</em> are grepped too, and the type / size / recency filters
 * that further narrow the result set.
 *
 * <p>Instances are created through {@link #builder()}; every field has a sane
 * default (the user's home folder, case-insensitive substring name match, no
 * content search, no size or date bounds, a {@value #DEFAULT_MAX_RESULTS}
 * result cap) so {@code SearchQuery.builder().build()} is a valid "everything
 * under home" query. Being immutable and side-effect free, a query is safe to
 * build on the EDT, hand to a background {@link SearchEngine}, and reuse.</p>
 *
 * @see SearchEngine
 */
public final class SearchQuery {

    /** How the {@linkplain #getNamePattern() name pattern} is interpreted. */
    public enum NameMode {
        /** The name contains the pattern as a literal substring. */
        SUBSTRING,
        /** The name matches a shell-style glob (e.g. {@code *.java}). */
        GLOB,
        /** The name matches a regular expression (partial match). */
        REGEX
    }

    /** Which node kinds a search reports. */
    public enum Kind {
        /** Both files and directories. */
        ANY,
        /** Regular files only. */
        FILES,
        /** Directories only. */
        DIRECTORIES
    }

    /** The default result cap, high enough for real use, low enough to bound memory. */
    public static final int DEFAULT_MAX_RESULTS = 5000;

    /** The largest content-grepped file, in bytes; bigger files are name-matched only. */
    public static final long MAX_CONTENT_BYTES = 8L * 1024L * 1024L;

    private final List<Path> roots;
    private final NameMode nameMode;
    private final String namePattern;
    private final boolean caseSensitive;
    private final boolean includeHidden;
    private final Kind kind;
    private final boolean contentSearch;
    private final String contentPattern;
    private final boolean contentRegex;
    private final OptionalLong minSize;
    private final OptionalLong maxSize;
    private final OptionalInt modifiedWithinDays;
    private final int maxResults;

    private SearchQuery(Builder b) {
        this.roots = Collections.unmodifiableList(new ArrayList<>(b.roots));
        this.nameMode = b.nameMode;
        this.namePattern = b.namePattern == null ? "" : b.namePattern;
        this.caseSensitive = b.caseSensitive;
        this.includeHidden = b.includeHidden;
        this.kind = b.kind;
        this.contentSearch = b.contentSearch;
        this.contentPattern = b.contentPattern == null ? "" : b.contentPattern;
        this.contentRegex = b.contentRegex;
        this.minSize = b.minSize;
        this.maxSize = b.maxSize;
        this.modifiedWithinDays = b.modifiedWithinDays;
        this.maxResults = b.maxResults;
    }

    /** A fresh builder pre-loaded with the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** The folders the walk descends from; never empty (defaults to home). */
    public List<Path> getRoots() {
        return roots;
    }

    /** How {@link #getNamePattern()} is interpreted. */
    public NameMode getNameMode() {
        return nameMode;
    }

    /** The name pattern; blank means "any name". */
    public String getNamePattern() {
        return namePattern;
    }

    /** True when name and content matching distinguish case. */
    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    /** True when dot-files and dot-folders are searched too. */
    public boolean isIncludeHidden() {
        return includeHidden;
    }

    /** Which node kinds are reported. */
    public Kind getKind() {
        return kind;
    }

    /** True when file contents are grepped for {@link #getContentPattern()}. */
    public boolean isContentSearch() {
        return contentSearch;
    }

    /** The content pattern; only meaningful when {@link #isContentSearch()}. */
    public String getContentPattern() {
        return contentPattern;
    }

    /** True when the content pattern is a regex, false for a literal substring. */
    public boolean isContentRegex() {
        return contentRegex;
    }

    /** The inclusive minimum file size in bytes, if bounded. */
    public OptionalLong getMinSize() {
        return minSize;
    }

    /** The inclusive maximum file size in bytes, if bounded. */
    public OptionalLong getMaxSize() {
        return maxSize;
    }

    /** Only report nodes modified within this many days, if bounded. */
    public OptionalInt getModifiedWithinDays() {
        return modifiedWithinDays;
    }

    /** The hard cap on reported matches. */
    public int getMaxResults() {
        return maxResults;
    }

    @Override
    public String toString() {
        return "SearchQuery[roots=" + roots + ", nameMode=" + nameMode
                + ", name=" + namePattern + ", case=" + caseSensitive
                + ", hidden=" + includeHidden + ", kind=" + kind
                + ", content=" + (contentSearch ? contentPattern : "off")
                + ", max=" + maxResults + "]";
    }

    /**
     * A mutable accumulator for {@link SearchQuery}. Setters return {@code this}
     * for chaining; {@link #build()} snapshots the current state into an
     * immutable query. Null roots/patterns and non-positive caps are coerced to
     * safe values so a partially-filled builder still produces a usable query.
     */
    public static final class Builder {
        private final List<Path> roots = new ArrayList<>();
        private NameMode nameMode = NameMode.SUBSTRING;
        private String namePattern = "";
        private boolean caseSensitive;
        private boolean includeHidden;
        private Kind kind = Kind.ANY;
        private boolean contentSearch;
        private String contentPattern = "";
        private boolean contentRegex;
        private OptionalLong minSize = OptionalLong.empty();
        private OptionalLong maxSize = OptionalLong.empty();
        private OptionalInt modifiedWithinDays = OptionalInt.empty();
        private int maxResults = DEFAULT_MAX_RESULTS;

        private Builder() {
        }

        /** Replaces the roots with {@code paths}; null entries are dropped. */
        public Builder roots(List<Path> paths) {
            roots.clear();
            if (paths != null) {
                for (Path p : paths) {
                    if (p != null) {
                        roots.add(p);
                    }
                }
            }
            return this;
        }

        /** Adds a single root; a null or blank string is ignored. */
        public Builder addRoot(Path root) {
            if (root != null) {
                roots.add(root);
            }
            return this;
        }

        /** Adds a root parsed from {@code path}; blank/unparseable is ignored. */
        public Builder addRoot(String path) {
            if (path != null && !path.isBlank()) {
                try {
                    roots.add(Paths.get(path.trim()));
                } catch (RuntimeException ignored) {
                    // An invalid path string simply does not become a root.
                }
            }
            return this;
        }

        public Builder nameMode(NameMode mode) {
            this.nameMode = (mode == null) ? NameMode.SUBSTRING : mode;
            return this;
        }

        public Builder namePattern(String pattern) {
            this.namePattern = (pattern == null) ? "" : pattern;
            return this;
        }

        public Builder caseSensitive(boolean value) {
            this.caseSensitive = value;
            return this;
        }

        public Builder includeHidden(boolean value) {
            this.includeHidden = value;
            return this;
        }

        public Builder kind(Kind value) {
            this.kind = (value == null) ? Kind.ANY : value;
            return this;
        }

        public Builder contentSearch(boolean value) {
            this.contentSearch = value;
            return this;
        }

        public Builder contentPattern(String pattern) {
            this.contentPattern = (pattern == null) ? "" : pattern;
            return this;
        }

        public Builder contentRegex(boolean value) {
            this.contentRegex = value;
            return this;
        }

        public Builder minSize(OptionalLong bytes) {
            this.minSize = (bytes == null) ? OptionalLong.empty() : bytes;
            return this;
        }

        public Builder maxSize(OptionalLong bytes) {
            this.maxSize = (bytes == null) ? OptionalLong.empty() : bytes;
            return this;
        }

        public Builder modifiedWithinDays(OptionalInt days) {
            this.modifiedWithinDays = (days == null) ? OptionalInt.empty() : days;
            return this;
        }

        public Builder maxResults(int cap) {
            this.maxResults = (cap <= 0) ? DEFAULT_MAX_RESULTS : cap;
            return this;
        }

        /** Snapshots this builder into an immutable {@link SearchQuery}. */
        public SearchQuery build() {
            if (roots.isEmpty()) {
                roots.add(defaultRoot());
            }
            return new SearchQuery(this);
        }

        /** The user's home folder, or the working directory if it is unknown. */
        private static Path defaultRoot() {
            String home = System.getProperty("user.home");
            if (home != null && !home.isBlank()) {
                return Paths.get(home);
            }
            return Paths.get("").toAbsolutePath();
        }
    }
}
