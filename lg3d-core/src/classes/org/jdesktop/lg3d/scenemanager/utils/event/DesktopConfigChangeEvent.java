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
package org.jdesktop.lg3d.scenemanager.utils.event;

import org.jdesktop.lg3d.wg.event.LgEvent;

/**
 * Posted after the user changes the desktop configuration (see
 * {@link org.jdesktop.lg3d.utils.prefs.DesktopConfig}). It carries no payload:
 * listeners re-read the {@code DesktopConfig} singleton and re-apply, mirroring
 * the {@link BackgroundChangeRequestEvent} bridge between the Control Center
 * (lg3d-demo-apps) and the scene-manager taskbar (lg3d-core).
 */
public class DesktopConfigChangeEvent extends LgEvent {
    // just a tag class
}
