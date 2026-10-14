/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules (world seeding): a classpath {@link WorldStore} that CAN
 * enumerate a module's cells.
 *
 * ClasspathWorldStore deliberately returns an empty list from listAssets -- a
 * jar directory cannot be walked reliably across exploded and packaged layouts,
 * and its only prior consumer (the avatar pool) knows its contents through a
 * fixed catalog. A world is the same situation: WorldLoader.load discovers cells
 * through listAssets(module, "-wlc.xml"), so loading a world from the classpath
 * needs that enumeration backed by an explicit index rather than a directory scan.
 *
 * This store supplies exactly that: it reads {@code <module>/world-index.txt}
 * (one module-root-relative asset path per line; blank lines and '#' comments
 * ignored) and answers listAssets from it, filtering by suffix and sorting. Every
 * other operation delegates to a plain ClasspathWorldStore, so path confinement
 * (no '../', no leading '/') and the defensive "absent is empty" degrade are the
 * same rule the file and avatar stores already enforce.
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A classpath {@link WorldStore} whose cell discovery is driven by a per-module index file. */
public final class ManifestClasspathWorldStore implements WorldStore {

    /** The module-root-relative catalog every manifest store reads. */
    public static final String INDEX_FILE = "world-index.txt";

    private final ClasspathWorldStore delegate;

    public ManifestClasspathWorldStore() {
        this.delegate = new ClasspathWorldStore();
    }

    /** Resolve against a caller-supplied loader (tests pin it; production uses the no-arg form). */
    public ManifestClasspathWorldStore(ClassLoader loader) {
        this.delegate = new ClasspathWorldStore(loader);
    }

    @Override
    public Optional<InputStream> open(String module, String path) {
        return delegate.open(module, path);
    }

    @Override
    public boolean present(String module, String path) {
        return delegate.present(module, path);
    }

    @Override
    public List<String> listAssets(String module, String suffix) {
        List<String> out = new ArrayList<>();
        Optional<InputStream> index = delegate.open(module, INDEX_FILE);
        if (index.isEmpty()) {
            return out;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(index.get(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String asset = line.trim();
                if (asset.isEmpty() || asset.startsWith("#")) {
                    continue;
                }
                if (suffix == null || asset.endsWith(suffix)) {
                    out.add(asset);
                }
            }
        } catch (Exception ex) {
            return List.of(); // a broken index is "no cells", never a thrown load
        }
        out.sort(String::compareTo);
        return out;
    }
}
