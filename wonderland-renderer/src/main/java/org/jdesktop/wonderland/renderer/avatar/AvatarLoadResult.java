/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6b: the outcome of resolving one avatar body URL.
 *
 * The M5 asset pipeline (ModelLoader) already produces a rich
 * {@code ModelLoadResult} for a world's deployed model -- but that type is
 * immutable and its factories are package-private inside wonderland-modules,
 * so a renderer-side leaf cannot construct one. M6b does not need any of the
 * pipeline's texture-dependency bookkeeping either: the bundled pool ships
 * raw .dae bytes with no textures, no .dep indirection and no .ldr loader
 * data. This is the small dedicated result record the {@link
 * AvatarLoadResolver} returns so the renderer's dispatch decision (loaded ->
 * build the scene; error -> null renderer) stays headless-testable.
 */
package org.jdesktop.wonderland.renderer.avatar;

import org.jdesktop.wonderland.modules.collada.ColladaDocument;

/** The result of resolving an {@code wla://} avatar body through the pool. */
public final class AvatarLoadResult {

    private final ColladaDocument document;
    private final String resolvedUrl;
    private final boolean loaded;
    private final String errorMessage;

    private AvatarLoadResult(ColladaDocument document, String resolvedUrl,
                             boolean loaded, String errorMessage) {
        this.document = document;
        this.resolvedUrl = resolvedUrl;
        this.loaded = loaded;
        this.errorMessage = errorMessage;
    }

    /** A successful resolve: the parsed Collada document plus the URL used. */
    public static AvatarLoadResult loaded(ColladaDocument document, String resolvedUrl) {
        return new AvatarLoadResult(document, resolvedUrl, true, null);
    }

    /** A failed resolve: null document + the reason and the URL attempted. */
    public static AvatarLoadResult error(String message, String resolvedUrl) {
        return new AvatarLoadResult(null, resolvedUrl, false, message);
    }

    public ColladaDocument document() {
        return document;
    }

    public String resolvedUrl() {
        return resolvedUrl;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public String errorMessage() {
        return errorMessage;
    }
}
