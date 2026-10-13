/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5a (colladaloader): a COLLADA <image>.
 *
 * The <library_images>/<image>/<init_from> entry names the texture file the
 * effect samples. The loader keeps the reference (relative to the .dae) and, for
 * a {@code file://} init_from, strips it back to a plain path -- the 2006 loader
 * resolved exactly this against the deployed-texture map. The bytes themselves
 * are decoded by the deferred M6 renderer, not here.
 */
package org.jdesktop.wonderland.modules.collada;

/** A {@code <library_images>/<image>} element. */
public final class ColladaImage {

    private final String id;
    private final String initFrom;

    public ColladaImage(String id, String initFrom) {
        this.id = id;
        this.initFrom = initFrom;
    }

    public String id() {
        return id;
    }

    /** The raw {@code <init_from>} text, or null when the image carried none. */
    public String initFrom() {
        return initFrom;
    }

    /**
     * The texture path with any {@code file://} scheme and leading slash removed,
     * so it matches the {@code ./untitled/texture0.png}-style keys the deployed
     * model's loader-data records. A null init_from yields null.
     */
    public String normalizedPath() {
        if (initFrom == null) {
            return null;
        }
        String path = initFrom;
        if (path.startsWith("file://")) {
            path = path.substring("file://".length());
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
        }
        return path;
    }
}
