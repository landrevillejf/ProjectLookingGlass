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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** A {@link WorldStore} whose modules are directories under a base path. */
public final class DirectoryWorldStore implements WorldStore {

    /**
     * The two file-system calls whose {@link IOException} this store turns into an
     * {@link UncheckedIOException}. Factored behind an interface so a test can
     * drive the fault deterministically on any root, rather than coaxing a real
     * {@code Files.newInputStream} or {@code Files.walk} to fail on a live one.
     */
    interface Fs {
        InputStream newInputStream(Path path) throws IOException;

        Stream<Path> walk(Path root) throws IOException;
    }

    private static final Fs SYSTEM = new Fs() {
        @Override
        public InputStream newInputStream(Path path) throws IOException {
            return Files.newInputStream(path);
        }

        @Override
        public Stream<Path> walk(Path root) throws IOException {
            return Files.walk(root);
        }
    };

    private final Path base;
    private final Fs fs;

    /** @param base the directory that holds one sub-directory per module */
    public DirectoryWorldStore(Path base) {
        this(base, SYSTEM);
    }

    /**
     * Test seam: the same store with the file-system calls supplied, so an I/O
     * fault that {@code Files} only raises on a hostile machine is raised on
     * demand. Production callers use the single-argument constructor.
     */
    DirectoryWorldStore(Path base, Fs fs) {
        this.base = base.toAbsolutePath().normalize();
        this.fs = fs;
    }

    @Override
    public Optional<InputStream> open(String module, String path) {
        for (Path resolved : resolve(module, path)) {
            if (Files.isRegularFile(resolved)) {
                try {
                    return Optional.of(fs.newInputStream(resolved));
                } catch (IOException ex) {
                    throw new UncheckedIOException("cannot open " + resolved, ex);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean present(String module, String path) {
        return resolve(module, path).stream().anyMatch(Files::isRegularFile);
    }

    @Override
    public List<String> listAssets(String module, String suffix) {
        Path root = base.resolve(safeName(module)).normalize();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = fs.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace(java.io.File.separatorChar, '/'))
                    .filter(p -> p.endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot list " + root, ex);
        }
    }

    /**
     * The files one reference may name, in order: {@link AssetSpelling}'s
     * spellings, each confined to the module directory. A world unpacked to disk
     * keeps the packager's conventions -- an {@code art/} root and gzipped
     * COLLADA -- as faithfully as a jar does, so the same read rule applies here
     * and the two stores cannot disagree about whether an asset exists.
     */
    private List<Path> resolve(String module, String path) {
        List<Path> out = new ArrayList<>(4);
        for (String candidate : AssetSpelling.candidates(path)) {
            Path confined = confine(module, candidate);
            if (confined != null) {
                out.add(confined);
            }
        }
        return out;
    }

    /** Resolve a module+path and confirm it stays inside that module's directory. */
    private Path confine(String module, String path) {
        // Only resolve() calls this, and it passes an AssetSpelling candidate, which
        // is never null and never empty -- so the module is the only part to guard.
        if (module == null) {
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
