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
package org.jdesktop.lg3d.utils.taskscheduler;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;
import java.util.Locale;
import java.util.Optional;

/**
 * A parser and evaluator for {@code crontab}-style schedules, the timing core of
 * the desktop {@link TaskScheduler}. It understands the classic five-field form
 *
 * <pre>  minute  hour  day-of-month  month  day-of-week</pre>
 *
 * and, for sub-minute jobs, the six-field form that prefixes a seconds field
 *
 * <pre>  second  minute  hour  day-of-month  month  day-of-week</pre>
 *
 * <p>Each field accepts {@code *} (any), {@code ?} (any, day fields), a single
 * value, an inclusive range {@code a-b}, a step {@code *&#47;n} or {@code a-b/n}
 * or {@code a/n}, and a comma-separated list of any of those. Months and
 * week-days also accept their three-letter English names ({@code JAN}, {@code
 * MON}), and week-day {@code 7} is an alias for {@code 0} (Sunday). The
 * convenience macros {@code @yearly}, {@code @annually}, {@code @monthly},
 * {@code @weekly}, {@code @daily}, {@code @midnight}, {@code @hourly} and
 * {@code @minutely} expand to the equivalent field expressions, and
 * {@code @reboot} marks a job that runs once whenever the scheduler starts.</p>
 *
 * <p>Following the Vixie-cron rule, when <em>both</em> the day-of-month and the
 * day-of-week fields are restricted (neither is {@code *}/{@code ?}) a time
 * matches if <em>either</em> one matches; otherwise the single restricted day
 * field governs. Instances are immutable and thread-safe. All evaluation is pure
 * and deterministic - the clock is passed in - so the whole grammar is unit
 * testable headlessly.</p>
 */
public final class CronExpression {

    /** The longest horizon searched for a next fire time (guards impossible dates). */
    private static final int MAX_SEARCH_YEARS = 5;

    private static final String[] MONTH_NAMES = {
        "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
        "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"
    };
    private static final String[] DOW_NAMES = {
        "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"
    };

    private final String expression;
    private final boolean seconds;
    private final boolean reboot;

    private final BitSet secondBits = new BitSet(60);
    private final BitSet minuteBits = new BitSet(60);
    private final BitSet hourBits = new BitSet(24);
    private final BitSet domBits = new BitSet(32);      // 1..31
    private final BitSet monthBits = new BitSet(13);    // 1..12
    private final BitSet dowBits = new BitSet(7);       // 0..6 (0 = Sunday)

    /** True when the day-of-month field was restricted (not {@code *}/{@code ?}). */
    private final boolean domRestricted;
    /** True when the day-of-week field was restricted (not {@code *}/{@code ?}). */
    private final boolean dowRestricted;

    private CronExpression(String expression, boolean seconds, boolean reboot,
            boolean domRestricted, boolean dowRestricted) {
        this.expression = expression;
        this.seconds = seconds;
        this.reboot = reboot;
        this.domRestricted = domRestricted;
        this.dowRestricted = dowRestricted;
    }

