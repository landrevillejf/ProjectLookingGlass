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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An immutable, ordered list of the {@link WindowRecord}s that make up a saved
 * 2D-desktop session, plus the text form it is persisted in.
 *
 * <p>The encoding is a single {@code String} so it fits one preferences key:
 * each record's nine fields are URL-encoded (which escapes any delimiter) and
 * joined with {@code |}, and the records are joined with {@code ;}. Decoding is
 * deliberately tolerant — a null, blank or corrupt value yields
 * {@link #EMPTY}, and an individual malformed record is skipped rather than
 * failing the whole restore — so a damaged session can never stop the desktop
 * from starting.</p>
 *
 * <p>Pure data and text handling (no Swing, no Java 3D, no filesystem), so the
 * round-trip and the corrupt-input guards are unit-testable headless.</p>
 */
final class SessionSnapshot {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /** The empty session, restored when nothing (or nothing usable) was saved. */
    static final SessionSnapshot EMPTY =
            new SessionSnapshot(Collections.emptyList());

    /** Separates the nine fields of one record. */
    private static final char FIELD_SEP = '|';

    /** Separates one record from the next. */
    private static final char RECORD_SEP = ';';

    /** The number of fields encoded per record. */
    private static final int FIELD_COUNT = 9;

    private final List<WindowRecord> windows;

    SessionSnapshot(List<WindowRecord> windows) {
        List<WindowRecord> copy = new ArrayList<>();
        if (windows != null) {
            for (WindowRecord record : windows) {
                if (record != null) {
                    copy.add(record);
                }
            }
        }
        this.windows = Collections.unmodifiableList(copy);
    }

    /** The persisted windows, in the order they were captured. Never null. */
    List<WindowRecord> windows() {
        return windows;
    }

    boolean isEmpty() {
        return windows.isEmpty();
    }

    int size() {
        return windows.size();
    }

    /**
     * The single-string persisted form of this snapshot. {@link #EMPTY} encodes
     * to the empty string.
     */
    String encode() {
        StringBuilder sb = new StringBuilder();
        for (WindowRecord record : windows) {
            if (sb.length() > 0) {
                sb.append(RECORD_SEP);
            }
            sb.append(encodeRecord(record));
        }
        return sb.toString();
    }

    /** URL-encodes a record's nine fields and joins them with {@code |}. */
    private static String encodeRecord(WindowRecord record) {
        String[] fields = {
            record.appName(),
            record.command(),
            record.iconResource() == null ? "" : record.iconResource(),
            Integer.toString(record.x()),
            Integer.toString(record.y()),
            Integer.toString(record.width()),
            Integer.toString(record.height()),
            Boolean.toString(record.iconified()),
            Boolean.toString(record.maximized()),
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(FIELD_SEP);
            }
            sb.append(URLEncoder.encode(fields[i], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * Parses the persisted form back into a snapshot. Never throws: null, blank
     * or corrupt input yields {@link #EMPTY}, and a malformed record is dropped
     * while the well-formed ones around it survive.
     */
    static SessionSnapshot decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return EMPTY;
        }
        List<WindowRecord> records = new ArrayList<>();
        for (String chunk : split(encoded, RECORD_SEP)) {
            if (chunk.isEmpty()) {
                continue;
            }
            WindowRecord record = parseRecord(chunk);
            if (record != null) {
                records.add(record);
            }
        }
        return records.isEmpty() ? EMPTY : new SessionSnapshot(records);
    }

    /** Parses one {@code |}-delimited record, or null if it is malformed. */
    private static WindowRecord parseRecord(String chunk) {
        String[] fields = split(chunk, FIELD_SEP);
        if (fields.length != FIELD_COUNT) {
            logger.log(Level.FINE, "Skipping a malformed session record: {0}", chunk);
            return null;
        }
        try {
            String appName = decodeField(fields[0]);
            String command = decodeField(fields[1]);
            String icon = decodeField(fields[2]);
            int x = Integer.parseInt(fields[3]);
            int y = Integer.parseInt(fields[4]);
            int width = Integer.parseInt(fields[5]);
            int height = Integer.parseInt(fields[6]);
            boolean iconified = Boolean.parseBoolean(fields[7]);
            boolean maximized = Boolean.parseBoolean(fields[8]);
            return new WindowRecord(appName, command,
                    icon.isEmpty() ? null : icon,
                    x, y, width, height, iconified, maximized);
        } catch (RuntimeException e) {
            // NumberFormatException from a bad int, or IllegalArgumentException
            // from WindowRecord's own validation: drop just this record.
            logger.log(Level.FINE, "Skipping an unreadable session record: " + chunk, e);
            return null;
        }
    }

    /**
     * Splits on a literal separator (not a regex), returning trailing-empty-free
     * tokens the way {@code String.split} would for a well-formed record. The
     * field count is what {@link #parseRecord} validates against, so a record
     * missing its trailing fields is rejected there.
     */
    private static String[] split(String value, char separator) {
        List<String> tokens = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == separator) {
                tokens.add(value.substring(start, i));
                start = i + 1;
            }
        }
        tokens.add(value.substring(start));
        return tokens.toArray(new String[0]);
    }

    private static String decodeField(String field) {
        return URLDecoder.decode(field, StandardCharsets.UTF_8);
    }
}
