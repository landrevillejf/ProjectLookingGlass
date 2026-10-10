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
package org.jdesktop.lg3d.mandela.values;

/**
 * A Mandela range: {@code a..b} includes {@code b}, {@code a..<b} does not, and
 * {@code a..b step s} produces every {@code s}-th value.
 *
 * <p>A range is a value, not a loop. It can be bound to a variable, stored in a
 * map, passed to a function, tested with {@code in}, and iterated by
 * {@code for (i in 1..10)}. It never materialises its elements, so
 * {@code 0..1000000} costs the same as {@code 0..10}.</p>
 *
 * <p>Ranges are integral. A {@code Double} bound makes the comparison rules
 * unresolvable at the edges (which of 0.1, 0.2, 0.3 fall inside?), so Mandela
 * requires integers and the compiler enforces it.</p>
 *
 * <p>The stride is written the way Kotlin writes it, because a range is one of the
 * places where the two languages are meant to feel the same to a host developer:
 * {@code 0..8 step 2} gives 0, 2, 4, 6, 8, and a negative stride walks down
 * ({@code 8..0 step -2}). A range whose direction contradicts its bounds is empty,
 * which is the same rule {@code 10..1} already follows, so there is no range that
 * means two different things depending on how it is read.</p>
 *
 * @param from      the first value
 * @param to        the boundary
 * @param exclusive true when {@code to} itself is not included
 * @param step      the stride, never zero; negative walks downward
 */
public record MandelaRange(long from, long to, boolean exclusive, long step) {

    /** Guards the invariant the counting rules below divide by. */
    public MandelaRange {
        if (step == 0L) {
            throw new IllegalArgumentException("a range step must not be zero");
        }
    }

    /** @return the number of values the range produces, zero when it is empty */
    public long size() {
        long span = span();
        if (span == 0L || Long.signum(span) != Long.signum(step)) {
            return 0L;
        }
        // Ceil division written as (n - 1) / d + 1 so a wide range cannot overflow
        // the way magnitude + stride - 1 would.
        return (Math.abs(span) - 1L) / Math.abs(step) + 1L;
    }

    /** @return the number of unit values between the bounds, signed toward {@code to} */
    private long span() {
        // Long.MAX_VALUE as an inclusive end cannot be represented as a count.
        if (!exclusive && to == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return exclusive ? to - from : to - from + 1L;
    }

    /** @return true when the range holds no values at all */
    public boolean isEmpty() {
        return size() == 0L;
    }

    /** @return the last value the range actually produces, or {@code from} when empty */
    public long last() {
        long count = size();
        if (count == 0L) {
            return from;
        }
        return from + (count - 1L) * step;
    }

    /**
     * @param value the candidate, integral only
     * @return true when {@code value} is produced by this range
     */
    public boolean contains(long value) {
        if (step > 0L) {
            if (value < from || (exclusive ? value >= to : value > to)) {
                return false;
            }
        } else if (value > from || (exclusive ? value <= to : value < to)) {
            return false;
        }
        // Only the remainder matters, and Java's remainder keeps the dividend's sign,
        // so a downward stride tests zero exactly the same way an upward one does.
        return (value - from) % step == 0L;
    }

    /** @return the inclusive form {@code from..to} */
    public static MandelaRange inclusive(long start, long end) {
        return new MandelaRange(start, end, false, 1L);
    }

    /** @return the exclusive form {@code from..<to} */
    public static MandelaRange exclusive(long start, long end) {
        return new MandelaRange(start, end, true, 1L);
    }

    /**
     * @param start     the first value
     * @param end       the boundary
     * @param exclusive true when {@code end} itself is not included
     * @param stride    the step clause, which the parser has already checked is not 0
     * @return the range a {@code start..end step stride} expression denotes
     */
    public static MandelaRange strided(long start, long end, boolean exclusive,
            long stride) {
        return new MandelaRange(start, end, exclusive, stride);
    }

    /**
     * @return the display form {@code from..to}, plus {@code step s} whenever the
     *         stride is not the default, so a printed range can be pasted back in
     */
    @Override
    public String toString() {
        String bounds = from + (exclusive ? "..<" : "..") + to;
        return step == 1L ? bounds : bounds + " step " + step;
    }
}
