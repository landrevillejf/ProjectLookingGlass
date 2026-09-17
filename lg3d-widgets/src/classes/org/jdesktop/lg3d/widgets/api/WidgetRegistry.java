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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Aggregates every {@link WidgetProvider} visible on the classpath (via
 * {@link ServiceLoader}) into a single lookup of {@link WidgetDescriptor}s.
 *
 * <p>Use {@link #getInstance()} for the shared registry, {@link #descriptors()} to
 * list available widget types, and {@link #create(String)} to instantiate one by
 * id. A provider that fails to load is logged and skipped so one bad jar cannot
 * break the desktop.</p>
 */
public final class WidgetRegistry {
    private static final Logger logger = Logger.getLogger("lg.widgets");

    private static final WidgetRegistry INSTANCE = new WidgetRegistry();

    /** id -> descriptor, in discovery order. */
    private final Map<String, WidgetDescriptor> byId = new LinkedHashMap<>();

    private WidgetRegistry() {
        loadProviders(getClass().getClassLoader());
    }

    /** The shared registry. */
    public static WidgetRegistry getInstance() {
        return INSTANCE;
    }

    private void loadProviders(ClassLoader cl) {
        ServiceLoader<WidgetProvider> loader =
                (cl != null) ? ServiceLoader.load(WidgetProvider.class, cl)
                             : ServiceLoader.load(WidgetProvider.class);
        for (WidgetProvider provider : loader) {
            try {
                List<WidgetDescriptor> descriptors = provider.descriptors();
                if (descriptors == null) {
                    continue;
                }
                for (WidgetDescriptor d : descriptors) {
                    if (d == null) {
                        continue;
                    }
                    if (byId.containsKey(d.id())) {
                        logger.log(Level.WARNING,
                                "Duplicate widget id ''{0}'' from {1}; keeping the first",
                                new Object[]{d.id(), provider.getClass().getName()});
                        continue;
                    }
                    byId.put(d.id(), d);
                }
            } catch (Throwable t) {
                // Never let one provider take down widget discovery.
                logger.log(Level.WARNING, "Widget provider failed: "
                        + provider.getClass().getName(), t);
            }
        }
        logger.log(Level.INFO, "Widget registry loaded {0} widget type(s): {1}",
                new Object[]{byId.size(), byId.keySet()});
    }

    /** All discovered descriptors, in discovery order (unmodifiable). */
    public List<WidgetDescriptor> descriptors() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    /** The descriptor for the given id, or null if unknown. */
    public WidgetDescriptor descriptor(String id) {
        return byId.get(id);
    }

    /** True if a widget type with the given id is available. */
    public boolean contains(String id) {
        return byId.containsKey(id);
    }

    /**
     * Creates a new instance of the widget type with the given id.
     *
     * @return the new {@link Widget}, or null if the id is unknown or the factory
     *         failed
     */
    public Widget create(String id) {
        WidgetDescriptor d = byId.get(id);
        if (d == null) {
            logger.log(Level.WARNING, "Unknown widget id: {0}", id);
            return null;
        }
        try {
            return d.create();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Failed to create widget '" + id + "'", t);
            return null;
        }
    }
}
