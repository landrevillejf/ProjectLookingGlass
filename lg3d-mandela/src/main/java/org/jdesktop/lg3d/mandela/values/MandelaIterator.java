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
 * <p>One object covers lists, maps, ranges, strings, objects and enum constants,
 * so the machine needs four opcodes in total &mdash; {@code NEW_ITER},
 * {@code ITER_NEXT}, and then {@code ITER_VALUE} or {@code ITER_KV} for the pair
 * spellings &mdash; instead of a specialised loop shape per container. The cursor
 * is deliberately <em>advance then peek</em>:
 * {@link #advance()} moves to the next element and reports whether there was one,
 * and the element stays available until the next advance, which is what lets a
 * destructuring body read the same element twice without the iterator having to
 * support look-back.</p>
 *
 * <p>What one trip produces depends on the container: an element of a list, a
 * character of a string, a number of a range, a field value of an object, a
 * constant of an enum and the bound value of a map. A single-name
 * {@code for (x in …)} reads {@link #value()} for every one of them, so a map
 * loop hands out values rather than keys; the pair spellings
 * ({@code for [k, v] in map}) ask the machine for {@link #key()} as well, and
 * {@code key()} is an index for a list, a range or a string, and a name for a
 * map, an object or an enum.</p>
 *
 * <p>Not thread-safe, and it reflects the container as it is: mutating a list
 * while looping it moves the cursor over the new contents, exactly as a
 * {@code java.util.Iterator} would.</p>
 */
public final class MandelaIterator {

    private final Object source;
    private int index;
    private Object key;
    private Object value;

    private MandelaIterator(Object source) {
        this.source = source;
    }

    /**
     * Creates a cursor over any iterable value.
     *
     * @param value the list, map, range, string, enum, object or null to walk
     * @return the cursor
     * @throws MandelaError when the value is not something Mandela can iterate
     */
    public static MandelaIterator of(Object value) {
        if (value instanceof MandelaList || value instanceof MandelaRange
                || value instanceof String || value instanceof MandelaInstance
                || value instanceof MandelaClass || value instanceof MandelaMap) {
            return new MandelaIterator(value);
        }
        if (value == null) {
            // Iterating null is the empty sequence, which is what keeps a
            // `for (x in maybeMissing)` from needing a guard.
            return new MandelaIterator(new MandelaList());
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
            return true;
        }
        if (source instanceof String text) {
            if (index >= text.length()) {
                return done();
            }
            key = (long) index;
            value = String.valueOf(text.charAt(index));
            index++;
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
            return true;
        }
        // The six shapes above are the whole of what of() accepts, so the last one
        // is not a test but the exhausted list: a cast, and no unreachable exit
        // underneath it.
        MandelaInstance instance = (MandelaInstance) source;
        List<String> names = instance.fieldNames();
        if (index >= names.size()) {
            return done();
        }
        key = names.get(index);
        value = instance.get((String) key);
        index++;
        return true;
    }

    /** @return the current element; for a map, an object or an enum this is its value */
    public Object value() {
        return value;
    }

    /** @return the current element's key: an index, a map key or a field name */
    public Object key() {
        return key;
    }

    /** Ends the walk, clearing the current element so it cannot be read again. */
    private boolean done() {
        key = null;
        value = null;
        return false;
    }

    @Override
    public String toString() {
        return "<iter " + Values.typeName(source) + " " + index + ">";
    }
}
