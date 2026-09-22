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
package org.jdesktop.lg3d.wg.switcher;

import java.awt.Image;
import javax.swing.Icon;

/**
 * One entry of an application switcher: the display name and icon shown in the
 * overlay, plus an opaque reference to the underlying window the switcher
 * activates.
 *
 * <p>The window reference is deliberately an {@link Object} so the same
 * switcher machinery can serve every desktop: a {@code Desktop2DWindow}
 * (an MDI {@code JInternalFrame}) on the 2D/Swing desktop, a {@code Frame3D}
 * on the 3D desktop, or a {@code NativeWindow3D} for an external application
 * composited in compositor mode. Only the {@link SwitcherModel} that produced
 * the item knows how to interpret it.</p>
 *
 * <p>This class is pure AWT/Swing and pulls in no Java 3D, so it is safe to
 * use (and unit-test) headless.</p>
 */
public final class SwitcherItem {

    private final Object window;
    private final String name;
    private final Icon icon;
    private final Image thumbnail;

    /**
     * @param window    the underlying window handle (never null)
     * @param name      the label shown in the overlay (null becomes "")
     * @param icon      the application icon, or null for none
     * @param thumbnail an optional preview image, or null to draw only the icon
     */
    public SwitcherItem(Object window, String name, Icon icon, Image thumbnail) {
        this.window = window;
        this.name = (name == null) ? "" : name;
        this.icon = icon;
        this.thumbnail = thumbnail;
    }

    /** Convenience constructor for an item with no thumbnail. */
    public SwitcherItem(Object window, String name, Icon icon) {
        this(window, name, icon, null);
    }

    /** The underlying window this item activates. */
    public Object getWindow() {
        return window;
    }

    /** The label shown in the overlay; never null. */
    public String getName() {
        return name;
    }

    /** The application icon, or null. */
    public Icon getIcon() {
        return icon;
    }

    /** An optional preview image, or null. */
    public Image getThumbnail() {
        return thumbnail;
    }

    @Override
    public String toString() {
        return name;
    }
}
