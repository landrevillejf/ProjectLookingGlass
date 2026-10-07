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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.jdesktop.lg3d.apps.webbrowser.ext.ExtensionRegistry.LoadedExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The runtime that drives {@link BrowserExtension} hooks on behalf of
 * {@code FxBrowser}. It is the single place that (a) asks the
 * {@link ExtensionRegistry} which extensions are enabled, (b) enforces the
 * granted-permission gate per hook, and (c) isolates failures so a throwing
 * extension is logged and skipped without disturbing the browser or the other
 * extensions.
 *
 * <p>Navigation, popup and page-loaded hooks are called on the JavaFX
 * Application Thread (where {@code FxBrowser} lives); lifecycle and toolbar
 * contributions are realised on the EDT by the panel. The broker itself is
 * stateless and thread-agnostic.</p>
 */
public final class ExtensionBroker {

    private static final Logger LOG = LoggerFactory.getLogger(ExtensionBroker.class);

    private final ExtensionRegistry registry;
    private final Consumer<String> openTab;
    private final Consumer<String> navigate;
    private final Consumer<String> log;

    /**
     * @param registry the enabled/grant source
     * @param openTab  delegate for {@link BrowserContext#openTab} (wired to FxBrowser)
     * @param navigate delegate for {@link BrowserContext#navigate}
     * @param log      delegate for {@link BrowserContext#log} (status line)
     */
    public ExtensionBroker(ExtensionRegistry registry, Consumer<String> openTab,
                           Consumer<String> navigate, Consumer<String> log) {
        this.registry = registry;
        this.openTab = openTab;
        this.navigate = navigate;
        this.log = log;
    }

    /** Fires {@link BrowserExtension#onBrowserStarted} for enabled extensions. */
    public void notifyStarted() {
        for (LoadedExtension le : registry.enabled()) {
            try {
                le.getExtension().onBrowserStarted(contextFor(le));
            } catch (RuntimeException e) {
                LOG.warn("Extension {} onBrowserStarted failed; ignored",
                        le.getManifest().getId(), e);
            }
        }
    }

    /** Fires {@link BrowserExtension#onBrowserStopping} for enabled extensions. */
    public void notifyStopping() {
        for (LoadedExtension le : registry.enabled()) {
            try {
                le.getExtension().onBrowserStopping(contextFor(le));
            } catch (RuntimeException e) {
                LOG.warn("Extension {} onBrowserStopping failed; ignored",
                        le.getManifest().getId(), e);
            }
        }
    }

    /**
     * Consults enabled {@link Permission#NAVIGATE} extensions about a pending
     * navigation. The first non-ALLOW verdict wins; a throwing extension is
     * treated as ALLOW.
     *
     * @param request the navigation about to happen
     * @return the effective decision, never null (ALLOW when nobody objects)
     */
    public NavigationDecision onNavigate(NavigationRequest request) {
        for (LoadedExtension le : registry.enabled()) {
            if (!le.has(Permission.NAVIGATE)) {
                continue;
            }
            try {
                NavigationDecision d = le.getExtension().onNavigate(request);
                if (d != null && !d.isAllow()) {
                    return d;
                }
            } catch (RuntimeException e) {
                LOG.warn("Extension {} onNavigate failed; treated as ALLOW",
                        le.getManifest().getId(), e);
            }
        }
        return NavigationDecision.allow();
    }

    /**
     * Consults enabled {@link Permission#POPUP} extensions about a popup.
     *
     * @param request the popup about to open
     * @return true when any enabled extension blocks it
     */
    public boolean isPopupBlocked(PopupRequest request) {
        for (LoadedExtension le : registry.enabled()) {
            if (!le.has(Permission.POPUP)) {
                continue;
            }
            try {
                if (le.getExtension().onPopup(request) == PopupDecision.BLOCK) {
                    return true;
                }
            } catch (RuntimeException e) {
                LOG.warn("Extension {} onPopup failed; treated as ALLOW",
                        le.getManifest().getId(), e);
            }
        }
        return false;
    }

    /**
     * Fires {@link BrowserExtension#onPageLoaded} for enabled extensions. The
     * script runner is only live for extensions granted
     * {@link Permission#CONTENT_SCRIPT}; everyone else gets a silent no-op.
     *
     * @param url          the committed page URL
     * @param title        the page title
     * @param scriptRunner runs injected JS against the live engine
     */
    public void notifyPageLoaded(String url, String title, Consumer<String> scriptRunner) {
        for (LoadedExtension le : registry.enabled()) {
            Consumer<String> runner = le.has(Permission.CONTENT_SCRIPT)
                    ? scriptRunner : js -> { };
            PageContext page = new PageContext(url, title, runner);
            try {
                le.getExtension().onPageLoaded(page);
            } catch (RuntimeException e) {
                LOG.warn("Extension {} onPageLoaded failed; ignored",
                        le.getManifest().getId(), e);
            }
        }
    }

    /**
     * Collects toolbar buttons from enabled {@link Permission#TOOLBAR}
     * extensions, de-duplicated by contribution id.
     *
     * @return the contributions to render, possibly empty
     */
    public List<ToolbarContribution> toolbarContributions() {
        List<ToolbarContribution> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LoadedExtension le : registry.enabled()) {
            if (!le.has(Permission.TOOLBAR)) {
                continue;
            }
            try {
                List<ToolbarContribution> contributions = le.getExtension().toolbarContributions();
                if (contributions == null) {
                    continue;
                }
                for (ToolbarContribution c : contributions) {
                    if (c != null && seen.add(c.getId())) {
                        out.add(c);
                    }
                }
            } catch (RuntimeException e) {
                LOG.warn("Extension {} toolbarContributions failed; ignored",
                        le.getManifest().getId(), e);
            }
        }
        return out;
    }

    private BrowserContext contextFor(LoadedExtension le) {
        return new BrowserContext(le.getGranted(), openTab, navigate, log);
    }
}
