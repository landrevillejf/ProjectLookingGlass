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

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.jdesktop.lg3d.apps.webbrowser.BrowserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers and tracks {@link BrowserExtension} implementations, and holds the
 * user's enable / permission-grant decisions for each.
 *
 * <p>Two sources are scanned by {@link #scan()}: <em>built-ins</em> registered
 * via {@code META-INF/services} on the application classpath (first-party, so
 * they start enabled with their declared permissions pre-granted), and
 * <em>third-party</em> jars dropped into the extensions directory
 * ({@code ~/.lg3d/webbrowser/extensions} by default), each loaded in its own
 * child {@link URLClassLoader}. A third-party extension starts <b>disabled</b>
 * with nothing granted until the user approves it in the extension manager
 * &mdash; that approval is the permission gate.</p>
 *
 * <p>State (enabled + granted permissions + source) is persisted through
 * {@link BrowserStore} as {@code extensions.json}, so approvals survive
 * restarts. All reads are defensive: a corrupt jar or a manifest that throws
 * is logged and skipped, never fatal.</p>
 */
public final class ExtensionRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ExtensionRegistry.class);

    /** One discovered extension plus the user's decisions about it. */
    public static final class LoadedExtension {
        private final BrowserExtension extension;
        private final ExtensionManifest manifest;
        private final boolean builtin;
        private final String sourcePath;
        private volatile boolean enabled;
        private volatile Set<Permission> granted;

        LoadedExtension(BrowserExtension extension, ExtensionManifest manifest,
                        boolean builtin, String sourcePath, boolean enabled,
                        Set<Permission> granted) {
            this.extension = extension;
            this.manifest = manifest;
            this.builtin = builtin;
            this.sourcePath = sourcePath;
            this.enabled = enabled;
            this.granted = granted;
        }

        public BrowserExtension getExtension() { return extension; }
        public ExtensionManifest getManifest() { return manifest; }
        public boolean isBuiltin() { return builtin; }
        /** @return the jar path, or "" for a classpath built-in. */
        public String getSourcePath() { return sourcePath; }
        public boolean isEnabled() { return enabled; }
        /** @return the granted permissions, never null. */
        public Set<Permission> getGranted() { return granted; }
        /** @return true when enabled and granted {@code p}. */
        public boolean has(Permission p) { return enabled && p != null && granted.contains(p); }

        void setEnabled(boolean enabled) { this.enabled = enabled; }
        void setGranted(Set<Permission> granted) { this.granted = granted; }
    }

    private final BrowserStore store;
    private final Path extensionsDir;
    private final List<LoadedExtension> loaded = new CopyOnWriteArrayList<>();
    private final List<URLClassLoader> loaders = new ArrayList<>();

    /** Creates a registry using the default extensions directory. */
    public ExtensionRegistry(BrowserStore store) {
        this(store, defaultExtensionsDir());
    }

    /**
     * @param store         persistence for enable/grant state
     * @param extensionsDir directory scanned for third-party extension jars
     */
    public ExtensionRegistry(BrowserStore store, Path extensionsDir) {
        this.store = store;
        this.extensionsDir = extensionsDir;
    }

    /** @return {@code ~/.lg3d/webbrowser/extensions} (or the store override). */
    public static Path defaultExtensionsDir() {
        return BrowserStore.defaultConfigDir().resolve("extensions");
    }

    /** @return the directory scanned for third-party jars. */
    public Path getExtensionsDir() { return extensionsDir; }

    /**
     * (Re)discovers extensions from the classpath and the extensions directory,
     * applying persisted enable/grant state. Safe to call again to rescan.
     */
    public synchronized void scan() {
        closeLoaders();
        loaded.clear();
        Map<String, ExtensionState> byId = indexStates();
        for (BrowserExtension ext : ServiceLoader.load(BrowserExtension.class)) {
            adopt(ext, true, "", byId);
        }
        for (Path jar : listJars()) {
            URLClassLoader loader = openLoader(jar);
            if (loader == null) {
                continue;
            }
            loaders.add(loader);
            for (BrowserExtension ext : ServiceLoader.load(BrowserExtension.class, loader)) {
                adopt(ext, false, jar.toString(), byId);
            }
        }
        LOG.info("Extension registry scanned {} extension(s) from {} and {}",
                loaded.size(), "classpath", extensionsDir);
    }

    /** @return every discovered extension, built-ins first. */
    public List<LoadedExtension> extensions() {
        return List.copyOf(loaded);
    }

    /** @return only the enabled extensions. */
    public List<LoadedExtension> enabled() {
        List<LoadedExtension> out = new ArrayList<>();
        for (LoadedExtension le : loaded) {
            if (le.isEnabled()) {
                out.add(le);
            }
        }
        return out;
    }

    /**
     * Enables or disables an extension and persists the decision. Enabling
     * grants the extension's declared permissions (the manager's enable is the
     * user's approval); disabling keeps the grants but stops the hooks.
     *
     * @param id      the extension id
     * @param enabled the new enabled flag
     */
    public synchronized void setEnabled(String id, boolean enabled) {
        for (LoadedExtension le : loaded) {
            if (le.getManifest().getId().equals(id)) {
                le.setEnabled(enabled);
                if (enabled && le.getGranted().isEmpty()) {
                    le.setGranted(EnumSet.copyOf(le.getManifest().getPermissions().isEmpty()
                            ? EnumSet.noneOf(Permission.class)
                            : le.getManifest().getPermissions()));
                }
                persist();
                return;
            }
        }
    }

    /**
     * Explicitly sets the granted permissions for an extension and persists.
     *
     * @param id   the extension id
     * @param perms the permissions to grant (null clears)
     */
    public synchronized void grant(String id, Set<Permission> perms) {
        for (LoadedExtension le : loaded) {
            if (le.getManifest().getId().equals(id)) {
                le.setGranted((perms == null || perms.isEmpty())
                        ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(perms));
                persist();
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Test seam (package-private): seeds an already-constructed extension with
     * explicit enable/grant state, bypassing {@link #scan()}. This lets the
     * broker's dispatch, isolation and permission logic be exercised without jar
     * or filesystem IO. Not part of the public API.
     */
    void seed(BrowserExtension ext, boolean builtin, boolean enabled, Set<Permission> granted) {
        loaded.add(new LoadedExtension(ext, ext.manifest(), builtin, "", enabled, copyOf(granted)));
    }

    private void adopt(BrowserExtension ext, boolean builtin, String path,
                       Map<String, ExtensionState> byId) {
        try {
            ExtensionManifest manifest = ext.manifest();
            if (manifest == null) {
                LOG.warn("Extension {} returned a null manifest; skipped", ext.getClass());
                return;
            }
            ExtensionState state = byId.get(manifest.getId());
            boolean enabled;
            Set<Permission> granted;
            if (state != null) {
                enabled = state.isEnabled();
                granted = parsePermissions(state.getGrantedPermissions());
            } else {
                enabled = builtin;
                granted = builtin ? copyOf(manifest.getPermissions())
                        : EnumSet.noneOf(Permission.class);
            }
            loaded.add(new LoadedExtension(ext, manifest, builtin, path, enabled, granted));
        } catch (RuntimeException | ServiceConfigurationError e) {
            LOG.warn("Could not load extension {}; skipped", ext.getClass(), e);
        }
    }

    private Map<String, ExtensionState> indexStates() {
        Map<String, ExtensionState> byId = new HashMap<>();
        for (ExtensionState s : store.loadExtensionStates()) {
            if (s.getId() != null && !s.getId().isBlank()) {
                byId.put(s.getId(), s);
            }
        }
        return byId;
    }

    private List<Path> listJars() {
        List<Path> jars = new ArrayList<>();
        if (extensionsDir == null || !Files.isDirectory(extensionsDir)) {
            return jars;
        }
        try (Stream<Path> stream = Files.list(extensionsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(jars::add);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not list extensions dir {}", extensionsDir, e);
        }
        return jars;
    }

    private URLClassLoader openLoader(Path jar) {
        try {
            URL[] urls = { jar.toUri().toURL() };
            return new URLClassLoader(urls, getClass().getClassLoader());
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not open extension jar {}; skipped", jar, e);
            return null;
        }
    }

    private void closeLoaders() {
        for (URLClassLoader loader : loaders) {
            try {
                loader.close();
            } catch (IOException | RuntimeException ignored) {
                // best effort
            }
        }
        loaders.clear();
    }

    private void persist() {
        List<ExtensionState> states = new ArrayList<>();
        for (LoadedExtension le : loaded) {
            ExtensionState s = new ExtensionState();
            s.setId(le.getManifest().getId());
            s.setEnabled(le.isEnabled());
            List<String> names = new ArrayList<>();
            for (Permission p : le.getGranted()) {
                names.add(p.name());
            }
            s.setGrantedPermissions(names);
            s.setSource(le.getSourcePath());
            states.add(s);
        }
        store.saveExtensionStates(states);
    }

    private static Set<Permission> parsePermissions(List<String> names) {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        if (names == null) {
            return out;
        }
        for (String name : names) {
            try {
                out.add(Permission.valueOf(name));
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // unknown permission name: drop it
            }
        }
        return out;
    }

    private static Set<Permission> copyOf(Set<Permission> perms) {
        return (perms == null || perms.isEmpty())
                ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(perms);
    }
}
