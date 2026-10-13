/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4: the view of the running module set that the loader
 * hands each plugin at initialisation.
 *
 * The 2006 modules reached one another through the NetBeans lookup; here a
 * plugin simply resolves a peer by module name. Because the loader initialises
 * in dependency order, any module a plugin getRequires() is already present in
 * this context when initialize() runs, so a lookup of a declared dependency
 * always succeeds. The context is deliberately tiny and carries no display or
 * scene-graph type -- the whole point of M4 is that the loader and the module
 * cores run headless.
 */
package org.jdesktop.wonderland.modules.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only access to the plugins that have already been initialised. Supplied
 * by {@link org.jdesktop.wonderland.modules.WonderlandPluginLoader} to
 * {@link WonderlandPlugin#initialize(PluginContext)}.
 */
public final class PluginContext {

    private final Map<String, WonderlandPlugin> initialized;

    /**
     * @param initialized the plugins initialised so far, keyed by module name;
     *     copied so a caller cannot mutate the loader's live registry
     */
    public PluginContext(Map<String, WonderlandPlugin> initialized) {
        this.initialized = new LinkedHashMap<>(initialized);
    }

    /**
     * The initialised plugin for a module name.
     *
     * @param moduleName the {@link WonderlandPlugin#getModuleInfo() name} of the
     *     module to look up
     * @return the plugin, or {@code null} when no module of that name is loaded
     */
    public WonderlandPlugin plugin(String moduleName) {
        return initialized.get(moduleName);
    }

    /**
     * The initialised plugin for a module name, as a specific type.
     *
     * @param <T> the expected plugin type
     * @param moduleName the module to look up
     * @param type the class the plugin is expected to be
     * @return the cast plugin, or {@code null} when the module is not loaded
     * @throws ClassCastException when a module of that name is loaded but is not
     *     an instance of {@code type}
     */
    public <T extends WonderlandPlugin> T plugin(String moduleName, Class<T> type) {
        WonderlandPlugin plugin = initialized.get(moduleName);
        return plugin == null ? null : type.cast(plugin);
    }

    /**
     * The names of the modules available to the caller, in initialisation order.
     *
     * @return an unmodifiable snapshot of the loaded module names
     */
    public java.util.Set<String> loadedModuleNames() {
        return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(initialized.keySet()));
    }
}
