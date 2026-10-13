/*
 * wonderland-modules M5c (assetmgr): the content store a world's assets resolve against.
 *
 * A world bundles its art as {@code wla://<module>/<path>} references; the 2006
 * asset manager resolved those against the installed module (a jar on the
 * classpath). This is that resolution contract as a tiny interface so the loader
 * is testable against any backing store: a directory in CI today, a module jar
 * (or the live HTTP/WFS repository) when the desktop integration lands at M6.
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/** Resolves {@code wla://} module assets to byte streams. */
public interface WorldStore {

    /**
     * Open one asset.
     *
     * @param module the module name (the {@code wla://<module>} host)
     * @param path   the asset path inside the module
     * @return a stream over the bytes, or empty when the asset is not present
     */
    Optional<InputStream> open(String module, String path);

    /** Whether the named asset exists (a cheap existence check for dependency walking). */
    boolean present(String module, String path);

    /**
     * List the module's asset paths that end with {@code suffix}, sorted, relative
     * to the module root. Used to discover a world's cell files.
     */
    List<String> listAssets(String module, String suffix);
}
