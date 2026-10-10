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

import java.util.List;

/**
 * The cursor a {@code for} loop drives.
 *
 * <p>One object covers lists, maps, ranges, strings and enum constants, so the
 * machine needs a single trio of opcodes &mdash; {@code NEW_ITER},
 * {@code ITER_NEXT} and {@code ITER_VALUE} &mdash; instead of a specialised loop
 * shape per container. The cursor is deliberately <em>advance then peek</em>:
 * {@link #advance()} moves to the next element and reports whether there was one,
 * and the element stays available until the next advance, which is what lets a
 * destructuring body read the same element twice without the iterator having to
 * support look-back.</p>
 *
 * <p>A map iterates its keys, so {@code for (k in map)} reads the way a Java
 * {@code keySet()} loop does; {@code for ((k, v) in map)} asks the machine for the
 * key/value pair through {@link #key()} and {@link #value()}.</p>
 *
 * <p>Not thread-safe, and it reflects the container as it is: mutating a list
 * while looping it moves the cursor over the new contents, exactly as a
 * {@code java.util.Iterator} would.</p>
 */
public final class MandelaIterator {

    private final Object source;
    private final boolean pairs;
    private int index;
    private Object key;
    private Object value;
    private boolean started;

    private MandelaIterator(Object source, boolean pairs) {
        this.source = source;
        this.pairs = pairs;
    }

    /**
     * Creates a cursor over any iterable value.
     *
     * @param value the list, map, range, string, enum or null to walk
     * @return the cursor
     * @throws MandelaError when the value is not something Mandela can iterate
     */
    public static MandelaIterator of(Object value) {
        if (value instanceof MandelaList || value instanceof MandelaRange
                || value instanceof String || value instanceof MandelaInstance
                || value instanceof MandelaClass) {
            return new MandelaIterator(value, false);
        }
        if (value instanceof MandelaMap) {
            return new MandelaIterator(value, true);
        }
        if (value == null) {
            // Iterating null is the empty sequence, which is what keeps a
            // `for (x in maybeMissing)` from needing a guard.
            return new MandelaIterator(new MandelaList(), false);
        }
        throw MandelaError.type(Values.typeName(value)
                + " cannot be iterated; use a List, Map, Range, Str or an enum");
    }

    /**
     * Moves to the next element.
     *
     * @return true when an element was found and is now current
     */
    public boolean advance() {
        if (source instanceof MandelaList list) {
            if (index >= list.size()) {
                return done();
            }
            key = (long) index;
            value = list.get(index);
            index++;
            started = true;
            return true;
        }
        if (source instanceof MandelaMap map) {
            List<String> keys = map.keys();
            if (index >= keys.size()) {
                return done();
            }
            key = keys.get(index);
            value = map.get((String) key);
            index++;
            started = true;
            return true;
        }
        if (source instanceof MandelaRange range) {
            if (index >= range.size()) {
                return done();
            }
            // Position arithmetic, not a running total: the value at index i is
            // from + step * i whether the range walks up or down.
            key = (long) index;
            value = range.from() + range.step() * index;
            index++;
            started = true;
            return true;
        }
        if (source instanceof String text) {
            if (index >= text.length()) {
                return done();
            }
            key = (long) index;
            value = String.valueOf(text.charAt(index));
            index++;
            started = true;
            return true;
        }
        if (source instanceof MandelaClass klass) {
            List<String> names = List.copyOf(klass.constants().keySet());
            if (index >= names.size()) {
                return done();
            }
            key = names.get(index);
            value = klass.constants().get((String) key);
            index++;
            started = true;
            return true;
        }
        if (source instanceof MandelaInstance instance) {
            List<String> names = instance.fieldNames();
            if (index >= names.size()) {
                return done();
            }
            key = names.get(index);
            value = instance.get((String) key);
            index++;
            started = true;
            return true;
        }
        return done();
    }

    /** @return the current element, or its key for a map */
    public Object value() {
        return value;
    }

    /** @return the current element's key: an index, a map key or a field name */
    public Object key() {
        return key;
    }

    /** @return true when this cursor was built over a map or a pair source */
    public boolean yieldsPairs() {
        return pairs;
    }

    /** @return the container being walked, for {@code size()} and diagnostics */
    public Object source() {
        return source;
    }

    /** @return the number of elements already produced */
    public int index() {
        return index;
    }

    /** Ends the walk, clearing the current element so it cannot be read again. */
    private boolean done() {
        key = null;
        value = null;
        return false;
    }

    /** @return true once the cursor has produced at least one element */
    public boolean started() {
        return started;
    }

    @Override
    public String toString() {
        return "<iter " + Values.typeName(source) + " " + index + ">";
    }
}
