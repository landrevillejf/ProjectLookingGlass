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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A Mandela list: an ordered, mutable sequence of values, written
 * {@code [1, 2, 3]}.
 *
 * <p>Backed by an {@code ArrayList}, which gives the two operations scripts use
 * most &mdash; random access by index and append &mdash; amortised constant time.
 * The value is a reference: passing a list to a function and appending to it
 * there is visible to the caller, which is the behaviour authors expect from a
 * collection, and the reason {@code List} is deliberately not a record.</p>
 *
 * <p>Growth limits are enforced by the runtime ({@code rt.Std}/{@code rt.Json})
 * rather than here, so the container stays a plain data type and a hostile
 * script cannot make a list outgrow what the host configured.</p>
 */
public final class MandelaList implements Iterable<Object> {

    private final List<Object> items;

    /** An empty list. */
    public MandelaList() {
        this.items = new ArrayList<>();
    }

    /** A list of the given capacity, for builders that know the size. */
    public MandelaList(int capacity) {
        this.items = new ArrayList<>(Math.max(0, capacity));
    }

    /** A list containing {@code source}, in order. */
    public MandelaList(List<Object> source) {
        this.items = new ArrayList<>(source);
    }

    /** @return the number of elements */
    public int size() {
        return items.size();
    }

    /** @return true when the list holds nothing */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * @param index the position, may be negative to count from the end
     * @return the element at {@code index}
     * @throws IndexOutOfBoundsException when the position is not in range
     */
    public Object get(int index) {
        return items.get(normalise(index));
    }

    /**
     * Replaces the element at {@code index}.
     *
     * @param index the position, may be negative to count from the end
     * @param value the new element
     */
    public void set(int index, Object value) {
        items.set(normalise(index), value);
    }

    /** Appends {@code value}. */
    public void add(Object value) {
        items.add(value);
    }

    /**
     * Inserts {@code value} at {@code index}, shifting the tail right.
     *
     * @param index the position, may be negative to count from the end; the
     *              index {@code size()} appends
     */
    public void insert(int index, Object value) {
        int at = index < 0 ? Math.max(0, items.size() + index) : index;
        items.add(Math.min(at, items.size()), value);
    }

    /**
     * Removes the element at {@code index}.
     *
     * @param index the position, may be negative to count from the end
     * @return the removed element
     */
    public Object removeAt(int index) {
        return items.remove(normalise(index));
    }

    /** Removes every element. */
    public void clear() {
        items.clear();
    }

    /** Appends every element of {@code other}. */
    public void addAll(MandelaList other) {
        items.addAll(other.items);
    }

    /**
     * @return the live backing list. Callers in the runtime use this to sort or
     *         scan without copying; script code never sees it.
     */
    public List<Object> items() {
        return items;
    }

    /** @return a shallow copy: a new list holding the same elements */
    public MandelaList copyOf() {
        return new MandelaList(items);
    }

    @Override
    public Iterator<Object> iterator() {
        return items.iterator();
    }

    /**
     * Two lists are equal when they hold equal elements in the same order, which
     * is what makes {@code == } useful on parsed data.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof MandelaList list && items.equals(list.items);
    }

    @Override
    public int hashCode() {
        return items.hashCode();
    }

    @Override
    public String toString() {
        return Values.display(this);
    }

    /** Maps a possibly negative index onto a position, throwing when out of range. */
    private int normalise(int index) {
        int at = (index < 0) ? items.size() + index : index;
        if (at < 0 || at >= items.size()) {
            throw new IndexOutOfBoundsException(
                    "index " + index + " is out of bounds for a list of " + items.size());
        }
        return at;
    }
}
