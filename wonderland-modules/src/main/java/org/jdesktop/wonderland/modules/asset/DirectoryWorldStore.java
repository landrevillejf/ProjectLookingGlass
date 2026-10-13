/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5c (assetmgr): a directory-backed {@link WorldStore}.
 *
 * Resolves a module's assets under {@code <base>/<module>/<path>} on the local
 * file system -- the shape a world jar unwraps to, and the backing a headless
 * test (and the eventual module-jar / HTTP stores) share. Paths are normalised
 * and confined to the module directory so a {@code ../} in a descriptor cannot
 * escape the store.
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** A {@link WorldStore} whose modules are directories under a base path. */
public final class DirectoryWorldStore implements WorldStore {

    private final Path base;

    /** @param base the directory that holds one sub-directory per module */
    public DirectoryWorldStore(Path base) {
        this.base = base.toAbsolutePath().normalize();
    }

    @Override
    public Optional<InputStream> open(String module, String path) {
        Path resolved = confine(module, path);
        if (resolved == null || !Files.isRegularFile(resolved)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.newInputStream(resolved));
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot open " + resolved, ex);
        }
    }

    @Override
    public boolean present(String module, String path) {
        Path resolved = confine(module, path);
        return resolved != null && Files.isRegularFile(resolved);
    }

    @Override
    public List<String> listAssets(String module, String suffix) {
        Path root = base.resolve(safeName(module)).normalize();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace(java.io.File.separatorChar, '/'))
                    .filter(p -> p.endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot list " + root, ex);
        }
    }

    /** Resolve a module+path and confirm it stays inside that module's directory. */
    private Path confine(String module, String path) {
        if (module == null || path == null) {
            return null;
        }
        Path root = base.resolve(safeName(module)).normalize();
        Path resolved = root.resolve(path).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    private static String safeName(String module) {
        return module == null ? "" : module.replace("..", "_");
    }
}
