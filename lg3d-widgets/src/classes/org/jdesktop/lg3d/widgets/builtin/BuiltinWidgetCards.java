/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.widgets.builtin;

import java.util.List;

/**
 * The catalogue of built-in widget cards (clock, temperature, CPU, memory and
 * weather), as pure-Swing {@link WidgetCardSpec}s.
 *
 * <p>This is the single source of the built-in widgets' metadata. The 2D
 * desktop's gallery and widget layer build directly from it, and the 3D
 * {@link BuiltinWidgetProvider} derives its Java 3D descriptors from it, so the
 * id / name / category / icon / size of each widget is written exactly once.</p>
 *
 * <p>Nothing here references Java 3D, so the whole catalogue (and every card it
 * creates) loads and runs on a JVM where the Java 3D jars are absent.</p>
 */
public final class BuiltinWidgetCards {

    private static final String ICON_PREFIX = "/resources/images/icon/";

    private static final List<WidgetCardSpec> ALL = List.of(
        new WidgetCardSpec(ClockCard.ID, "Clock", "Clock",
                ICON_PREFIX + "star.png", 150, 150, ClockCard::new),
        new WidgetCardSpec(TemperatureCard.ID, "Temperature", "System",
                ICON_PREFIX + "system.png", 150, 120, TemperatureCard::new),
        new WidgetCardSpec(CpuCard.ID, "CPU Load", "System",
                ICON_PREFIX + "system.png", 150, 110, CpuCard::new),
        new WidgetCardSpec(MemoryCard.ID, "Memory", "System",
                ICON_PREFIX + "system.png", 160, 110, MemoryCard::new),
        new WidgetCardSpec(WeatherCard.ID, "Weather", "Web",
                ICON_PREFIX + "leaf.png", 200, 160, WeatherCard::new)
    );

    private BuiltinWidgetCards() {
        // no instances
    }

    /** Every built-in card spec, in gallery order (unmodifiable). */
    public static List<WidgetCardSpec> all() {
        return ALL;
    }

    /** The spec for the given type id, or null if it is not a built-in. */
    public static WidgetCardSpec forId(String id) {
        if (id == null) {
            return null;
        }
        for (WidgetCardSpec spec : ALL) {
            if (spec.id().equals(id)) {
                return spec;
            }
        }
        return null;
    }

    /** True if {@code id} names a built-in widget card. */
    public static boolean contains(String id) {
        return forId(id) != null;
    }
}
