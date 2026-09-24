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
package org.jdesktop.lg3d.widgets.api;

import java.util.List;

/**
 * Service-provider interface for contributing widgets.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader}. To add
 * widgets from a third-party jar, provide an implementation of this interface and
 * register it in
 * {@code META-INF/services/org.jdesktop.lg3d.widgets.api.WidgetProvider}
 * (one line: the fully-qualified implementation class name). No changes to the
 * widgets module or to lg3d-core are required - drop the jar on the desktop
 * classpath and its widgets appear in the gallery.</p>
 *
 * <p>Implementations must have a public no-argument constructor and should return
 * quickly; {@link #descriptors()} is typically called once at startup.</p>
 */
public interface WidgetProvider {

    /**
     * The widget types this provider contributes. Must not return null; return an
     * empty list to contribute nothing.
     */
    List<WidgetDescriptor> descriptors();
}
