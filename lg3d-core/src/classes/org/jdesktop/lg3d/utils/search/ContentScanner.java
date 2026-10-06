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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Greps the <em>contents</em> of a file for a content search. Pure and static so
 * it is trivially testable against temp files with no desktop, threads or AWT.
 *
 * <p>Robustness is the priority: a file that cannot be opened (permissions, a
 * dangling symlink, a vanished temp file) yields no hits rather than an
 * exception; a binary file is detected by a NUL byte in its first chunk and
 * skipped so images and executables never flood the results with garbage; very
 * large files are capped by {@code maxBytes}; and malformed byte sequences are
 * replaced rather than failing the read, so non-UTF-8 text still scans.</p>
 */
final class ContentScanner {

    /** How many leading bytes are sniffed for a NUL to classify a file as binary. */
    static final int SNIFF_BYTES = 8192;

    /** The default per-file hit cap, enough for context without ballooning memory. */
    static final int DEFAULT_MAX_HITS = 50;

    /** The default snippet length in characters. */
    static final int DEFAULT_SNIPPET_LEN = 160;

    private ContentScanner() {
    }

    /**
     * Compiles the content pattern into a reusable per-line predicate. A blank
     * pattern matches every line (so "content search on, no pattern" behaves
     * like "any non-empty text file"); an invalid regex degrades to matching
     * nothing rather than throwing.
     */
    static Predicate<String> lineMatcher(String pattern, boolean regex, boolean caseSensitive) {
        String p = (pattern == null) ? "" : pattern;
        if (p.isEmpty()) {
            return line -> true;
        }
        if (regex) {
            try {
                int flags = caseSensitive ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                Pattern compiled = Pattern.compile(p, flags);
                return line -> line != null && compiled.matcher(line).find();
            } catch (PatternSyntaxException ex) {
                return line -> false;
            }
        }
        String needle = caseSensitive ? p : p.toLowerCase();
        return line -> {
            if (line == null) {
                return false;
            }
            String hay = caseSensitive ? line : line.toLowerCase();
            return hay.contains(needle);
        };
    }

    /**
     * Scans {@code file} line by line, returning up to {@code maxHits} matching
     * {@link ContentHit}s. Returns an empty list for a directory, a binary file,
     * a file larger than {@code maxBytes}, or any I/O error.
     */
    static List<ContentHit> scan(Path file, Predicate<String> lineMatcher,
                                 long maxBytes, int maxHits, int snippetLen) {
        List<ContentHit> hits = new ArrayList<>();
        if (file == null || lineMatcher == null) {
            return hits;
        }
        try {
            if (!Files.isRegularFile(file)) {
                return hits;
            }
            if (maxBytes > 0 && Files.size(file) > maxBytes) {
                return hits;
            }
            if (!looksTextual(file)) {
                return hits;
            }
            int cap = (maxHits <= 0) ? DEFAULT_MAX_HITS : maxHits;
            int len = (snippetLen <= 0) ? DEFAULT_SNIPPET_LEN : snippetLen;
            try (InputStream in = Files.newInputStream(file);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                int number = 0;
                while ((line = reader.readLine()) != null && hits.size() < cap) {
                    number++;
                    if (lineMatcher.test(line)) {
                        hits.add(new ContentHit(number, snippet(line, len)));
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            // An unreadable file simply contributes no hits.
            return new ArrayList<>();
        }
        return hits;
    }

    /** True when the file's first chunk holds no NUL byte (a text heuristic). */
    static boolean looksTextual(Path file) {
        byte[] buf = new byte[SNIFF_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.readNBytes(buf, 0, buf.length);
            for (int i = 0; i < read; i++) {
                if (buf[i] == 0) {
                    return false;
                }
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    /** Trims and length-caps a matching line into a single-line preview. */
    static String snippet(String line, int maxLen) {
        if (line == null) {
            return "";
        }
        String trimmed = line.strip();
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen) + "\u2026";
    }
}
