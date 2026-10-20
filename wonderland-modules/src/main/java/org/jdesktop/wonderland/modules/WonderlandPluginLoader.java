/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4: the plugin loader that replaces the NetBeans Platform
 * module system and the dead scannotation class scan.
 *
 * Concern split for testability:
 *   - discover()   the only ServiceLoader touchpoint -- it reads the
 *                  META-INF/services entries on a class loader (exactly the
 *                  plugin/annotation-scanning concern whose javadoc in
 *                  ScannedClassLoader defers to "milestone M4"). Kept thin so a
 *                  test can feed plugins directly.
 *   - load(Collection)  the ordering/initialisation brain, which needs NO
 *                  class loader: it indexes by module name, drops unsatisfiable
 *                  modules (a required name is absent), topologically sorts the
 *                  rest so a dependency initialises before its dependents, and
 *                  fails on a requirement cycle. This is exercised headless with
 *                  hand-built plugins in WonderlandPluginLoaderTest.
 *   - loadAll()    discover + load, the path the assembled desktop calls.
 *
 * Degradation policy mirrors the module contract: a single missing dependency
 * skips only the affected subtree with a WARNING (the desktop boots with the
 * modules it has), while a cycle -- a defect that no subset can satisfy --
 * throws PluginCycleException rather than silently dropping every member.
 */
package org.jdesktop.wonderland.modules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.wonderland.common.modules.ModuleInfo;
import org.jdesktop.wonderland.modules.api.PluginContext;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;

/** Discovers, orders and initialises the {@link WonderlandPlugin} module set. */
public final class WonderlandPluginLoader {

    private static final Logger logger =
            Logger.getLogger(WonderlandPluginLoader.class.getName());

    /** Discovers plugins on the thread context class loader. */
    public List<WonderlandPlugin> discover() {
        return discover(Thread.currentThread().getContextClassLoader());
    }

    /**
     * Discovers every {@link WonderlandPlugin} service on {@code loader}. A
     * broken provider (one that throws during the service iteration) is logged
     * and skipped, so one bad jar cannot abort discovery of the rest.
     *
     * @param loader the class loader whose {@code META-INF/services} entries are read
     * @return the discovered plugins in service-file order
     */
    public List<WonderlandPlugin> discover(ClassLoader loader) {
        List<WonderlandPlugin> found = new ArrayList<>();
        try {
            for (WonderlandPlugin plugin :
                    ServiceLoader.load(WonderlandPlugin.class, loader)) {
                found.add(plugin);
            }
        } catch (ServiceConfigurationError err) {
            logger.log(Level.WARNING, "plugin discovery failed part-way; loading what was found", err);
        }
        return found;
    }

    /**
     * Discover on the context loader and {@link #load(Collection) load} the
     * result. This is what the assembled desktop calls once at boot.
     *
     * @return the outcome (loaded set in order, skipped names)
     */
    public LoadResult loadAll() {
        return load(discover());
    }

    /**
     * Order and initialise the given plugins. The ordering/dependency brain
     * described in the class header; no class loader is involved.
     *
     * @param discovered the plugins to bring up (may be any collection)
     * @return the outcome: the initialised plugins in initialisation order, and
     *     the module names that were skipped for an unsatisfiable requirement
     * @throws IllegalArgumentException when two plugins share a module name
     * @throws PluginCycleException when the requirements form a cycle
     */
    public LoadResult load(Collection<? extends WonderlandPlugin> discovered) {
        Map<String, WonderlandPlugin> byName = index(discovered);
        Map<String, Set<String>> requires = requiredNames(byName);

        Set<String> alive = pruneUnsatisfiable(byName.keySet(), requires);
        List<String> order = topologicalOrder(alive, requires);

        Map<String, WonderlandPlugin> initialized = new LinkedHashMap<>();
        List<WonderlandPlugin> loaded = new ArrayList<>();
        for (String name : order) {
            WonderlandPlugin plugin = byName.get(name);
            plugin.initialize(new PluginContext(initialized));
            initialized.put(name, plugin);
            loaded.add(plugin);
        }

        List<String> skipped = new ArrayList<>(byName.keySet());
        skipped.removeAll(alive);
        return new LoadResult(loaded, skipped);
    }

