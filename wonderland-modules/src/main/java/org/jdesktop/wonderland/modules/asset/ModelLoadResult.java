/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5c (assetmgr): the outcome of resolving one model asset.
 *
 * What the pipeline produces for a single {@code wla://} deployed-model
 * reference: the parsed {@code .dep}, {@code .ldr} and Collada document, the
 * texture dependencies split into those the store actually holds and those it
 * does not (the 2006 loader treated a missing texture as a warning, not a
 * failure), and the {@link AssetLoadStatus}. Immutable; built by
 * {@link ModelLoader}.
 */
package org.jdesktop.wonderland.modules.asset;

import java.util.List;
import org.jdesktop.wonderland.modules.artimport.DeployedModel;
import org.jdesktop.wonderland.modules.artimport.LoaderData;
import org.jdesktop.wonderland.modules.collada.ColladaDocument;

/** A resolved (or failed) model-load result. */
public final class ModelLoadResult {

    private final AssetLoadStatus status;
    private final String error;
    private final DeployedModel model;
    private final LoaderData loaderData;
    private final ColladaDocument document;
    private final List<String> presentTextures;
    private final List<String> missingTextures;

    private ModelLoadResult(AssetLoadStatus status, String error, DeployedModel model,
                            LoaderData loaderData, ColladaDocument document,
                            List<String> presentTextures, List<String> missingTextures) {
        this.status = status;
        this.error = error;
        this.model = model;
        this.loaderData = loaderData;
        this.document = document;
        this.presentTextures = List.copyOf(presentTextures);
        this.missingTextures = List.copyOf(missingTextures);
    }

    /** A successful load result. */
    static ModelLoadResult loaded(DeployedModel model, LoaderData loaderData,
                                  ColladaDocument document,
                                  List<String> presentTextures, List<String> missingTextures) {
        return new ModelLoadResult(AssetLoadStatus.LOADED, null, model, loaderData,
                document, presentTextures, missingTextures);
    }

    /**
     * A loaded model that arrived as a bare COLLADA document, with no
     * {@code .dep} and no {@code .ldr} behind it.
     *
     * <p>The avatar pool is the reason this exists: the bundled bodies are
     * {@code .dae} files on the classpath (see
     * {@code org.jdesktop.wonderland.avatars.AvatarPool}), authored at human
     * scale and normalised by nobody, so there is no deployment descriptor to
     * resolve and nothing to normalise against. A renderer that could only draw
     * what a {@code .dep} had pointed at could not draw a person.</p>
     */
    public static ModelLoadResult bare(ColladaDocument document) {
        return new ModelLoadResult(AssetLoadStatus.LOADED, null, null, null,
                document, List.of(), List.of());
    }

    /** A failed load with the reason the pipeline stopped. */
    static ModelLoadResult error(String message) {
        return new ModelLoadResult(AssetLoadStatus.ERROR, message, null, null, null,
                List.of(), List.of());
    }

    public AssetLoadStatus status() {
        return status;
    }

    public boolean isLoaded() {
        return status == AssetLoadStatus.LOADED;
    }

    /** The failure reason, or null on success. */
    public String error() {
        return error;
    }

    public DeployedModel model() {
        return model;
    }

    public LoaderData loaderData() {
        return loaderData;
    }

    public ColladaDocument document() {
        return document;
    }

    public List<String> presentTextures() {
        return presentTextures;
    }

    public List<String> missingTextures() {
        return missingTextures;
    }
}
