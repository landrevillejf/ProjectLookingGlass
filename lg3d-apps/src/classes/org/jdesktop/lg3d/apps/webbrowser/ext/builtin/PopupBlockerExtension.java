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
package org.jdesktop.lg3d.apps.webbrowser.ext.builtin;

import java.util.EnumSet;
import java.util.Set;
import org.jdesktop.lg3d.apps.webbrowser.ext.BrowserExtension;
import org.jdesktop.lg3d.apps.webbrowser.ext.ExtensionManifest;
import org.jdesktop.lg3d.apps.webbrowser.ext.Permission;
import org.jdesktop.lg3d.apps.webbrowser.ext.PopupDecision;
import org.jdesktop.lg3d.apps.webbrowser.ext.PopupRequest;

/**
 * A first-party reference extension, and the canonical example of the
 * {@link BrowserExtension} SPI: it suppresses popups that were not triggered by
 * a direct user gesture (the classic {@code window.open}-on-load ad), while
 * letting user-initiated popups through.
 *
 * <p>Registered via {@code META-INF/services} so it is discovered by
 * {@link java.util.ServiceLoader} exactly like a third-party jar, exercising the
 * same code path. It needs only {@link Permission#POPUP}.</p>
 */
public final class PopupBlockerExtension implements BrowserExtension {

    private static final ExtensionManifest MANIFEST = new ExtensionManifest(
            "lg3d.popup-blocker",
            "Popup Blocker",
            "1.0.0",
            "Blocks popups that were not triggered by a direct user gesture.",
            "Project Looking Glass",
            Set.of(Permission.POPUP));

    @Override
    public ExtensionManifest manifest() {
        return MANIFEST;
    }

    @Override
    public PopupDecision onPopup(PopupRequest request) {
        // Allow anything the user actually asked for; suppress the rest.
        return (request != null && request.isUserInitiated())
                ? PopupDecision.ALLOW
                : PopupDecision.BLOCK;
    }

    /** @return the permissions this built-in declares, for tests/tooling. */
    public static Set<Permission> declaredPermissions() {
        return EnumSet.of(Permission.POPUP);
    }
}
