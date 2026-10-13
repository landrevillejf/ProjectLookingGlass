/*
 * wonderland-modules M4: the plugin SPI that replaces the NetBeans Platform's
 * org.openide module contracts.
 *
 * The 2006 client discovered feature modules through the NetBeans module system
 * plus scannotation class scanning; both are unrecoverable (no NBM toolchain, no
 * org.openide, and scannotation cannot read modern class files). This rebuilds
 * the same concern as a plain java.util.ServiceLoader contract: a feature module
 * ships one WonderlandPlugin provider named in its META-INF/services entry, and
 * the WonderlandPluginLoader discovers, orders and initialises it.
 *
 * Identity is NOT re-invented here -- it reuses the module metadata already
 * ported into wonderland-common (ModuleInfo carries the name / version /
 * description, ModuleRequires the dependencies). A plugin therefore exposes the
 * same descriptors the (server-side) module repository exchanges, so a headless
 * test and a live desktop see one vocabulary.
 */
package org.jdesktop.wonderland.modules.api;

import org.jdesktop.wonderland.common.modules.ModuleInfo;
import org.jdesktop.wonderland.common.modules.ModuleRequires;

/**
 * The entry point a Wonderland feature module contributes. One implementation
 * per module, registered through {@code META-INF/services} and discovered by
 * {@link org.jdesktop.wonderland.modules.WonderlandPluginLoader}.
 */
public interface WonderlandPlugin {

    /**
     * The module's identity -- name, version and description. The loader keys
     * and orders plugins by {@link ModuleInfo#getName() getName()}, so this
     * must be non-null and stable.
     *
     * @return the module descriptor, never {@code null}
     */
    ModuleInfo getModuleInfo();

    /**
     * The modules this one must be initialised after. The default is an empty
     * requirement set (a leaf module). A requirement that is not present among
     * the discovered plugins makes this plugin unsatisfiable, and the loader
     * skips it rather than failing the whole boot.
     *
     * @return the dependency descriptor, never {@code null}
     */
    default ModuleRequires getRequires() {
        return new ModuleRequires(new ModuleInfo[0]);
    }

    /**
     * Bring the module up. Called exactly once, after every module this one
     * {@link #getRequires() requires} has already been initialised, so a plugin
     * may look up its peers through the {@code context}.
     *
     * @param context the loader-supplied view of the running module set
     */
    void initialize(PluginContext context);

    /**
     * Take the module down. Called in reverse initialisation order. The default
     * does nothing, for the many modules with no resource to release.
     */
    default void dispose() {
    }
}
