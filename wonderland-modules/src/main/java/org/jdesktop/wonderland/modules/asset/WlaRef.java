/*
 * wonderland-modules M5c (assetmgr): a parsed wla:// asset reference.
 *
 * The worlds spell their art as {@code wla://<module>/<path>}. The wire-side
 * ArtURI in wonderland-common models the general form; this is the narrow parse
 * the loader needs -- split the reference into the module host and the in-module
 * path -- kept independent so the asset pipeline stays self-contained and cheap
 * to test.
 */
package org.jdesktop.wonderland.modules.asset;

/** A {@code wla://<module>/<path>} reference split into its two parts. */
public final class WlaRef {

    /** The scheme the worlds use for module-local art. */
    public static final String SCHEME = "wla://";

    private final String module;
    private final String path;

    private WlaRef(String module, String path) {
        this.module = module;
        this.path = path;
    }

    public String module() {
        return module;
    }

    public String path() {
        return path;
    }

    /**
     * Parse a {@code wla://} reference.
     *
     * @param uri the reference to parse
     * @return the split reference, or null when {@code uri} is null, is not a
     *         {@code wla://} URL, or names no path after the module
     */
    public static WlaRef parse(String uri) {
        if (uri == null || !uri.startsWith(SCHEME)) {
            return null;
        }
        String rest = uri.substring(SCHEME.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            return null; // no module, or no path after the module
        }
        return new WlaRef(rest.substring(0, slash), rest.substring(slash + 1));
    }

    /**
     * Join a base directory with a texture path that may be {@code ./}-prefixed,
     * producing the store-relative path a dependency resolves to. A null base
     * returns the (normalised) child alone.
     */
    public static String resolve(String baseDir, String relative) {
        String child = relative != null && relative.startsWith("./")
                ? relative.substring(2) : relative;
        if (baseDir == null || baseDir.isEmpty()) {
            return child;
        }
        if (child == null || child.isEmpty()) {
            return baseDir;
        }
        return baseDir.endsWith("/") ? baseDir + child : baseDir + "/" + child;
    }

    /** The directory portion of a path (everything before the last slash), or "" at root. */
    public static String parentDir(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}