    /**
     * Parses a cron expression.
     *
     * @throws IllegalArgumentException if the expression is null, blank, has the
     *         wrong field count, or contains an out-of-range / malformed field.
     */
    public static CronExpression parse(String expr) {
        if (expr == null) {
            throw new IllegalArgumentException("cron expression is null");
        }
        String trimmed = expr.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("cron expression is blank");
        }
        if (trimmed.startsWith("@")) {
            return parseMacro(trimmed);
        }
        String[] fields = trimmed.split("\\s+");
        boolean hasSeconds = fields.length == 6;
        if (fields.length != 5 && fields.length != 6) {
            throw new IllegalArgumentException(
                    "cron expression must have 5 or 6 fields, found "
                            + fields.length + ": \"" + trimmed + "\"");
        }
        int i = 0;
        // day-of-month field index: 2 for a 5-field expression, 3 for a 6-field
        // one (which prefixes a seconds field). The last field is day-of-week.
        CronExpression c = new CronExpression(trimmed, hasSeconds, false,
                !isWildcard(fields[hasSeconds ? 3 : 2]),
                !isWildcard(fields[fields.length - 1]));
        if (hasSeconds) {
            parseInto(fields[i++], 0, 59, null, c.secondBits);
        } else {
            c.secondBits.set(0);
        }
        parseInto(fields[i++], 0, 59, null, c.minuteBits);
        parseInto(fields[i++], 0, 23, null, c.hourBits);
        parseInto(fields[i++], 1, 31, null, c.domBits);
        parseInto(fields[i++], 1, 12, MONTH_NAMES, c.monthBits);
        parseInto(fields[i], 0, 7, DOW_NAMES, c.dowBits);
        if (c.dowBits.get(7)) {          // 7 == Sunday == 0
            c.dowBits.set(0);
            c.dowBits.clear(7);
        }
        return c;
    }

    private static CronExpression parseMacro(String macro) {
        String m = macro.toLowerCase(Locale.ROOT);
        switch (m) {
            case "@yearly":
            case "@annually":
                return parse("0 0 1 1 *");
            case "@monthly":
                return parse("0 0 1 * *");
            case "@weekly":
                return parse("0 0 * * 0");
            case "@daily":
            case "@midnight":
                return parse("0 0 * * *");
            case "@hourly":
                return parse("0 * * * *");
            case "@minutely":
                return parse("* * * * *");
            case "@reboot": {
                CronExpression c = new CronExpression(macro, false, true, false, false);
                c.secondBits.set(0);
                c.minuteBits.set(0);
                c.hourBits.set(0);
                c.domBits.set(1, 32);
                c.monthBits.set(1, 13);
                c.dowBits.set(0, 7);
                return c;
            }
            default:
                throw new IllegalArgumentException("unknown cron macro: " + macro);
        }
    }

    private static boolean isWildcard(String field) {
        return field.equals("*") || field.equals("?");
    }

    /**
     * Parses one field into {@code out}. Supports {@code *}, {@code ?}, values,
     * ranges, steps and comma-separated lists of those.
     */
    private static void parseInto(String field, int min, int max,
            String[] names, BitSet out) {
        if (field == null || field.isEmpty()) {
            throw new IllegalArgumentException("empty cron field");
        }
        for (String part : field.split(",")) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("empty list element in \"" + field + "\"");
            }
            parsePart(part.trim(), min, max, names, out, field);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("cron field selects nothing: \"" + field + "\"");
        }
    }

    private static void parsePart(String part, int min, int max,
            String[] names, BitSet out, String field) {
        int step = 1;
        String range = part;
        int slash = part.indexOf('/');
        if (slash >= 0) {
            range = part.substring(0, slash);
            String stepStr = part.substring(slash + 1);
            step = parseNumber(stepStr, 1, max, field);
            if (range.isEmpty() || range.equals("*") || range.equals("?")) {
                range = min + "-" + max;
            } else if (range.indexOf('-') < 0) {
                // "a/n" means from a to the field maximum, stepping by n.
                range = range + "-" + max;
            }
        } else if (range.equals("*") || range.equals("?")) {
            range = min + "-" + max;
        }
        int dash = range.indexOf('-');
        if (dash < 0) {
            int v = parseValue(range, min, max, names, field);
            out.set(v);
            return;
        }
        int lo = parseValue(range.substring(0, dash), min, max, names, field);
        int hi = parseValue(range.substring(dash + 1), min, max, names, field);
        if (lo > hi) {
            throw new IllegalArgumentException(
                    "cron range start exceeds end in \"" + field + "\"");
        }
        for (int v = lo; v <= hi; v += step) {
            out.set(v);
        }
    }

    private static int parseValue(String token, int min, int max,
            String[] names, String field) {
        if (names != null) {
            String up = token.toUpperCase(Locale.ROOT);
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(up)) {
                    // Month names map to 1..12; dow names map to 0..6.
                    return (names == MONTH_NAMES) ? i + 1 : i;
                }
            }
        }
        return parseNumber(token, min, max, field);
    }

    private static int parseNumber(String token, int min, int max, String field) {
        try {
            int v = Integer.parseInt(token.trim());
            if (v < min || v > max) {
                throw new IllegalArgumentException("cron value " + v
                        + " out of range [" + min + ".." + max + "] in \"" + field + "\"");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "invalid cron token \"" + token + "\" in \"" + field + "\"", e);
        }
    }

    // ------------------------------------------------------------------
    // Evaluation
    // ------------------------------------------------------------------

    /** True if this is the {@code @reboot} pseudo-schedule (runs on start only). */
    public boolean isReboot() {
        return reboot;
    }

    /** True when the schedule has sub-minute (seconds) resolution. */
    public boolean hasSeconds() {
        return seconds;
    }

    /** The original expression text this was parsed from. */
    public String getExpression() {
        return expression;
    }

    /** True if {@code time} falls exactly on a scheduled instant. */
    public boolean matches(ZonedDateTime time) {
        if (reboot || time == null) {
            return false;
        }
        if (seconds && !secondBits.get(time.getSecond())) {
            return false;
        }
        if (!minuteBits.get(time.getMinute())) {
            return false;
        }
        if (!hourBits.get(time.getHour())) {
            return false;
        }
        if (!monthBits.get(time.getMonthValue())) {
            return false;
        }
        return dayMatches(time);
    }

    private boolean dayMatches(ZonedDateTime time) {
        // java.time DayOfWeek is MON=1..SUN=7; cron uses SUN=0..SAT=6.
        int cronDow = time.getDayOfWeek().getValue() % 7;
        boolean domOk = domBits.get(time.getDayOfMonth());
        boolean dowOk = dowBits.get(cronDow);
        if (domRestricted && dowRestricted) {
            return domOk || dowOk;          // Vixie cron: either field fires
        }
        if (domRestricted) {
            return domOk;
        }
        if (dowRestricted) {
            return dowOk;
        }
        return true;                        // both wildcards: any day
    }

    /**
     * The next instant strictly after {@code after} at which this expression
     * fires, or {@link Optional#empty()} if it never fires again within
     * {@value #MAX_SEARCH_YEARS} years (e.g. an impossible date such as 30
     * February) or the expression is {@code @reboot}.
     *
     * <p>For a five-field expression the result is always on a minute boundary
     * (seconds zeroed); for a six-field expression it carries seconds.</p>
     */
    public Optional<ZonedDateTime> nextFireTime(ZonedDateTime after) {
        if (reboot || after == null) {
            return Optional.empty();
        }
        // Step forward by the smallest unit we schedule on, so "next" is strict.
        ZonedDateTime t = after.withNano(0);
        t = seconds
                ? t.plusSeconds(1)
                : t.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        ZonedDateTime limit = after.plusYears(MAX_SEARCH_YEARS);
        while (t.isBefore(limit)) {
            if (!monthBits.get(t.getMonthValue())) {
                t = t.plusMonths(1).withDayOfMonth(1)
                        .withHour(0).withMinute(0).withSecond(0).withNano(0);
                continue;
            }
            if (!dayMatches(t)) {
                t = t.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
                continue;
            }
            if (!hourBits.get(t.getHour())) {
                t = t.plusHours(1).withMinute(0).withSecond(0).withNano(0);
                continue;
            }
            if (!minuteBits.get(t.getMinute())) {
                t = t.plusMinutes(1).withSecond(0).withNano(0);
                continue;
            }
            if (seconds && !secondBits.get(t.getSecond())) {
                t = t.plusSeconds(1).withNano(0);
                continue;
            }
            return Optional.of(t);
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return expression;
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof CronExpression)
                && expression.equals(((CronExpression) o).expression);
    }

    @Override
    public int hashCode() {
        return expression.hashCode();
    }
}
