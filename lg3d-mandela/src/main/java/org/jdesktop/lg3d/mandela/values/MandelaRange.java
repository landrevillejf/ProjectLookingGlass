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
 * ({@code 8..0 step -2}) to the same nine-value answer an upward range gets,
 * 8, 6, 4, 2, 0. The count is measured <em>along the stride</em>, never off the
 * bounds, which is what keeps an inclusive end included whichever way it points.
 * A range whose direction contradicts its bounds is empty, which is the same rule
 * {@code 10..1} already follows, so there is no range that means two different
 * things depending on how it is read.</p>
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

    /**
     * @return the number of values the range produces, zero when it is empty, and
     *         {@link Long#MAX_VALUE} as "more than a long can count" when the
     *         bounds are too wide to measure
     */
    public long size() {
        // The distance between the bounds, signed in the direction the walk goes.
        // Measuring that instead of "the span toward to" is the fix: an inclusive
        // end owes the walk one more step along the *stride*, so 8..0 step -1 is
        // nine values ending at 0 and not seven ending at 2.
        long travelled;
        try {
            travelled = Math.subtractExact(to, from);
        } catch (ArithmeticException unmeasurable) {
            return Long.MAX_VALUE;
        }
        if (travelled == 0L) {
            // Coincident bounds: the start is one value when it is also the end.
            return exclusive ? 0L : 1L;
        }
        if (Long.signum(travelled) != Long.signum(step)) {
            return 0L;
        }
        if (travelled == Long.MIN_VALUE) {
            // The one distance Math.abs cannot measure: MIN_VALUE to 0 is 2^63
            // units, one more than a long counts, in either direction.
            return Long.MAX_VALUE;
        }
        long distance = Math.abs(travelled);
        if (exclusive) {
            // Ceil division written as (n - 1) / d + 1 so a wide range cannot
            // overflow the way magnitude + stride - 1 would.
            return (distance - 1L) / Math.abs(step) + 1L;
        }
        if (distance == Long.MAX_VALUE && Math.abs(step) == 1L) {
            return Long.MAX_VALUE; // MAX_VALUE + 1 values is not a count a long holds
        }
        return distance / Math.abs(step) + 1L;
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
