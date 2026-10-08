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
package org.jdesktop.lg3d.apps.webbrowser;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-site counters of what the browser's protections blocked, shown in the
 * site-info popup: navigations blocked and popups blocked, keyed by host. The
 * {@code FxBrowser} records a hit whenever the extension broker vetoes a
 * navigation or a popup; the panel reads the counts for the current host.
 *
 * <p>Free of AWT/JavaFX and internally synchronised (the counters are written on
 * the JavaFX thread and read on the EDT), so the logic is unit-tested headlessly.</p>
 */
public final class SiteStats {

    private static final int NAV = 0;
    private static final int POPUP = 1;
    private static final int SLOTS = 2;

    private final Map<String, int[]> counts = new HashMap<>();

    /** An immutable snapshot of one host's blocked counts. */
    public static final class Counts {
        private final int navigations;
        private final int popups;

        Counts(int navigations, int popups) {
            this.navigations = navigations;
            this.popups = popups;
        }

        /** @return navigations blocked for the host. */
        public int getNavigationsBlocked() {
            return navigations;
        }

        /** @return popups blocked for the host. */
        public int getPopupsBlocked() {
            return popups;
        }

        /** @return the sum of all blocked events for the host. */
        public int getTotalBlocked() {
            return navigations + popups;
        }

        @Override
        public String toString() {
            return "nav=" + navigations + " popup=" + popups;
        }
    }

    /** Records a blocked navigation for {@code urlOrHost}'s host. */
    public void recordNavigationBlocked(String urlOrHost) {
        bump(urlOrHost, NAV);
    }

    /** Records a blocked popup for {@code urlOrHost}'s host. */
    public void recordPopupBlocked(String urlOrHost) {
        bump(urlOrHost, POPUP);
    }

    /**
     * Returns the counts for {@code urlOrHost}. Accepts either an absolute URL
     * (its host is extracted) or a bare host. An unknown host yields all-zero
     * counts, never null.
     *
     * @param urlOrHost the URL or host to look up (may be null)
     * @return the immutable counts snapshot, never null
     */
    public synchronized Counts forUrl(String urlOrHost) {
        int[] slot = counts.get(hostKey(urlOrHost));
        if (slot == null) {
            return new Counts(0, 0);
        }
        return new Counts(slot[NAV], slot[POPUP]);
    }

    /** Forgets every host's counts. */
    public synchronized void clear() {
        counts.clear();
    }

    /** Forgets one host's counts. */
    public synchronized void clear(String urlOrHost) {
        counts.remove(hostKey(urlOrHost));
    }

    private synchronized void bump(String urlOrHost, int index) {
        String key = hostKey(urlOrHost);
        if (key.isEmpty()) {
            return;
        }
        counts.computeIfAbsent(key, k -> new int[SLOTS])[index]++;
    }

    private static String hostKey(String urlOrHost) {
        if (urlOrHost == null || urlOrHost.isBlank()) {
            return "";
        }
        String host = UrlNormalizer.hostOf(urlOrHost);
        // hostOf returns "" for a bare host; fall back to the trimmed input.
        return host.isEmpty() ? urlOrHost.trim().toLowerCase() : host;
    }
}
