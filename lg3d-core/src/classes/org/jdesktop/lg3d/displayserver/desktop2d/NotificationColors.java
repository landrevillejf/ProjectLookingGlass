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

/**
 * The single source of truth for the colour a notification's severity maps to,
 * shared by the {@link ToastLayer} accent bar and the {@link NotificationTray}
 * menu entries so a warning is the same amber everywhere.
 *
 * <p>Kept apart from {@link Notification} so that value object stays free of any
 * AWT dependency, and from the two views so neither owns a palette the other has
 * to duplicate.</p>
 */
final class NotificationColors {

    /** Ordinary information: a calm blue. */
    static final Color INFO = new Color(90, 160, 230);
    /** Something to notice but not fatal: amber. */
    static final Color WARNING = new Color(230, 180, 70);
    /** A failure: red. */
    static final Color ERROR = new Color(220, 90, 90);

    private NotificationColors() {
        // No instances: this is a static palette lookup.
    }

    /**
     * The accent colour for {@code kind}; null is treated as
     * {@link Notification.Kind#INFO}.
     */
    static Color accentFor(Notification.Kind kind) {
        if (kind == null) {
            return INFO;
        }
        switch (kind) {
            case WARNING:
                return WARNING;
            case ERROR:
                return ERROR;
            case INFO:
            default:
                return INFO;
        }
    }
}
