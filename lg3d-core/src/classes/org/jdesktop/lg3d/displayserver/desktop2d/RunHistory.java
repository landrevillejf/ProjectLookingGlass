/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The run dialog's most-recent-first list of previously entered commands — the
 * pure, headless model behind the Alt+F2 box's up/down recall. Adding an entry
 * promotes it to the front and drops any older duplicate, so the list never
 * holds the same string twice and never grows past {@link #MAX_ENTRIES}.
 *
 * <p>The persisted form is a single {@link String}: each entry is URL-encoded
 * (which escapes the separator and any whitespace) and joined with a newline,
 * the same "encode the whole thing under one preferences key" approach
 * {@link SessionSnapshot} uses. Decoding a blank or corrupt value yields an
 * empty history, and an individual malformed entry is skipped rather than
 * fatal, so a bad value can never stop the dialog from opening.</p>
 */
final class RunHistory {

    /** How many recent entries are kept. */
    static final int MAX_ENTRIES = 20;

    /** Separator between URL-encoded entries; never appears in encoded output. */
    private static final char ENTRY_SEP = '\n';

    private final List<String> entries;

    /** An empty history. */
    RunHistory() {
        this.entries = new ArrayList<>();
    }

    /** A history pre-seeded with {@code seed} (copied, most-recent-first). */
    private RunHistory(List<String> seed) {
        this.entries = new ArrayList<>(seed);
    }

    /**
     * Records {@code command} as the most recent entry: blank input is ignored,
     * an existing duplicate is promoted rather than repeated, and the tail is
     * trimmed back to {@link #MAX_ENTRIES}.
     */
    void add(String command) {
        if (command == null) {
            return;
        }
        String text = command.trim();
        if (text.isEmpty()) {
            return;
        }
        entries.remove(text);
        entries.add(0, text);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
    }

    /** The entries, most-recent-first, as an unmodifiable snapshot. */
    List<String> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    int size() {
        return entries.size();
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Forgets every entry. */
    void clear() {
        entries.clear();
    }

    /** The single-string persisted form; an empty history encodes to "". */
    String encode() {
        StringBuilder sb = new StringBuilder();
        for (String entry : entries) {
            if (sb.length() > 0) {
                sb.append(ENTRY_SEP);
            }
            sb.append(URLEncoder.encode(entry, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * Rebuilds a history from its {@link #encode()}d form. Null, blank or
     * corrupt input yields an empty history; a malformed entry is dropped and
     * the rest are kept, capped at {@link #MAX_ENTRIES}.
     */
    static RunHistory decode(String encoded) {
        List<String> decoded = new ArrayList<>();
        if (encoded != null && !encoded.isBlank()) {
            for (String chunk : split(encoded)) {
                if (chunk.isEmpty()) {
                    continue;
                }
                try {
                    decoded.add(URLDecoder.decode(chunk, StandardCharsets.UTF_8));
                } catch (IllegalArgumentException iae) {
                    // A malformed escape; skip this entry and keep the rest.
                    continue;
                }
                if (decoded.size() >= MAX_ENTRIES) {
                    break;
                }
            }
        }
        return new RunHistory(decoded);
    }

    /** Splits on {@link #ENTRY_SEP}, keeping trailing empties out. */
    private static List<String> split(String encoded) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < encoded.length(); i++) {
            if (encoded.charAt(i) == ENTRY_SEP) {
                chunks.add(encoded.substring(start, i));
                start = i + 1;
            }
        }
        chunks.add(encoded.substring(start));
        return chunks;
    }
}
