/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4: raised when the declared module requirements form a
 * cycle, so no dependency-safe initialisation order exists. A cycle is a defect
 * in the module set, not a runtime condition to degrade around, so -- unlike a
 * single unsatisfiable module (which the loader skips with a warning) -- it
 * fails the load loudly.
 */
package org.jdesktop.wonderland.modules;

import java.util.Collection;

/** Signals a dependency cycle among the discovered Wonderland plugins. */
public class PluginCycleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param cycleNames the module names on the cycle, in traversal order
     */
    public PluginCycleException(Collection<String> cycleNames) {
        super("cyclic module requirements: " + String.join(" -> ", cycleNames));
    }
}
