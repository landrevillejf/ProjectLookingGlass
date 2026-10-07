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
package org.jdesktop.lg3d.apps.webbrowser.ext;

/**
 * A popup (a {@code window.open} or {@code target=_blank} request) about to be
 * opened, offered to extensions through {@link BrowserExtension#onPopup}.
 */
public final class PopupRequest {

    private final String openerUrl;
    private final boolean userInitiated;

    /**
     * @param openerUrl     the URL of the page requesting the popup
     * @param userInitiated true when the popup follows a direct user gesture
     */
    public PopupRequest(String openerUrl, boolean userInitiated) {
        this.openerUrl = (openerUrl == null) ? "" : openerUrl;
        this.userInitiated = userInitiated;
    }

    public String getOpenerUrl() { return openerUrl; }
    public boolean isUserInitiated() { return userInitiated; }

    @Override
    public String toString() {
        return "PopupRequest[from " + openerUrl + " userInitiated=" + userInitiated + "]";
    }
}
