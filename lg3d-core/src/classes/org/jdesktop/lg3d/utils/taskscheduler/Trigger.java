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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * When a {@link ScheduledTask} fires. Three kinds cover every desktop-scheduler
 * need while staying trivial to persist:
 *
 * <ul>
 *   <li>{@link Kind#CRON} - a {@code crontab} expression evaluated by
 *       {@link CronExpression} (including {@code @reboot}, which fires once when
 *       the scheduler starts and has no wall-clock next time).</li>
 *   <li>{@link Kind#INTERVAL} - a fixed period in milliseconds, counted forward
 *       from the moment the scheduler next evaluates it (so a job missed while
 *       the desktop was down simply runs again after one period).</li>
 *   <li>{@link Kind#ONCE} - a single absolute instant; it never fires again once
 *       that instant has passed.</li>
 * </ul>
 *
 * <p>Instances are immutable value objects. {@link #nextFireTime} is pure (the
 * reference time and zone are passed in), so trigger arithmetic is unit testable
 * headlessly.</p>
 */
public final class Trigger {

    /** The kind of schedule a {@link Trigger} represents. */
    public enum Kind { CRON, INTERVAL, ONCE }

    private final Kind kind;
    private final String cron;          // CRON only
    private final long periodMillis;    // INTERVAL only
    private final long atEpochMillis;   // ONCE only

    private Trigger(Kind kind, String cron, long periodMillis, long atEpochMillis) {
        this.kind = kind;
        this.cron = cron;
        this.periodMillis = periodMillis;
        this.atEpochMillis = atEpochMillis;
    }

    /** A cron-expression trigger. The expression is validated eagerly. */
    public static Trigger cron(String expression) {
        CronExpression.parse(expression);     // throws on a bad expression
        return new Trigger(Kind.CRON, expression.trim(), 0L, 0L);
    }

    /** A fixed-interval trigger. {@code periodMillis} must be positive. */
    public static Trigger interval(long periodMillis) {
        if (periodMillis <= 0) {
            throw new IllegalArgumentException("interval period must be positive");
        }
        return new Trigger(Kind.INTERVAL, null, periodMillis, 0L);
    }

    /** A one-shot trigger at an absolute epoch-millis instant. */
    public static Trigger once(long epochMillis) {
        return new Trigger(Kind.ONCE, null, 0L, epochMillis);
    }

    public Kind getKind() {
        return kind;
    }

    /** The cron expression for a {@link Kind#CRON} trigger, else null. */
    public String getCron() {
        return cron;
    }

    /** The period for a {@link Kind#INTERVAL} trigger, else 0. */
    public long getPeriodMillis() {
        return periodMillis;
    }

    /** The instant for a {@link Kind#ONCE} trigger, else 0. */
    public long getAtEpochMillis() {
        return atEpochMillis;
    }

    /**
     * The next instant strictly after {@code after} (in {@code zone}) at which
     * this trigger fires, or empty if it never fires again.
     */
    public Optional<ZonedDateTime> nextFireTime(ZonedDateTime after, ZoneId zone) {
        switch (kind) {
            case CRON:
                return CronExpression.parse(cron).nextFireTime(after.withZoneSameInstant(zone));
            case INTERVAL:
                return Optional.of(after.withZoneSameInstant(zone)
                        .plusNanos(periodMillis * 1_000_000L));
            case ONCE: {
                Instant at = Instant.ofEpochMilli(atEpochMillis);
                ZonedDateTime when = at.atZone(zone);
                return when.isAfter(after) ? Optional.of(when) : Optional.empty();
            }
            default:
                return Optional.empty();
        }
    }

    /** A short human description for the scheduler UI. */
    public String describe() {
        switch (kind) {
            case CRON: return "cron: " + cron;
            case INTERVAL: return "every " + humanizeMillis(periodMillis);
            case ONCE: return "once at " + Instant.ofEpochMilli(atEpochMillis).toString();
            default: return kind.name();
        }
    }

    private static String humanizeMillis(long ms) {
        if (ms % 3_600_000L == 0) {
            long h = ms / 3_600_000L;
            return h + " hour" + (h == 1 ? "" : "s");
        }
        if (ms % 60_000L == 0) {
            long m = ms / 60_000L;
            return m + " minute" + (m == 1 ? "" : "s");
        }
        if (ms % 1000L == 0) {
            long s = ms / 1000L;
            return s + " second" + (s == 1 ? "" : "s");
        }
        return ms + " ms";
    }

    @Override
    public String toString() {
        return describe();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Trigger)) {
            return false;
        }
        Trigger t = (Trigger) o;
        return kind == t.kind
                && periodMillis == t.periodMillis
                && atEpochMillis == t.atEpochMillis
                && (cron == null ? t.cron == null : cron.equals(t.cron));
    }

    @Override
    public int hashCode() {
        int h = kind.hashCode();
        h = 31 * h + (cron == null ? 0 : cron.hashCode());
        h = 31 * h + Long.hashCode(periodMillis);
        h = 31 * h + Long.hashCode(atEpochMillis);
        return h;
    }
}
