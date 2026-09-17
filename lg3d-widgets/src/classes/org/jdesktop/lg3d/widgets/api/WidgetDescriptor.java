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
package org.jdesktop.lg3d.widgets.api;

import java.util.function.Supplier;

/**
 * Immutable metadata describing a kind of widget, plus a factory that creates
 * instances of it. The gallery lists descriptors; the host calls
 * {@link #create()} to instantiate one.
 *
 * <p>Descriptors are contributed by {@link WidgetProvider}s and discovered
 * through the {@link WidgetRegistry}.</p>
 */
public final class WidgetDescriptor {

    private final String id;
    private final String displayName;
    private final String category;
    private final String iconResource;
    private final int defaultWidth;
    private final int defaultHeight;
    private final Supplier<Widget> factory;

    /**
     * @param id            unique widget type id (e.g. {@code "clock"})
     * @param displayName   human-readable name (e.g. {@code "Clock"})
     * @param category      grouping label for the gallery (e.g. {@code "System"})
     * @param iconResource  classpath resource for a gallery icon, or null
     * @param defaultWidth  default Swing panel width in pixels
     * @param defaultHeight default Swing panel height in pixels
     * @param factory       creates a fresh {@link Widget} instance
     */
    public WidgetDescriptor(String id, String displayName, String category,
                            String iconResource, int defaultWidth, int defaultHeight,
                            Supplier<Widget> factory) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id cannot be null/blank");
        }
        if (factory == null) {
            throw new IllegalArgumentException("factory cannot be null");
        }
        this.id = id;
        this.displayName = (displayName == null || displayName.isBlank()) ? id : displayName;
        this.category = (category == null || category.isBlank()) ? "General" : category;
        this.iconResource = iconResource;
        this.defaultWidth = defaultWidth > 0 ? defaultWidth : 120;
        this.defaultHeight = defaultHeight > 0 ? defaultHeight : 80;
        this.factory = factory;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public String category() { return category; }
    /** Classpath resource path for the gallery icon, or null if none. */
    public String iconResource() { return iconResource; }
    public int defaultWidth() { return defaultWidth; }
    public int defaultHeight() { return defaultHeight; }

    /** Creates a new widget instance of this type. */
    public Widget create() {
        return factory.get();
    }

    @Override
    public String toString() { return displayName + " [" + id + "]"; }
}
