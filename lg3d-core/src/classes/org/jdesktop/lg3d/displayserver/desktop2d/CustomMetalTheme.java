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
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.awt.Color;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;

/**
 * A {@link DefaultMetalTheme} whose palette is driven by a persisted
 * {@link MetalThemeSpec}. This is the bridge between the pure, serialisable
 * theme description and the live Swing {@code Metal} look-and-feel:
 * {@link MetalThemeManager} installs an instance through
 * {@code MetalLookAndFeel.setCurrentTheme} so the whole 2D desktop adopts the
 * chosen colours.
 *
 * <p>Only the six palette shades are overridden; every font, spacing and
 * structural default is inherited from {@code DefaultMetalTheme}, so a custom
 * theme re-skins the desktop without changing its metrics.</p>
 */
public final class CustomMetalTheme extends DefaultMetalTheme {

    private final MetalThemeSpec spec;

    /** Builds a theme rendering {@code spec}'s palette (never null). */
    public CustomMetalTheme(MetalThemeSpec spec) {
        this.spec = (spec == null) ? MetalThemeSpec.STEEL : spec;
    }

    /** The backing spec, exposed so the manager can compare/persist it. */
    public MetalThemeSpec spec() {
        return spec;
    }

    @Override
    public String getName() {
        return spec.name();
    }

    @Override
    protected ColorUIResource getPrimary1() {
        return resource(spec.primary1());
    }

    @Override
    protected ColorUIResource getPrimary2() {
        return resource(spec.primary2());
    }

    @Override
    protected ColorUIResource getPrimary3() {
        return resource(spec.primary3());
    }

    @Override
    protected ColorUIResource getSecondary1() {
        return resource(spec.secondary1());
    }

    @Override
    protected ColorUIResource getSecondary2() {
        return resource(spec.secondary2());
    }

    @Override
    protected ColorUIResource getSecondary3() {
        return resource(spec.secondary3());
    }

    /** Wraps a plain {@link Color} as the {@link ColorUIResource} Metal wants. */
    private static ColorUIResource resource(Color c) {
        return (c instanceof ColorUIResource r) ? r : new ColorUIResource(c);
    }
}
