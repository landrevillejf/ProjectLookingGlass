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
package org.jdesktop.lg3d.ftpclient.net;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses raw FTP directory-listing lines into {@link RemoteEntry} values.
 *
 * <p>Apache Commons Net parses the common {@code LIST} dialects itself, but it
 * returns an {@code FTPFile} with a {@code null} name for any line its bundled
 * parsers do not recognize, alongside the untouched {@code getRawListing()}. This
 * class is the fallback for those lines and the parser for {@code MLSD} output:
 * it is pure (no I/O, no state) and therefore directly unit-tested against
 * representative Unix, Windows/NT and machine-readable listings.</p>
 *
 * <p>Timestamps carry no timezone in {@code LIST}, so they are interpreted as
 * UTC for determinism; {@code MLSD} {@code modify} facts are UTC by RFC 3659.
 * A listing whose time-only date ({@code Mon dd HH:mm}) resolves into the future
 * is assumed to be from the previous year, matching the usual client heuristic.</p>
 */
public final class ListParser {

    // Unix: perms links owner group size month day (year|time) name
    private static final Pattern UNIX = Pattern.compile(
            "^([dl\\-bcps][rwxsStT\\-]{9})\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+"
            + "([A-Za-z]{3}\\s+\\d{1,2}\\s+(?:\\d{1,2}:\\d{2}|\\d{4}))\\s+(.+)$");

    // Windows/NT: date time (<DIR>|size) name
    private static final Pattern WINDOWS = Pattern.compile(
            "^(\\d{2}-\\d{2}-\\d{2,4})\\s+(\\d{1,2}:\\d{2}[APap][Mm])\\s+(<DIR>|\\d+)\\s+(.+)$");

    private static final String UTC = "UTC";

    private ListParser() {
    }

