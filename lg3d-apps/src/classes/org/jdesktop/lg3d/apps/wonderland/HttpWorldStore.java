/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland M8: the world store that reads over the wire.
 *
 * wonderland-modules already owns the asset contract -- {@code WorldStore} plus
 * {@code ModelLoader} turning a {@code wla://} reference into a decoded Collada
 * document and an inventory of which textures are actually there. Until now the
 * only implementations read a directory, a jar or the classpath, all of which
 * assume the world's files are on the same machine as the reader. The desktop
 * does not have them: the server does, behind {@code /api/world/asset}, which is
 * the same store the server's own catalog walks.
 *
 * So this is that endpoint behind that interface. Two rules make it safe to use
 * from a render loop:
 *
 * <ul>
 *   <li><strong>One fetch per path.</strong> A model's texture inventory walks
 *       every image reference and {@code ModelLoader} asks {@code present()} for
 *       each before the renderer asks for its bytes, so without a cache a
 *       corridor with four walls requests the same floor tile four times. The
 *       cache holds the bytes and the misses: a 404 is an answer, and re-asking
 *       a server for an asset it already said it does not have is how a render
 *       stalls.</li>
 *   <li><strong>No listing.</strong> {@link #listAssets} returns empty because
 *       the endpoint serves a named file and the REST surface publishes no
 *       directory. The one caller that needs enumeration ({@code WorldLoader})
 *       is not this desktop's path -- the desktop is handed its cells by
 *       {@code /api/world/cells}, which already named each one's model.</li>
 * </ul>
 *
 * The transport is {@link WorldCatalog.ByteFetcher}, the same seam the preview
 * image uses, so a scripted test drives this without a socket and a 404 stays a
 * 404 rather than an exception.
 */
package org.jdesktop.lg3d.apps.wonderland;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jdesktop.wonderland.modules.asset.WorldStore;

/** A {@link WorldStore} whose modules live on a wonderland-server. */
final class HttpWorldStore implements WorldStore {

    /** What the cache holds for "asked, and the server said no". */
    private static final byte[] ABSENT = new byte[0];

    private final String server;
    private final WorldCatalog.ByteFetcher fetcher;
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    HttpWorldStore(String server, WorldCatalog.ByteFetcher fetcher) {
        if (fetcher == null) {
            throw new IllegalArgumentException("fetcher must not be null");
        }
        this.server = server;
        this.fetcher = fetcher;
    }

    /**
     * Reads one asset. A transport failure is empty, not a throw: a world whose
     * server hiccups on one texture still has to show its geometry, and every
     * caller of this interface already treats "no bytes" as the missing case.
     */
    @Override
    public Optional<InputStream> open(String module, String path) {
        byte[] bytes = bytes(module, path);
        return (bytes == null || bytes.length == 0)
                ? Optional.empty()
                : Optional.of(new ByteArrayInputStream(bytes));
    }

    @Override
    public boolean present(String module, String path) {
        byte[] bytes = bytes(module, path);
        return bytes != null && bytes.length > 0;
    }

    /**
     * Not supported, honestly: see the header. The desktop's cells come from the
     * cells endpoint, never from a directory walk.
     */
    @Override
    public List<String> listAssets(String module, String suffix) {
        return List.of();
    }

    /**
     * The bytes for one asset, cached in both directions. Package-private so the
     * texture source below and any future caller share exactly one read per path.
     */
    byte[] bytes(String module, String path) {
        if (module == null || path == null || path.isEmpty()) {
            return null;
        }
        String key = module + "/" + path;
        byte[] cached = cache.get(key);
        if (cached == null) {
            try {
                cached = fetcher
                        .fetch(WorldCatalog.assetUrl(server, module, path))
                        .orElse(ABSENT);
            } catch (IOException | RuntimeException unavailable) {
                cached = ABSENT; // remembered as absent, so it is not asked twice
            }
            cache.put(key, cached);
        }
        return cached.length == 0 ? null : cached;
    }

    /**
     * This store as a texture source for one module: the renderer names a
     * texture by its module-relative path, and the module is already known here.
     */
    org.jdesktop.wonderland.renderer.mesh.TextureSource textures(String module) {
        return path -> bytes(module, path);
    }
}
