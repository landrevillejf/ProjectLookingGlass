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
package org.jdesktop.lg3d.widgets.builtin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jdesktop.lg3d.widgets.api.Widget;
import org.jdesktop.lg3d.widgets.api.WidgetDescriptor;
import org.jdesktop.lg3d.widgets.api.WidgetProvider;

/**
 * Contributes the widgets bundled with the lg3d-widgets module (clock,
 * calendar, temperature, CPU, memory, system indicators and weather) to the
 * {@link org.jdesktop.lg3d.widgets.api.WidgetRegistry}.
 *
 * <p>Registered through
 * {@code META-INF/services/org.jdesktop.lg3d.widgets.api.WidgetProvider}. The
 * metadata (id, name, category, icon, default size) comes from the shared,
 * Java 3D-free {@link BuiltinWidgetCards} catalogue - the same source the 2D
 * desktop uses - so each widget is described exactly once; this provider only
 * pairs each card spec with the {@link Widget} that hosts it in 3D.</p>
 */
public class BuiltinWidgetProvider implements WidgetProvider {

    /** Widget type id -> factory for the 3D widget that hosts its card. */
    private static final Map<String, Supplier<Widget>> WIDGETS = new LinkedHashMap<>();

    static {
        WIDGETS.put(ClockWidget.ID, ClockWidget::new);
        WIDGETS.put(CalendarWidget.ID, CalendarWidget::new);
        WIDGETS.put(TemperatureWidget.ID, TemperatureWidget::new);
        WIDGETS.put(CpuWidget.ID, CpuWidget::new);
        WIDGETS.put(MemoryWidget.ID, MemoryWidget::new);
        WIDGETS.put(SystemIndicatorsWidget.ID, SystemIndicatorsWidget::new);
        WIDGETS.put(WeatherWidget.ID, WeatherWidget::new);
    }

    @Override
    public List<WidgetDescriptor> descriptors() {
        List<WidgetDescriptor> out = new ArrayList<>();
        for (WidgetCardSpec spec : BuiltinWidgetCards.all()) {
            Supplier<Widget> factory = WIDGETS.get(spec.id());
            if (factory == null) {
                continue;
            }
            out.add(new WidgetDescriptor(spec.id(), spec.displayName(), spec.category(),
                    spec.iconResource(), spec.defaultWidth(), spec.defaultHeight(), factory));
        }
        return out;
    }
}
