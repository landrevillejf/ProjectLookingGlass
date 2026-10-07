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
 * An immutable navigation about to happen, offered to extensions through
 * {@link BrowserExtension#onNavigate} so they may allow, block or redirect it.
 */
public final class NavigationRequest {

    private final String url;
    private final int sourceTabId;

    /**
     * @param url         the URL about to be loaded
     * @param sourceTabId the id of the tab the navigation targets
     */
    public NavigationRequest(String url, int sourceTabId) {
        this.url = (url == null) ? "" : url;
        this.sourceTabId = sourceTabId;
    }

    public String getUrl() { return url; }
    public int getSourceTabId() { return sourceTabId; }

    @Override
    public String toString() {
        return "NavigationRequest[" + url + " tab=" + sourceTabId + "]";
    }
}
