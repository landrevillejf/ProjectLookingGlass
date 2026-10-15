/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M8: where a model's texture bytes actually come from.
 *
 * The M6a factory resolved a texture path against the classpath or the file
 * system, which is enough for a probe pointed at a bundled fixture -- and
 * nothing else. A world that lives in a module jar on a server has its textures
 * behind that server's asset endpoint, so the bytes have to arrive through the
 * same store the Collada did. This is that seam: one method, no Java 3D type in
 * its signature, so the decision "textured or colour-fallback" stays testable
 * and the store wiring stays where it belongs.
 *
 * A null return is not a failure: it is the same "warning, not failure" the
 * 2006 loader treated a missing texture as, and it is what lets a world with
 * half its art still show its geometry.
 */
package org.jdesktop.wonderland.renderer.mesh;

/**
 * Serves a model's texture bytes by the path the Collada document names.
 * Implementations are free to be slow (a network read); the caller decodes and
 * caches the image, so one path is asked for at most once per scene.
 */
@FunctionalInterface
public interface TextureSource {

    /**
     * @param path the texture path as the document resolved it (module-relative)
     * @return the image bytes, or {@code null} when this source cannot serve it
     */
    byte[] read(String path);

    /** A source that serves nothing -- the colour-fallback path. */
    TextureSource NONE = path -> null;
}
