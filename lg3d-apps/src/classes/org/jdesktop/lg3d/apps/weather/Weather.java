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
package org.jdesktop.lg3d.apps.weather;

import org.jdesktop.lg3d.apps.TitledSwingWindow;

/**
 * The Weather application: the {@link WeatherPanel} current-conditions and
 * forecast reader presented as an integrated 3D desktop window (title bar plus
 * minimize / maximize / close) via {@link TitledSwingWindow}, which hosts the
 * panel on a {@code SwingNode} quad below a draggable glassy title bar.
 *
 * <p>This is the 3D-desktop entry point (Start Menu &rarr; Utilities &rarr;
 * Weather). In the 2D/Swing desktop the very same {@link WeatherPanel} is
 * hosted as an MDI internal frame by {@code Desktop2DAppRegistry}, so this
 * wrapper is never loaded there &mdash; only the panel is.</p>
 *
 * <p>Weather comes from the free Open-Meteo API through {@link OpenMeteo}, the
 * same key-less data source the desktop Weather widget uses, over the JDK
 * {@code java.net.http} client with a dependency-free JSON reader.</p>
 */
public class Weather {

    public static void main(String[] args) {
        new Weather();
    }

    public Weather() {
        // Metal, not the platform Synth LAF: Synth widgets NPE when SwingNode
        // paints them offscreen. Must run before the panel is constructed.
        TitledSwingWindow.installHostedLookAndFeel();
        TitledSwingWindow.show(
                "Weather",
                new WeatherPanel(),
                WeatherPanel.WIDTH_PX,
                WeatherPanel.HEIGHT_PX);
    }
}