    /**
     * Parses a single raw listing line.
     *
     * @param line one line of {@code LIST} or {@code MLSD} output
     * @return the entry, or {@code null} when the line is blank, a {@code total}
     *         header, a {@code .}/{@code ..} navigation row, or unparseable
     */
    public static RemoteEntry parse(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("total ")) {
            return null;
        }
        RemoteEntry entry = parseMlsd(trimmed);
        if (entry == null) {
            entry = parseUnix(trimmed);
        }
        if (entry == null) {
            entry = parseWindows(trimmed);
        }
        if (entry != null && entry.isSelfOrParent()) {
            return null;
        }
        return entry;
    }

    /**
     * Parses every line of a listing, dropping blanks and unparseable rows.
     *
     * @param lines the raw listing lines
     * @return the parsed entries, never {@code null}
     */
    public static List<RemoteEntry> parseAll(Collection<String> lines) {
        List<RemoteEntry> result = new ArrayList<>();
        if (lines == null) {
            return result;
        }
        for (String line : lines) {
            RemoteEntry e = parse(line);
            if (e != null) {
                result.add(e);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // MLSD / MLST (RFC 3659): "type=file;size=4096;modify=...; name"
    // ------------------------------------------------------------------

    private static RemoteEntry parseMlsd(String line) {
        int sp = line.indexOf(' ');
        if (sp <= 0 || line.charAt(0) == ' ') {
            return null;
        }
        String factPart = line.substring(0, sp);
        // Facts are semicolon-separated key=value with no spaces; require at
        // least a recognizable "type=" or "size=" to avoid mistaking a Unix
        // listing's first token for facts.
        String lower = factPart.toLowerCase(Locale.ROOT);
        if (!lower.contains("type=") && !lower.contains("size=") && !lower.contains("modify=")) {
            return null;
        }
        String name = line.substring(sp + 1).trim();
        if (name.isEmpty()) {
            return null;
        }
        boolean dir = false;
        long size = RemoteEntry.UNKNOWN_SIZE;
        long mtime = 0L;
        boolean skip = false;
        for (String fact : factPart.split(";")) {
            int eq = fact.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = fact.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = fact.substring(eq + 1).trim();
            switch (key) {
                case "type" -> {
                    String t = value.toLowerCase(Locale.ROOT);
                    if (t.equals("cdir") || t.equals("pdir")) {
                        skip = true;
                    } else if (t.startsWith("dir") || t.contains("=dir")) {
                        dir = true;
                    }
                }
                case "size" -> size = parseLong(value, RemoteEntry.UNKNOWN_SIZE);
                case "modify" -> mtime = parseMlsdTime(value);
                default -> {
                    // perm, UNIX.mode, unique, media-type: not needed here.
                }
            }
        }
        if (skip) {
            return null;
        }
        // Some servers append a trailing slash to directory names in MLSD.
        if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
            dir = true;
        }
        return new RemoteEntry(name, dir ? RemoteEntry.UNKNOWN_SIZE : size, mtime, dir, "", null);
    }

    private static long parseMlsdTime(String value) {
        // modify=YYYYMMDDHHMMSS[.sss]; ignore any fractional seconds.
        String v = value;
        int dot = v.indexOf('.');
        if (dot > 0) {
            v = v.substring(0, dot);
        }
        if (v.length() < 14) {
            return 0L;
        }
        return parseDate(v.substring(0, 14), "yyyyMMddHHmmss");
    }

    // ------------------------------------------------------------------
    // Unix LIST: "drwxr-xr-x 2 owner group 4096 Jan 01 2026 name"
    // ------------------------------------------------------------------

    private static RemoteEntry parseUnix(String line) {
        Matcher m = UNIX.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String perms = m.group(1);
        long size = parseLong(m.group(2), RemoteEntry.UNKNOWN_SIZE);
        long mtime = parseUnixDate(m.group(3));
        String name = m.group(4);
        char type = perms.charAt(0);
        boolean dir = (type == 'd');
        String linkTarget = null;
        if (type == 'l') {
            int arrow = name.indexOf(" -> ");
            if (arrow > 0) {
                linkTarget = name.substring(arrow + 4).trim();
                name = name.substring(0, arrow).trim();
            }
        }
        // Strip the 10-char permission block's leading type char for display.
        String display = perms.substring(1);
        return new RemoteEntry(name, dir ? RemoteEntry.UNKNOWN_SIZE : size, mtime,
                dir, display, linkTarget);
    }

    private static long parseUnixDate(String field) {
        // Either "MMM dd yyyy" or "MMM dd HH:mm" (no year -> assume this year).
        long withYear = parseDate(field, "MMM dd yyyy");
        if (withYear != 0L) {
            return withYear;
        }
        long thisYear = parseDate(field, "MMM dd HH:mm");
        if (thisYear == 0L) {
            return 0L;
        }
        // A parsed time-only date defaults to 1970; rebase onto the current year
        // and roll back a year when that would land in the future.
        try {
            Date now = new Date();
            java.util.Calendar cal = java.util.Calendar.getInstance(TimeZone.getTimeZone(UTC));
            cal.setTime(now);
            int year = cal.get(java.util.Calendar.YEAR);
            long rebased = parseDate(field + " " + year, "MMM dd HH:mm yyyy");
            if (rebased > now.getTime()) {
                rebased = parseDate(field + " " + (year - 1), "MMM dd HH:mm yyyy");
            }
            return rebased;
        } catch (RuntimeException e) {
            return thisYear;
        }
    }

    // ------------------------------------------------------------------
    // Windows / NT LIST: "01-01-26 12:00PM <DIR> name"
    // ------------------------------------------------------------------

    private static RemoteEntry parseWindows(String line) {
        Matcher m = WINDOWS.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String date = m.group(1);
        String time = m.group(2).toUpperCase(Locale.ROOT);
        String sizeOrDir = m.group(3);
        String name = m.group(4).trim();
        boolean dir = "<DIR>".equalsIgnoreCase(sizeOrDir);
        long size = dir ? RemoteEntry.UNKNOWN_SIZE : parseLong(sizeOrDir, RemoteEntry.UNKNOWN_SIZE);
        long mtime = parseWindowsDate(date, time);
        return new RemoteEntry(name, size, mtime, dir, "", null);
    }

    private static long parseWindowsDate(String date, String time) {
        long fourDigit = parseDate(date + " " + time, "MM-dd-yyyy hh:mma");
        if (fourDigit != 0L) {
            return fourDigit;
        }
        return parseDate(date + " " + time, "MM-dd-yy hh:mma");
    }

    // ------------------------------------------------------------------
    // shared helpers
    // ------------------------------------------------------------------

    private static SimpleDateFormat format(String pattern) {
        SimpleDateFormat fmt = new SimpleDateFormat(pattern, Locale.ENGLISH);
        fmt.setTimeZone(TimeZone.getTimeZone(UTC));
        fmt.setLenient(false);
        return fmt;
    }

    private static long parseDate(String value, String pattern) {
        try {
            ParsePosition pos = new ParsePosition(0);
            Date date = format(pattern).parse(value, pos);
            // Require the whole field to be consumed: DateFormat.parse(String)
            // silently ignores trailing text, so a time-only Unix date such as
            // "Jan 01 12:30" would otherwise be misread by "MMM dd yyyy" as year
            // 12 with the ":30" dropped. A partial parse returns 0 so the caller
            // falls through to the next pattern.
            if (date == null || pos.getIndex() != value.length()) {
                return 0L;
            }
            return date.getTime();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
