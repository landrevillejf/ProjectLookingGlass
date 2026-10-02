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
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;

/**
 * One pinned quick-launch entry on the 2D desktop's taskbar, plus the text form
 * the pinned list is persisted in.
 *
 * <p>An entry remembers just enough to relaunch and redraw an application: its
 * display {@code name}, its start-menu {@code command} (the launch identity) and
 * its optional classpath {@code iconResource}. The command is the key the
 * {@link QuickLaunchModel} de-duplicates and un-pins on; the name and icon are a
 * fallback for an entry whose command is no longer in the start-menu model (an
 * external command the user pinned), so a pin survives even when the menu cannot
 * refresh it.</p>
 *
 * <p>The list encoding mirrors {@link SessionSnapshot}: each entry's three
 * fields are URL-encoded (which escapes any delimiter) and joined with
 * {@code |}, and the entries are joined with {@code ;}, so the whole list fits
 * one preferences key. Decoding is deliberately tolerant &mdash; a null, blank
 * or corrupt value yields an empty list and a malformed entry is skipped rather
 * than failing the whole restore &mdash; so a damaged pin list can never stop the
 * desktop from starting. Pure data and text handling (no Swing, no Java 3D, no
 * filesystem), so the round-trip and the corrupt-input guards are unit-testable
 * headless.</p>
 */
final class QuickLaunchEntry {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /** Separates the three fields of one entry. */
    private static final char FIELD_SEP = '|';

    /** Separates one entry from the next. */
    private static final char RECORD_SEP = ';';

    /** The number of fields encoded per entry. */
    private static final int FIELD_COUNT = 3;

    private final String name;
    private final String command;
    private final String iconResource;

    QuickLaunchEntry(String name, String command, String iconResource) {
        this.name = (name == null) ? "" : name;
        this.command = (command == null) ? "" : command;
        this.iconResource = iconResource;
    }

    /** Builds an entry from a start-menu item. */
    static QuickLaunchEntry of(ItemSpec item) {
        return new QuickLaunchEntry(item.getName(), item.getCommand(),
                item.getIconResource());
    }

    String name() {
        return name;
    }

    /** The launch identity; never null (may be blank for a malformed entry). */
    String command() {
        return command;
    }

    String iconResource() {
        return iconResource;
    }

    /** This entry as a start-menu item, so it can be launched and drawn. */
    ItemSpec toItemSpec() {
        return new ItemSpec(name, command, null, null, iconResource);
    }

    /**
     * The single-string persisted form of {@code entries}. An empty or null list
     * encodes to the empty string.
     */
    static String encodeList(List<QuickLaunchEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (QuickLaunchEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(RECORD_SEP);
            }
            sb.append(encodeEntry(entry));
        }
        return sb.toString();
    }

    /** URL-encodes an entry's three fields and joins them with {@code |}. */
    private static String encodeEntry(QuickLaunchEntry entry) {
        String[] fields = {
            entry.name(),
            entry.command(),
            entry.iconResource() == null ? "" : entry.iconResource(),
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
     * Parses the persisted form back into entries. Never throws: null or blank
     * input yields an empty list, and a malformed entry is dropped while the
     * well-formed ones around it survive. An entry with a blank command is
     * dropped too, since it could never launch.
     */
    static List<QuickLaunchEntry> decodeList(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Collections.emptyList();
        }
        List<QuickLaunchEntry> entries = new ArrayList<>();
        for (String chunk : split(encoded, RECORD_SEP)) {
            if (chunk.isEmpty()) {
                continue;
            }
            QuickLaunchEntry entry = parseEntry(chunk);
            if (entry != null && !entry.command().isBlank()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /** Parses one {@code |}-delimited entry, or null if it is malformed. */
    private static QuickLaunchEntry parseEntry(String chunk) {
        String[] fields = split(chunk, FIELD_SEP);
        if (fields.length != FIELD_COUNT) {
            logger.log(Level.FINE, "Skipping a malformed quick-launch entry: {0}", chunk);
            return null;
        }
        try {
            String name = decodeField(fields[0]);
            String command = decodeField(fields[1]);
            String icon = decodeField(fields[2]);
            return new QuickLaunchEntry(name, command, icon.isEmpty() ? null : icon);
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "Skipping an unreadable quick-launch entry: " + chunk, e);
            return null;
        }
    }

    /**
     * Splits on a literal separator (not a regex). The field count is what
     * {@link #parseEntry} validates against, so an entry missing its trailing
     * fields is rejected there.
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

    @Override
    public String toString() {
        return "QuickLaunchEntry[" + name + " -> " + command + "]";
    }
}
