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

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jdesktop.lg3d.apps.webbrowser.ext.BrowserExtension;
import org.jdesktop.lg3d.apps.webbrowser.ext.ExtensionManifest;
import org.jdesktop.lg3d.apps.webbrowser.ext.NavigationDecision;
import org.jdesktop.lg3d.apps.webbrowser.ext.NavigationRequest;
import org.jdesktop.lg3d.apps.webbrowser.ext.PageContext;
import org.jdesktop.lg3d.apps.webbrowser.ext.Permission;

/**
 * A first-party reference extension that vetoes navigations to a small bundled
 * list of well-known tracker/ad hosts, and (when granted
 * {@link Permission#CONTENT_SCRIPT}) hides the common ad containers on the
 * rendered page.
 *
 * <p>The blocklist is intentionally tiny and static &mdash; a real deployment
 * would ship and periodically refresh a community list. It demonstrates both a
 * {@code onNavigate} veto and a {@code onPageLoaded} content-script.</p>
 */
public final class TrackerBlockerExtension implements BrowserExtension {

    private static final ExtensionManifest MANIFEST = new ExtensionManifest(
            "lg3d.tracker-blocker",
            "Tracker Blocker",
            "1.0.0",
            "Blocks known tracker/ad hosts and hides common ad containers.",
            "Project Looking Glass",
            Set.of(Permission.NAVIGATE, Permission.CONTENT_SCRIPT));

    /** Hostnames (and their subdomains) treated as trackers. */
    private static final List<String> BLOCKED_HOSTS = List.of(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "googletagmanager.com",
            "google-analytics.com",
            "adservice.google.com",
            "facebook.net",
            "scorecardresearch.com",
            "adnxs.com",
            "criteo.com");

    /** Injected CSS that collapses the most common ad/tracker containers. */
    private static final String HIDE_ADS_JS =
            "(function(){try{var s=document.createElement('style');"
            + "s.textContent='[id^=\"google_ads\"],[class*=\"adsbygoogle\"],"
            + "[id^=\"div-gpt-ad\"],[class*=\"ad-slot\"],[class*=\"banner-ad\"]"
            + "{display:none !important;}';"
            + "(document.head||document.documentElement).appendChild(s);}"
            + "catch(e){}})();";

    @Override
    public ExtensionManifest manifest() {
        return MANIFEST;
    }

    @Override
    public NavigationDecision onNavigate(NavigationRequest request) {
        if (request == null) {
            return NavigationDecision.allow();
        }
        return isBlockedHost(hostOf(request.getUrl()))
                ? NavigationDecision.block()
                : NavigationDecision.allow();
    }

    @Override
    public void onPageLoaded(PageContext page) {
        if (page != null) {
            page.executeScript(HIDE_ADS_JS);
        }
    }

    /**
     * @param host the request host (already lower-cased, no port)
     * @return true when the host is, or lives under, a blocked tracker domain
     */
    static boolean isBlockedHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        for (String blocked : BLOCKED_HOSTS) {
            if (host.equals(blocked) || host.endsWith("." + blocked)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts the lower-cased host from a URL, tolerating malformed input.
     *
     * @param url the URL to inspect
     * @return the host (no port), or "" when it cannot be determined
     */
    static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String rest = url.trim();
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        int query = rest.indexOf('?');
        if (query >= 0) {
            rest = rest.substring(0, query);
        }
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        int colon = rest.indexOf(':');
        if (colon >= 0) {
            rest = rest.substring(0, colon);
        }
        return rest.toLowerCase(Locale.ROOT);
    }
}