    /** Index by module name; a null/blank name or a duplicate is a hard error. */
    private Map<String, WonderlandPlugin> index(
            Collection<? extends WonderlandPlugin> discovered) {
        Map<String, WonderlandPlugin> byName = new LinkedHashMap<>();
        for (WonderlandPlugin plugin : discovered) {
            ModuleInfo info = plugin.getModuleInfo();
            if (info == null || info.getName() == null) {
                throw new IllegalArgumentException(
                        "plugin " + plugin.getClass().getName() + " declares no module name");
            }
            WonderlandPlugin clash = byName.putIfAbsent(info.getName(), plugin);
            if (clash != null) {
                throw new IllegalArgumentException("module '" + info.getName()
                        + "' is contributed by both " + clash.getClass().getName()
                        + " and " + plugin.getClass().getName());
            }
        }
        return byName;
    }

    /** The set of module names each module requires. */
    private Map<String, Set<String>> requiredNames(Map<String, WonderlandPlugin> byName) {
        Map<String, Set<String>> requires = new LinkedHashMap<>();
        for (Map.Entry<String, WonderlandPlugin> entry : byName.entrySet()) {
            Set<String> names = new LinkedHashSet<>();
            for (ModuleInfo dep : entry.getValue().getRequires().getRequires()) {
                if (dep.getName() != null) {
                    names.add(dep.getName());
                }
            }
            requires.put(entry.getKey(), names);
        }
        return requires;
    }

    /**
     * Drop, to a fixed point, any module that requires a name not itself alive
     * (so a dependent of a dropped module is dropped too). Each drop is logged.
     */
    private Set<String> pruneUnsatisfiable(Set<String> all, Map<String, Set<String>> requires) {
        Set<String> alive = new LinkedHashSet<>(all);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String name : new ArrayList<>(alive)) {
                for (String dep : requires.get(name)) {
                    if (!alive.contains(dep)) {
                        alive.remove(name);
                        changed = true;
                        logger.log(Level.WARNING,
                                "skipping module {0}: required module ''{1}'' is not available",
                                new Object[] {name, dep});
                        break;
                    }
                }
            }
        }
        return alive;
    }

    /**
     * Kahn's algorithm over the alive set, dependency before dependent, ties
     * broken by the (stable) insertion order. Leftover nodes prove a cycle.
     */
    private List<String> topologicalOrder(Set<String> alive, Map<String, Set<String>> requires) {
        // discovery index, so equal-rank modules come out in a fixed order
        Map<String, Integer> rank = new LinkedHashMap<>();
        int i = 0;
        for (String name : alive) {
            rank.put(name, i++);
        }

        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (String name : alive) {
            indegree.put(name, 0);
            dependents.put(name, new ArrayList<>());
        }
        for (String name : alive) {
            for (String dep : requires.get(name)) {
                // pruneUnsatisfiable ran to a fixed point, so every dep of an
                // alive module is itself alive and dependents.get(dep) is never
                // null. A self-edge is included on purpose: it keeps the node's
                // indegree non-zero, which is exactly how a self-requirement
                // becomes a cycle below.
                dependents.get(dep).add(name);
                indegree.merge(name, 1, Integer::sum);
            }
        }

        PriorityQueue<String> ready =
                new PriorityQueue<>(Comparator.comparingInt(rank::get));
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String name = ready.poll();
            order.add(name);
            for (String dependent : dependents.get(name)) {
                if (indegree.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (order.size() < alive.size()) {
            Set<String> leftover = new LinkedHashSet<>(alive);
            leftover.removeAll(order);
            throw new PluginCycleException(leftover);
        }
        return order;
    }

    /**
     * The outcome of a {@link WonderlandPluginLoader#load load}: which modules
     * came up (and in what order), and which were skipped.
     */
    public static final class LoadResult {

        private final List<WonderlandPlugin> loaded;
        private final List<String> skipped;

        LoadResult(List<WonderlandPlugin> loaded, List<String> skipped) {
            this.loaded = List.copyOf(loaded);
            this.skipped = List.copyOf(skipped);
        }

        /** The initialised plugins, in initialisation order. */
        public List<WonderlandPlugin> loaded() {
            return loaded;
        }

        /** The module names skipped for an unsatisfiable requirement. */
        public List<String> skipped() {
            return skipped;
        }

        /**
         * Take the loaded modules down, disposing each in the reverse of
         * initialisation order (so a dependent is torn down before its
         * dependency). Safe to call once; a double call is a no-op.
         */
        public void shutdown() {
            Deque<WonderlandPlugin> reverse = new ArrayDeque<>();
            for (WonderlandPlugin plugin : loaded) {
                reverse.push(plugin);
            }
            Set<WonderlandPlugin> done = new HashSet<>();
            for (WonderlandPlugin plugin : reverse) {
                if (done.add(plugin)) {
                    plugin.dispose();
                }
            }
        }
    }
}
