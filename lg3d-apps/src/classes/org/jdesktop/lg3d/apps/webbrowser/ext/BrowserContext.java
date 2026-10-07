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

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The capability facade the browser hands an extension at
 * {@link BrowserExtension#onBrowserStarted}. Every capability is gated on the
 * permissions the user granted: calling a method whose permission was not
 * granted is a silent no-op (and {@link #has(Permission)} reports the truth),
 * so an over-reaching extension degrades gracefully instead of misbehaving.
 *
 * <p>The delegates are wired by the broker to the live {@code FxBrowser}; in
 * headless tests they are stubs, which keeps this type AWT/JavaFX-free.</p>
 */
public final class BrowserContext {

    private final Set<Permission> granted;
    private final Consumer<String> openTab;
    private final Consumer<String> navigate;
    private final Consumer<String> log;

    /**
     * @param granted  the permissions the user granted this extension
     * @param openTab  opens a new tab at a URL (gated on {@link Permission#NAVIGATE})
     * @param navigate navigates the active tab to a URL (gated on NAVIGATE)
     * @param log      writes a line to the browser status/log (always allowed)
     */
    public BrowserContext(Set<Permission> granted, Consumer<String> openTab,
                          Consumer<String> navigate, Consumer<String> log) {
        this.granted = (granted == null || granted.isEmpty())
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(granted));
        this.openTab = openTab;
        this.navigate = navigate;
        this.log = log;
    }

    /** @return true when the extension was granted {@code p}. */
    public boolean has(Permission p) {
        return p != null && granted.contains(p);
    }

    /** Opens a new tab at {@code url}; no-op without {@link Permission#NAVIGATE}. */
    public void openTab(String url) {
        if (has(Permission.NAVIGATE) && openTab != null && url != null) {
            openTab.accept(url);
        }
    }

    /** Navigates the active tab to {@code url}; no-op without NAVIGATE. */
    public void navigate(String url) {
        if (has(Permission.NAVIGATE) && navigate != null && url != null) {
            navigate.accept(url);
        }
    }

    /** Writes {@code message} to the browser status line; always allowed. */
    public void log(String message) {
        if (log != null && message != null) {
            log.accept(message);
        }
    }
}
