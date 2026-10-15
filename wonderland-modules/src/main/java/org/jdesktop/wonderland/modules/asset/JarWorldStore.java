/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules (world library): a {@link WorldStore} backed by a world
 * MODULE JAR, so the stock Open Wonderland worlds ship as the packaged artifacts
 * they always were and are served WITHOUT being unpacked into the repository.
 *
 * The 2006 installer carries each demo world as {@code modules/wonderland-*-world.jar}
 * -- module.xml, requires.xml, wfs/<world>-wfs/*-wlc.xml cells and the deployed
 * models under an {@code art/} directory. {@link DirectoryWorldStore} reads a
 * world unpacked to disk and {@link ClasspathWorldStore} reads one folded into
 * the running jar, but neither reads a standalone world jar, and neither can
 * enumerate: exactly what a world LIBRARY needs (discover the cells, hand them to
 * {@link WorldLoader}). A {@link JarFile} is enumerable -- {@code entries()} walks
 * every entry -- so this store answers {@link #listAssets} from the jar itself,
 * which is precisely the enumeration the classpath store refused (its jar
 * directory cannot be walked across exploded and packaged layouts).
 *
 * One deployment detail earns the "no re-encoding" claim. Each stock world spells
 * its art as {@code wla://<module>/<path>} at the module ROOT, but the jar stores
 * those bytes under {@code art/<path>}. That mismatch is not a bug to fix in the
 * committed assets (they are vendored binaries that must not be rewritten); it is
 * resolved HERE, on read: {@link #open} walks {@link AssetSpelling}'s candidates,
 * so a module-root reference finds its packaged bytes. That is the whole
 * translation a plain directory store would have had to bake into the layout.
 *
 * The second half of that translation is the one this port lost, and it is why the
 * original worlds came out as empty boxes: the COLLADA document every one of the
 * seven stock worlds deploys is gzipped in the jar while the {@code .dep} names it
 * plain. A reader that took the reference literally found nothing to load -- 23 of
 * 23 models, 0 rendered. See {@link AssetSpelling} for the measurement.
 *
 * The store is opened for one world jar and is {@link AutoCloseable}; the caller
 * (the server's seeder/catalog) owns its lifetime and closes it after a load.
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/** A {@link WorldStore} whose single module lives inside a world jar. */
public final class JarWorldStore implements WorldStore, AutoCloseable {

    private final JarFile jar;

    /** Open one world module jar for reading. */
    public JarWorldStore(Path jarPath) throws IOException {
        this.jar = new JarFile(jarPath.toFile());
    }

    @Override
    public Optional<InputStream> open(String module, String path) {
        if (path == null || path.isEmpty()) {
            return Optional.empty();
        }
        ZipEntry entry = findEntry(path);
        if (entry == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(jar.getInputStream(entry));
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read " + path + " from jar", ex);
        }
    }

    @Override
    public boolean present(String module, String path) {
        return path != null && !path.isEmpty() && findEntry(path) != null;
    }

    @Override
    public List<String> listAssets(String module, String suffix) {
        List<String> out = new ArrayList<>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            if (suffix == null || name.endsWith(suffix)) {
                out.add(name);
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    /**
     * Resolve one asset path to a jar entry, walking the spellings a stock world's
     * reference may arrive under -- as written, gzipped, under {@code art/}, or both
     * -- and returning the first the jar actually holds. Null when none does.
     */
    private ZipEntry findEntry(String path) {
        for (String candidate : AssetSpelling.candidates(path)) {
            ZipEntry entry = jar.getEntry(candidate);
            if (entry != null && !entry.isDirectory()) {
                return entry;
            }
        }
        return null;
    }

    @Override
    public void close() {
        try {
            jar.close();
        } catch (IOException ignored) {
            // closing a read-only jar cannot fail usefully; nothing to recover
        }
    }
}
