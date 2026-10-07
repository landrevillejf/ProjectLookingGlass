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

import java.util.List;

/**
 * The Web Browser extension SPI. Developers implement this interface and
 * register the implementation with {@code META-INF/services} (either on the
 * application classpath for a built-in, or inside a jar dropped into
 * {@code ~/.lg3d/webbrowser/extensions} for a third-party extension); the
 * browser discovers it with {@link java.util.ServiceLoader}.
 *
 * <p>Every hook except {@link #manifest()} has a sensible default, so an
 * extension implements only the behaviour it needs. Hooks are invoked by the
 * {@code ExtensionBroker} on the browser's threads (navigation / popup /
 * page-loaded on the JavaFX Application Thread, toolbar contributions realised
 * on the EDT) and are individually guarded: an extension that throws is logged
 * and skipped without disturbing the browser or the other extensions.</p>
 *
 * <p>Capabilities are gated on the {@link Permission permissions} the user
 * granted in the extension manager; see {@link BrowserContext} and
 * {@link PageContext#executeScript(String)}.</p>
 */
public interface BrowserExtension {

    /**
     * @return this extension's identity, blurb and required permissions; never null
     */
    ExtensionManifest manifest();

    /**
     * Called once on the EDT after the browser is up and this extension is
     * enabled, with a capability facade scoped to the granted permissions.
     *
     * @param ctx the browser capability facade
     */
    default void onBrowserStarted(BrowserContext ctx) { }

    /**
     * Called on the EDT when the browser is shutting down, so the extension can
     * release resources.
     *
     * @param ctx the browser capability facade
     */
    default void onBrowserStopping(BrowserContext ctx) { }

    /**
     * Consulted before a navigation commits. Requires
     * {@link Permission#NAVIGATE}; the first non-ALLOW verdict wins.
     *
     * @param request the navigation about to happen
     * @return ALLOW (default), BLOCK, or REDIRECT to a different URL
     */
    default NavigationDecision onNavigate(NavigationRequest request) {
        return NavigationDecision.allow();
    }

    /**
     * Consulted when a page requests a popup. Requires
     * {@link Permission#POPUP}; any BLOCK suppresses the popup.
     *
     * @param request the popup about to open
     * @return ALLOW (default) or BLOCK
     */
    default PopupDecision onPopup(PopupRequest request) {
        return PopupDecision.ALLOW;
    }

    /**
     * Called after a page commits, the content-script injection point. Inject
     * JavaScript with {@link PageContext#executeScript(String)} (requires
     * {@link Permission#CONTENT_SCRIPT}).
     *
     * @param page the loaded page
     */
    default void onPageLoaded(PageContext page) { }

    /**
     * Toolbar buttons this extension contributes. Requires
     * {@link Permission#TOOLBAR}; rendered on the EDT.
     *
     * @return the contributions, empty by default
     */
    default List<ToolbarContribution> toolbarContributions() {
        return List.of();
    }
}
