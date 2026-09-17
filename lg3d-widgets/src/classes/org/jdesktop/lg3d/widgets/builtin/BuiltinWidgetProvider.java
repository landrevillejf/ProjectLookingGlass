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
import org.jdesktop.lg3d.widgets.api.WidgetDescriptor;
import org.jdesktop.lg3d.widgets.api.WidgetProvider;

/**
 * Contributes the widgets bundled with the lg3d-widgets module (clock,
 * temperature, CPU and memory) to the {@link org.jdesktop.lg3d.widgets.api.WidgetRegistry}.
 *
 * <p>Registered through
 * {@code META-INF/services/org.jdesktop.lg3d.widgets.api.WidgetProvider}. Icons
 * reference the core icon set, which is on the desktop runtime classpath.</p>
 */
public class BuiltinWidgetProvider implements WidgetProvider {

    private static final String ICON_PREFIX = "/resources/images/icon/";

    @Override
    public List<WidgetDescriptor> descriptors() {
        return List.of(
            new WidgetDescriptor(ClockWidget.ID, "Clock", "Clock",
                    ICON_PREFIX + "star.png", 150, 150, ClockWidget::new),
            new WidgetDescriptor(TemperatureWidget.ID, "Temperature", "System",
                    ICON_PREFIX + "system.png", 150, 120, TemperatureWidget::new),
            new WidgetDescriptor(CpuWidget.ID, "CPU Load", "System",
                    ICON_PREFIX + "system.png", 150, 110, CpuWidget::new),
            new WidgetDescriptor(MemoryWidget.ID, "Memory", "System",
                    ICON_PREFIX + "system.png", 160, 110, MemoryWidget::new)
        );
    }
}
