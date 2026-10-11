/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d.screensaver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Discovers the installed {@link ScreenSaver}s with
 * {@link java.util.ServiceLoader} and hands out the validated, ordered list
 * the hosts use. Keeping discovery behind one method
 * ({@link #discover()}) is what makes the desktop core independent of
 * lg3d-apps: the Kotlin suite registers itself through
 * {@code META-INF/services}, a third party can register the same way, and a
 * saver whose class is missing or broken on this classpath is logged and
 * dropped rather than taking the whole feature (or the desktop) down.
 *
 * <p>Validation rules, all of them drop-with-log:</p>
 * <ul>
 *   <li>a provider that fails to load or instantiate
 *       ({@link java.util.ServiceConfigurationError}, any
 *       {@link Throwable});</li>
 *   <li>blank or duplicate {@link ScreenSaver#id()} (first registration
 *       wins);</li>
 *   <li>duplicated option keys inside one saver's
 *       {@link ScreenSaver#options()}.</li>
 * </ul>
 *
 * <p>The core {@link BlankSaver} is always present (last in the list, so a
 * real saver wins the "first available" default), and the list is sorted by
 * display name for the Control Center's picker.</p>
 */
public final class ScreenSaverRegistry {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    private ScreenSaverRegistry() {
        // no instances
    }

    /**
     * The installed savers, validated, name-sorted, with the failsafe
     * {@link BlankSaver} appended. Never null and never empty.
     */
    public static List<ScreenSaver> discover() {
        // LinkedHashMap keyed by id: first registration of an id wins, and
        // insertion order gives a deterministic "duplicate" log.
        Map<String, ScreenSaver> byId = new LinkedHashMap<>();
        for (ScreenSaver s : load()) {
            if (s == null || !isWellFormed(s, byId)) {
                continue;
            }
            byId.put(s.id(), s);
        }
        if (!byId.containsKey(BlankSaver.ID)) {
            byId.put(BlankSaver.ID, new BlankSaver());
        }
        List<ScreenSaver> all = new ArrayList<>(byId.values());
        all.removeIf(s -> BlankSaver.ID.equals(s.id()));
        Collections.sort(all, Comparator.comparing(
                ScreenSaver::displayName, String.CASE_INSENSITIVE_ORDER));
        all.add(byId.get(BlankSaver.ID));
        return Collections.unmodifiableList(all);
    }

    /**
     * The saver registered under {@code id}, or the first installed one when
     * {@code id} is null/blank/stale (the "first available" default the
     * Control Center shows as "Default"), or null only if the registry came
     * up empty - which {@link #discover()} prevents, but callers keep the
     * null check for belt and braces.
     */
    public static ScreenSaver byId(List<ScreenSaver> all, String id) {
        if (all == null || all.isEmpty()) {
            return null;
        }
        if (id != null && !id.isBlank()) {
            for (ScreenSaver s : all) {
                if (id.equals(s.id())) {
                    return s;
                }
            }
        }
        return all.get(0);
    }

    /**
     * Resolves the raw stored option values for {@code saver} against its
     * declared schema: every declared key gets a value (the coerced stored
     * one, or the schema default when nothing is stored), and any stored key
     * the saver no longer declares is dropped. The result is exactly the map
     * {@link ScreenSaver#createView} promises its view.
     */
    public static Map<String, String> resolveOptions(
            ScreenSaver saver, Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (ScreenSaverOption option : saver.options()) {
            String stored = raw == null ? null : raw.get(option.getKey());
            out.put(option.getKey(), option.coerce(stored));
        }
        return Collections.unmodifiableMap(out);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** One ServiceLoader sweep, swallowing per-provider failures. */
    private static List<ScreenSaver> load() {
        List<ScreenSaver> found = new ArrayList<>();
        ClassLoader cl = ScreenSaverRegistry.class.getClassLoader();
        try {
            ServiceLoader<ScreenSaver> loader =
                    ServiceLoader.load(ScreenSaver.class, cl);
            // iterate() in small steps so one bad provider cannot stop the
            // rest of the sweep.
            for (var it = loader.iterator(); it.hasNext();) {
                try {
                    found.add(it.next());
                } catch (Throwable t) {
                    // ServiceConfigurationError and anything a provider's
                    // initializer throws: one bad jar cannot stop the sweep.
                    logger.log(Level.WARNING,
                            "Skipping an unloadable screen saver provider", t);
                }
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING,
                    "Screen saver discovery failed; only the failsafe "
                    + "Blank saver will be available", t);
        }
        return found;
    }

    /** Metadata sanity per the class doc; logs the reason when dropping. */
    private static boolean isWellFormed(ScreenSaver s,
                                        Map<String, ScreenSaver> taken) {
        String id;
        String name;
        try {
            id = s.id();
            name = s.displayName();
        } catch (Throwable t) {
            logger.log(Level.WARNING,
                    "Skipping screen saver with failing metadata", t);
            return false;
        }
        if (id == null || id.isBlank()) {
            logger.log(Level.WARNING,
                    "Skipping screen saver ''{0}'': blank id", name);
            return false;
        }
        if (taken.containsKey(id)) {
            logger.log(Level.WARNING,
                    "Skipping duplicate screen saver id ''{0}''", id);
            return false;
        }
        List<ScreenSaverOption> options;
        try {
            options = s.options();
        } catch (Throwable t) {
            logger.log(Level.WARNING,
                    "Skipping screen saver " + id
                    + ": options() failed", t);
            return false;
        }
        if (options == null) {
            logger.log(Level.WARNING,
                    "Skipping screen saver {0}: null options()", id);
            return false;
        }
        Set<String> keys = new HashSet<>();
        for (ScreenSaverOption o : options) {
            if (o == null || o.getKey() == null || o.getKey().isBlank()
                    || !keys.add(o.getKey())) {
                logger.log(Level.WARNING,
                        "Skipping screen saver {0}: duplicated or blank "
                        + "option key", id);
                return false;
            }
        }
        if (name == null || name.isBlank()) {
            logger.log(Level.WARNING,
                    "Skipping screen saver {0}: blank display name", id);
            return false;
        }
        return true;
    }
}
