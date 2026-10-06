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
package org.jdesktop.lg3d.apps.search;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Pure, UI-independent helpers for the Search panel: parsing the human size and
 * recency shorthand a user types into the filter fields, formatting byte counts
 * and timestamps for the results table, and enumerating the default scopes
 * (Home / Documents / Downloads / the whole file system) offered in the scope
 * list.
 *
 * <p>Everything here is static and side-effect free (apart from a couple of
 * {@code Files.exists} probes) so it is trivially unit-testable headless, keeping
 * the Swing panel itself thin.</p>
 */
final class SearchFormat {

    /** The byte units used by both {@link #parseSize} and {@link #formatSize}. */
    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    private SearchFormat() {
    }

    /** A labelled search scope shown in the UI's scope list. */
    record Scope(String label, Path path) {
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Parses a human size such as {@code 512}, {@code 1.5K}, {@code 2MB} or
     * {@code 3 GB} into a byte count. A blank, unparseable or negative value
     * yields {@link OptionalLong#empty()}, so an unfinished keystroke in the field
     * simply means "no bound".
     */
    static OptionalLong parseSize(String text) {
        if (text == null) {
            return OptionalLong.empty();
        }
        String s = text.strip().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return OptionalLong.empty();
        }
        int cut = s.length();
        while (cut > 0 && !isDigitOrDot(s.charAt(cut - 1))) {
            cut--;
        }
        String numberPart = s.substring(0, cut).strip();
        String unitPart = s.substring(cut).strip();
        if (numberPart.isEmpty()) {
            return OptionalLong.empty();
        }
        double value;
        try {
            value = Double.parseDouble(numberPart);
        } catch (NumberFormatException ex) {
            return OptionalLong.empty();
        }
        if (value < 0 || Double.isNaN(value) || Double.isInfinite(value)) {
            return OptionalLong.empty();
        }
        long multiplier = unitMultiplier(unitPart);
        if (multiplier < 0) {
            return OptionalLong.empty();
        }
        double bytes = value * multiplier;
        if (bytes > Long.MAX_VALUE) {
            return OptionalLong.of(Long.MAX_VALUE);
        }
        return OptionalLong.of((long) bytes);
    }

    private static boolean isDigitOrDot(char c) {
        return (c >= '0' && c <= '9') || c == '.';
    }

    /** The multiplier for a unit suffix, or -1 when the suffix is unrecognised. */
    private static long unitMultiplier(String unit) {
        String u = unit.replace("b", "").replace(" ", "");
        switch (u) {
            case "":
                return 1L;                       // bare number == bytes
            case "k":
                return 1024L;
            case "m":
                return 1024L * 1024L;
            case "g":
                return 1024L * 1024L * 1024L;
            case "t":
                return 1024L * 1024L * 1024L * 1024L;
            case "p":
                return 1024L * 1024L * 1024L * 1024L * 1024L;
            default:
                return -1L;
        }
    }

    /** Formats a byte count as a short human string, e.g. {@code 1.5 MB}. */
    static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double v = bytes;
        int i = 0;
        while (v >= 1024 && i < UNITS.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, "%.1f %s", v, UNITS[i]);
    }

    /**
     * Parses a "modified within N days" field. Blank, non-integer or non-positive
     * input yields {@link OptionalInt#empty()} (meaning "no recency bound").
     */
    static OptionalInt parseDays(String text) {
        if (text == null) {
            return OptionalInt.empty();
        }
        String s = text.strip();
        if (s.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            int days = Integer.parseInt(s);
            return (days <= 0) ? OptionalInt.empty() : OptionalInt.of(days);
        } catch (NumberFormatException ex) {
            return OptionalInt.empty();
        }
    }

    /** Formats an epoch-millis timestamp for the results table; 0 renders blank. */
    static String formatTimestamp(long millis) {
        if (millis <= 0L) {
            return "";
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withZone(ZoneId.systemDefault());
        return fmt.format(Instant.ofEpochMilli(millis));
    }

    /**
     * The default scopes offered in the UI: the user's Home folder (always),
     * Documents and Downloads when they exist, and the whole file system root.
     * Never empty.
     */
    static List<Scope> defaultScopes() {
        List<Scope> scopes = new ArrayList<>();
        Path home = homePath();
        scopes.add(new Scope("Home", home));
        addIfPresent(scopes, "Documents", home.resolve("Documents"));
        addIfPresent(scopes, "Downloads", home.resolve("Downloads"));
        addIfPresent(scopes, "Desktop", home.resolve("Desktop"));
        Path root = Paths.get("/");
        if (!scopes.contains(new Scope("File system", root))) {
            scopes.add(new Scope("File system", root));
        }
        return scopes;
    }

    private static void addIfPresent(List<Scope> scopes, String label, Path path) {
        if (Files.isDirectory(path)) {
            scopes.add(new Scope(label, path));
        }
    }

    private static Path homePath() {
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            return Paths.get(home);
        }
        return Paths.get("").toAbsolutePath();
    }

    /**
     * A short, human location label for a match: the parent folder's path, or the
     * root marker when the file sits at a filesystem root.
     */
    static String locationOf(Path file) {
        if (file == null) {
            return "";
        }
        Path parent = file.getParent();
        return (parent == null) ? file.toString() : parent.toString();
    }
}
