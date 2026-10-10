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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Mandela map: an insertion-ordered set of string-keyed values, written
 * {@code { "name": "Ada", score: 7 }}.
 *
 * <p>Keys are strings, always. Mandela gives up the ability to key a map by an
 * arbitrary object in exchange for three properties that matter on a desktop
 * where scripts trade data with JSON, HTTP payloads, configuration files and
 * editor documents: a map is trivially serialisable, {@code m.k} and
 * {@code m["k"]} mean the same thing, and no key type can quietly produce two
 * entries that look identical when printed.</p>
 *
 * <p>{@link LinkedHashMap} keeps declaration order, so {@code keys()} and the
 * printed form round-trip the way the author wrote them.</p>
 */
public final class MandelaMap {

    private final Map<String, Object> entries;

    /** An empty map. */
    public MandelaMap() {
        this.entries = new LinkedHashMap<>();
    }

    /** An empty map with room for {@code expected} keys. */
    public MandelaMap(int expected) {
        this.entries = new LinkedHashMap<>(Math.max(16, expected * 2));
    }

    /** @return the number of keys */
    public int size() {
        return entries.size();
    }

    /** @return true when the map has no keys */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * @param key the lookup key
     * @return the bound value, or {@code null} when the key is absent
     */
    public Object get(String key) {
        return entries.get(key);
    }

    /** Binds {@code value} to {@code key}, replacing any earlier binding. */
    public void put(String key, Object value) {
        entries.put(key, value);
    }

    /** @return true when {@code key} is present */
    public boolean contains(String key) {
        return entries.containsKey(key);
    }

    /** @return the value that was bound to {@code key}, or null */
    public Object remove(String key) {
        return entries.remove(key);
    }

    /** Removes every binding. */
    public void clear() {
        entries.clear();
    }

    /** Copies every binding of {@code other} into this map. */
    public void putAll(MandelaMap other) {
        entries.putAll(other.entries);
    }

    /** @return the keys in insertion order */
    public List<String> keys() {
        return new ArrayList<>(entries.keySet());
    }

    /** @return the values in key order */
    public List<Object> values() {
        return new ArrayList<>(entries.values());
    }

    /**
     * @return the live backing map, for runtime builtins that scan or sort
     *         without copying; script code never sees it
     */
    public Map<String, Object> entries() {
        return entries;
    }

    /** @return a shallow copy holding the same bindings */
    public MandelaMap copyOf() {
        MandelaMap copy = new MandelaMap(entries.size());
        copy.entries.putAll(entries);
        return copy;
    }

    /** Two maps are equal when they bind the same keys to equal values. */
    @Override
    public boolean equals(Object other) {
        return other instanceof MandelaMap map && entries.equals(map.entries);
    }

    /** @return a hash consistent with {@link #equals(Object)} */
    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return Values.display(this);
    }
}
