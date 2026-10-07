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

import java.util.Locale;
import java.util.Set;
import org.jdesktop.lg3d.apps.webbrowser.ext.BrowserExtension;
import org.jdesktop.lg3d.apps.webbrowser.ext.ExtensionManifest;
import org.jdesktop.lg3d.apps.webbrowser.ext.NavigationDecision;
import org.jdesktop.lg3d.apps.webbrowser.ext.NavigationRequest;
import org.jdesktop.lg3d.apps.webbrowser.ext.Permission;

/**
 * A first-party reference extension that upgrades plain-{@code http}
 * navigations to {@code https}, demonstrating the {@link NavigationDecision}
 * REDIRECT verdict. Only {@link Permission#NAVIGATE} is required.
 *
 * <p>{@code localhost}, loopback and link-local hosts are left alone so local
 * development servers over http keep working.</p>
 */
public final class HttpsUpgradeExtension implements BrowserExtension {

    private static final ExtensionManifest MANIFEST = new ExtensionManifest(
            "lg3d.https-upgrade",
            "HTTPS Upgrade",
            "1.0.0",
            "Upgrades http navigations to https where applicable.",
            "Project Looking Glass",
            Set.of(Permission.NAVIGATE));

    private static final String HTTP_PREFIX = "http://";

    @Override
    public ExtensionManifest manifest() {
        return MANIFEST;
    }

    @Override
    public NavigationDecision onNavigate(NavigationRequest request) {
        if (request == null) {
            return NavigationDecision.allow();
        }
        String upgraded = upgrade(request.getUrl());
        return (upgraded == null)
                ? NavigationDecision.allow()
                : NavigationDecision.redirect(upgraded);
    }

    /**
     * @param url the requested URL
     * @return the https equivalent, or null when no upgrade applies
     */
    static String upgrade(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (!trimmed.regionMatches(true, 0, HTTP_PREFIX, 0, HTTP_PREFIX.length())) {
            return null;
        }
        String host = TrackerBlockerExtension.hostOf(trimmed);
        if (isLocal(host)) {
            return null;
        }
        return "https://" + trimmed.substring(HTTP_PREFIX.length());
    }

    private static boolean isLocal(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost")
                || h.equals("127.0.0.1")
                || h.equals("0.0.0.0")
                || h.equals("[::1]")
                || h.startsWith("192.168.")
                || h.startsWith("10.")
                || h.startsWith("169.254.");
    }
}
