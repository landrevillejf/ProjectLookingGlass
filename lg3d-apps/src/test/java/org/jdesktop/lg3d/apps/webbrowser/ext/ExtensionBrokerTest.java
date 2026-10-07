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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.jdesktop.lg3d.apps.webbrowser.BrowserStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link ExtensionBroker}: hook dispatch, first-non-ALLOW
 * navigation verdicts, popup blocking, permission enforcement (an extension only
 * influences a hook it was granted), content-script gating, toolbar collection
 * and &mdash; critically &mdash; failure isolation, so a throwing extension can
 * never break the browser or the other extensions.
 *
 * <p>Extensions are injected through the registry's package-private test seam,
 * so no jar or filesystem discovery is involved.</p>
 */
class ExtensionBrokerTest {

    private ExtensionRegistry registryWith(BrowserStore store, BrowserExtension... exts) {
        return registryWith(store, true, exts);
    }

    private ExtensionRegistry registryWith(BrowserStore store, boolean grantDeclared,
                                           BrowserExtension... exts) {
        ExtensionRegistry registry = new ExtensionRegistry(store, Path.of("."));
        for (BrowserExtension ext : exts) {
            Set<Permission> granted = grantDeclared
                    ? EnumSet.copyOf(ext.manifest().getPermissions().isEmpty()
                            ? EnumSet.noneOf(Permission.class) : ext.manifest().getPermissions())
                    : EnumSet.noneOf(Permission.class);
            registry.seed(ext, true, true, granted);
        }
        return registry;
    }

    private ExtensionBroker broker(ExtensionRegistry registry) {
        return new ExtensionBroker(registry, url -> { }, url -> { }, msg -> { });
    }

    @Test
    @DisplayName("a granted POPUP extension that blocks suppresses the popup")
    void popupBlocked(@TempDir Path config) {
        ExtensionBroker broker = broker(registryWith(new BrowserStore(config),
                new Fixed("blocker", Set.of(Permission.POPUP), null, PopupDecision.BLOCK)));
        assertTrue(broker.isPopupBlocked(new PopupRequest("https://a.com", false)));
    }

    @Test
    @DisplayName("a POPUP extension that was not granted POPUP cannot suppress anything")
    void popupPermissionEnforced(@TempDir Path config) {
        ExtensionBroker broker = broker(registryWith(new BrowserStore(config), false,
                new Fixed("blocker", Set.of(Permission.POPUP), null, PopupDecision.BLOCK)));
        assertFalse(broker.isPopupBlocked(new PopupRequest("https://a.com", false)),
                "no grant, no influence");
    }

    @Test
    @DisplayName("navigation is allowed when nobody objects")
    void navigateAllowed(@TempDir Path config) {
        ExtensionBroker broker = broker(registryWith(new BrowserStore(config),
                new Fixed("pass", Set.of(Permission.NAVIGATE), NavigationDecision.allow(), null)));
        assertTrue(broker.onNavigate(new NavigationRequest("https://a.com", 0)).isAllow());
    }

    @Test
    @DisplayName("a granted NAVIGATE extension can block or redirect")
    void navigateBlockAndRedirect(@TempDir Path config) {
        ExtensionBroker blocking = broker(registryWith(new BrowserStore(config),
                new Fixed("block", Set.of(Permission.NAVIGATE), NavigationDecision.block(), null)));
        assertTrue(blocking.onNavigate(new NavigationRequest("https://a.com", 0)).isBlock());

        ExtensionBroker redirecting = broker(registryWith(new BrowserStore(config),
                new Fixed("redir", Set.of(Permission.NAVIGATE),
                        NavigationDecision.redirect("https://safe.com"), null)));
        NavigationDecision d = redirecting.onNavigate(new NavigationRequest("http://a.com", 0));
        assertTrue(d.isRedirect());
        assertEquals("https://safe.com", d.getTargetUrl());
    }

    @Test
    @DisplayName("the first non-ALLOW verdict wins over a later ALLOW")
    void firstNonAllowWins(@TempDir Path config) {
        ExtensionBroker broker = broker(registryWith(new BrowserStore(config),
                new Fixed("block", Set.of(Permission.NAVIGATE), NavigationDecision.block(), null),
                new Fixed("pass", Set.of(Permission.NAVIGATE), NavigationDecision.allow(), null)));
        assertTrue(broker.onNavigate(new NavigationRequest("https://a.com", 0)).isBlock());
    }

    @Test
    @DisplayName("a throwing extension is isolated: hooks fall back safely")
    void exceptionIsolation(@TempDir Path config) {
        ExtensionBroker broker = broker(registryWith(new BrowserStore(config),
                new Throwing("boom", Set.of(Permission.NAVIGATE, Permission.POPUP,
                        Permission.CONTENT_SCRIPT)),
                new Fixed("block", Set.of(Permission.NAVIGATE), NavigationDecision.block(), null)));
        // The thrower is skipped; the healthy blocker still vetoes.
        assertTrue(broker.onNavigate(new NavigationRequest("https://a.com", 0)).isBlock());
        assertFalse(broker.isPopupBlocked(new PopupRequest("https://a.com", false)),
                "the throwing popup hook is treated as ALLOW");
        // notifyPageLoaded must not propagate the throw.
        broker.notifyPageLoaded("https://a.com", "t", js -> { });
        broker.notifyStarted();
        broker.notifyStopping();
    }

