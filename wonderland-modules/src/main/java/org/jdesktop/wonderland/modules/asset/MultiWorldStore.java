/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules (world library): a {@link WorldStore} spanning MANY modules.
 *
 * A stock world does not keep all of its art to itself. The Open Wonderland
 * "combined" world is a world OF worlds: it places cells whose deployed models
 * live in the auditorium, town, gallery, japanese pavilion, meeting complex and
 * almost-empty jars -- {@code wla://wonderland-auditorium-world/Auditorium.kmz/...}
 * and so on, thirty-two of its forty-one cells. The 2006 server resolved those
 * because every world module was installed and the asset manager keyed a store by
 * module name; a single {@link JarWorldStore} cannot, because it ignores the
 * {@code module} argument and reads only its own bytes -- so every cross-module
 * reference missed and the combined world loaded with most of its objects missing
 * (an "empty world" that in fact had its cells, just not the models they name).
 *
 * This store is that missing key. It holds one {@link JarWorldStore} per installed
 * world module and forwards {@code open}/{@code present}/{@code listAssets} to the
 * store named by the reference's module, falling back to a shared store (the
 * classpath, for a bundled world) for a module no jar carries. The loader already
 * hands the referenced module to {@link WorldStore#open} -- it was the store, not
 * the loader, that could not answer -- so wiring this in needs no change to
 * {@link WorldLoader} or {@link ModelLoader}.
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link WorldStore} that routes each read to the store owning the named module,
 * so a world may place cells whose assets live in another installed world's jar.
 *
 * <p>The caller opens one jar per installed module through {@link #add} and closes
 * the composite (which closes them) when the load ends; it is therefore
 * {@link AutoCloseable} on the same terms as {@link JarWorldStore}.</p>
 */
public final class MultiWorldStore implements WorldStore, AutoCloseable {

    private final Map<String, JarWorldStore> byModule = new LinkedHashMap<>();
    private WorldStore fallback;

    /**
     * Register one installed world jar under its module name. The opened store is
     * owned by this composite and closed by {@link #close()}.
     *
     * @throws IOException when the jar cannot be opened for reading
     */
    public void add(String module, Path jarPath) throws IOException {
        byModule.put(module, new JarWorldStore(jarPath));
    }

    /**
     * The store consulted for a module no registered jar carries -- the classpath
     * store, so a bundled world's cells still resolve through the same composite.
     */
    public void fallback(WorldStore store) {
        this.fallback = store;
    }

    /** The store that answers a reference to {@code module}: its own jar, else the fallback. */
    private WorldStore route(String module) {
        JarWorldStore own = module == null ? null : byModule.get(module);
        return own != null ? own : fallback;
    }

    /** Whether a jar is registered for {@code module} (the fallback alone does not count). */
    public boolean hasModule(String module) {
        return module != null && byModule.containsKey(module);
    }

    @Override
    public Optional<InputStream> open(String module, String path) {
        WorldStore store = route(module);
        return store == null ? Optional.empty() : store.open(module, path);
    }

    @Override
    public boolean present(String module, String path) {
        WorldStore store = route(module);
        return store != null && store.present(module, path);
    }

    @Override
    public List<String> listAssets(String module, String suffix) {
        WorldStore store = route(module);
        return store == null ? List.of() : store.listAssets(module, suffix);
    }

    /** Close every registered jar store; a store that fails to close is ignored. */
    @Override
    public void close() {
        for (JarWorldStore store : byModule.values()) {
            store.close();
        }
        byModule.clear();
    }
}
