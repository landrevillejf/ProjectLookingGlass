/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M6b (assetmgr): a classpath-backed {@link WorldStore}.
 *
 * The second WorldStore implementation beside DirectoryWorldStore: where
 * DirectoryWorldStore reads modules unwrapped to disk under a base path, this
 * one reads them from the JVM's own classpath. It exists so a pure resource
 * jar -- the M6b wonderland-avatars module -- can ship its .dae bodies and
 * have the client's AvatarLoadResolver reach them through the exact same
 * wla://<module>/<path> contract the M5 file-store uses.
 *
 * Path confinement mirrors DirectoryWorldStore's rule: a resource name is
 * built by joining the module directory and the path with a '/'; a
 * {@code ..} segment anywhere in the path is refused so a descriptor cannot
 * read outside its module's resource folder. A null module or path is not
 * an error, it is simply "not present" -- matching the file store's
 * defensive degrade.
 *
 * listAssets returns an empty list by design: the pool is a fixed catalog
 * (see wonderland-avatars' AvatarPool.names()), not a directory the caller
 * enumerates. Enumerating classpath resources reliably across exploded and
 * jar-packaged layouts is fiddly and M6b has no consumer that wants it, so
 * the honest answer is "the abstraction does not support listing here".
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A {@link WorldStore} whose modules are directories on the classpath. */
public final class ClasspathWorldStore implements WorldStore {

    private final ClassLoader loader;

    /** Resolve against the current thread's context class loader. */
    public ClasspathWorldStore() {
        this(Thread.currentThread().getContextClassLoader());
    }

    /**
     * Resolve against a caller-supplied class loader. Package-private tests
     * pass a specific loader to prove the store does not implicitly bind to
     * a thread-local one; production callers use the no-arg form.
     */
    public ClasspathWorldStore(ClassLoader loader) {
        this.loader = loader == null ? ClasspathWorldStore.class.getClassLoader() : loader;
    }

    @Override
    public Optional<InputStream> open(String module, String path) {
        for (String resource : confineAll(module, path)) {
            InputStream found = loader.getResourceAsStream(resource);
            if (found != null) {
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean present(String module, String path) {
        for (String resource : confineAll(module, path)) {
            if (loader.getResource(resource) != null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> listAssets(String module, String suffix) {
        // See the header: the classpath store is intentionally non-listing.
        // Every caller that needs enumeration knows it through a fixed
        // catalog (wonderland-avatars' AvatarPool, or an application
        // providing its own index), not through this abstraction.
        return List.of();
    }

    /**
     * The resource names one reference may be bundled under, in order, each already
     * confined. A stock world folded into a jar keeps the packager's spelling --
     * gzipped COLLADA beside an {@code art/} root -- exactly as it does in a
     * standalone world jar, so the same rule has to hold here or a world would load
     * its models one way when installed and another when bundled.
     */
    private static List<String> confineAll(String module, String path) {
        List<String> out = new ArrayList<>(4);
        for (String candidate : AssetSpelling.candidates(path)) {
            String confined = confine(module, candidate);
            if (confined != null) {
                out.add(confined);
            }
        }
        return out;
    }

    /**
     * Build the resource name {@code <module>/<path>} and refuse it if the module
     * is null or empty, or the path tries to walk above its module directory.
     * Returns null for the "no such resource" case rather than throwing. The only
     * caller passes a path drawn from {@link AssetSpelling#candidates}, which is
     * never null and never empty, so this guards the module alone.
     */
    private static String confine(String module, String path) {
        if (module == null || module.isEmpty()) {
            return null;
        }
        // Classpath resources always use '/' regardless of host OS.
        String normalised = path.replace('\\', '/');
        if (normalised.contains("../") || normalised.endsWith("/..")
                || normalised.equals("..") || normalised.startsWith("/")) {
            return null;
        }
        return module + "/" + normalised;
    }
}