    @Test
    @DisplayName("the content-script runner is live only for a CONTENT_SCRIPT grant")
    void contentScriptGated(@TempDir Path config) {
        AtomicInteger ran = new AtomicInteger();
        Recording grantedExt = new Recording("granted",
                Set.of(Permission.CONTENT_SCRIPT), "injected();");
        Recording deniedExt = new Recording("denied", Set.of(Permission.NAVIGATE), "nope();");
        ExtensionRegistry registry = new ExtensionRegistry(new BrowserStore(config), Path.of("."));
        registry.seed(grantedExt, true, true, EnumSet.of(Permission.CONTENT_SCRIPT));
        registry.seed(deniedExt, true, true, EnumSet.of(Permission.NAVIGATE));

        broker(registry).notifyPageLoaded("https://a.com", "t", js -> ran.incrementAndGet());
        assertEquals(1, ran.get(), "only the CONTENT_SCRIPT extension's script actually runs");
    }

    @Test
    @DisplayName("toolbar contributions are collected from TOOLBAR grants and de-duplicated")
    void toolbarCollected(@TempDir Path config) {
        ExtensionRegistry registry = new ExtensionRegistry(new BrowserStore(config), Path.of("."));
        registry.seed(new Toolbar("a", Set.of(Permission.TOOLBAR), "btn"), true, true,
                EnumSet.of(Permission.TOOLBAR));
        // Same contribution id from a second extension is de-duplicated.
        registry.seed(new Toolbar("b", Set.of(Permission.TOOLBAR), "btn"), true, true,
                EnumSet.of(Permission.TOOLBAR));
        // A non-TOOLBAR extension's contribution is ignored.
        registry.seed(new Toolbar("c", Set.of(Permission.NAVIGATE), "hidden"), true, true,
                EnumSet.of(Permission.NAVIGATE));

        List<ToolbarContribution> contributions = broker(registry).toolbarContributions();
        assertEquals(1, contributions.size());
        assertEquals("btn", contributions.get(0).getId());
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /** Returns a fixed navigation / popup verdict. */
    private static final class Fixed implements BrowserExtension {
        private final ExtensionManifest manifest;
        private final NavigationDecision nav;
        private final PopupDecision popup;

        Fixed(String id, Set<Permission> perms, NavigationDecision nav, PopupDecision popup) {
            this.manifest = new ExtensionManifest(id, id, "1.0", "", "t", perms);
            this.nav = nav;
            this.popup = popup;
        }

        @Override
        public ExtensionManifest manifest() {
            return manifest;
        }

        @Override
        public NavigationDecision onNavigate(NavigationRequest request) {
            return (nav == null) ? NavigationDecision.allow() : nav;
        }

        @Override
        public PopupDecision onPopup(PopupRequest request) {
            return (popup == null) ? PopupDecision.ALLOW : popup;
        }
    }

    /** Throws from every hook, to prove isolation. */
    private static final class Throwing implements BrowserExtension {
        private final ExtensionManifest manifest;

        Throwing(String id, Set<Permission> perms) {
            this.manifest = new ExtensionManifest(id, id, "1.0", "", "t", perms);
        }

        @Override
        public ExtensionManifest manifest() {
            return manifest;
        }

        @Override
        public NavigationDecision onNavigate(NavigationRequest request) {
            throw new IllegalStateException("navigate boom");
        }

        @Override
        public PopupDecision onPopup(PopupRequest request) {
            throw new IllegalStateException("popup boom");
        }

        @Override
        public void onPageLoaded(PageContext page) {
            throw new IllegalStateException("page boom");
        }

        @Override
        public void onBrowserStarted(BrowserContext ctx) {
            throw new IllegalStateException("start boom");
        }

        @Override
        public void onBrowserStopping(BrowserContext ctx) {
            throw new IllegalStateException("stop boom");
        }
    }

    /** Injects a fixed script on page load. */
    private static final class Recording implements BrowserExtension {
        private final ExtensionManifest manifest;
        private final String script;
        private final List<String> injected = new ArrayList<>();

        Recording(String id, Set<Permission> perms, String script) {
            this.manifest = new ExtensionManifest(id, id, "1.0", "", "t", perms);
            this.script = script;
        }

        @Override
        public ExtensionManifest manifest() {
            return manifest;
        }

        @Override
        public void onPageLoaded(PageContext page) {
            page.executeScript(script);
            injected.add(script);
        }
    }

    /** Contributes one toolbar button. */
    private static final class Toolbar implements BrowserExtension {
        private final ExtensionManifest manifest;
        private final String id;

        Toolbar(String extId, Set<Permission> perms, String contributionId) {
            this.manifest = new ExtensionManifest(extId, extId, "1.0", "", "t", perms);
            this.id = contributionId;
        }

        @Override
        public ExtensionManifest manifest() {
            return manifest;
        }

        @Override
        public List<ToolbarContribution> toolbarContributions() {
            return List.of(new ToolbarContribution(id, id, id, () -> { }));
        }
    }
}
