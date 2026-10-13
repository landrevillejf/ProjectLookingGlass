/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5c (assetmgr): resolves and parses one deployed model.
 *
 * The read side of the asset pipeline: given a {@code wla://} reference to a
 * {@code .dep}, it fetches and parses the deployed model, its loader-data
 * ({@code .ldr}) and the (possibly gzip-inflated) Collada document, then splits
 * the model's texture dependencies into what the store has and what it lacks.
 * Every failure degrades to an {@link AssetLoadStatus#ERROR} result rather than
 * throwing -- the 2006 loader never let one bad asset abort a world. Building
 * the Java 3D shapes from the parsed Collada is the deferred M6 renderer.
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.jdesktop.wonderland.modules.artimport.DeployedModel;
import org.jdesktop.wonderland.modules.artimport.DeployedModelIO;
import org.jdesktop.wonderland.modules.artimport.LoaderData;
import org.jdesktop.wonderland.modules.collada.ColladaDocument;
import org.jdesktop.wonderland.modules.collada.ColladaParser;

/** Loads a deployed model and its Collada document from a {@link WorldStore}. */
public final class ModelLoader {

    private final DeployedModelIO deployedIo = new DeployedModelIO();
    private final ColladaParser colladaParser = new ColladaParser();

    /**
     * Resolve the {@code wla://} deployed-model reference against {@code store}.
     *
     * @return a {@link ModelLoadResult}; never throws for a missing / malformed asset
     */
    public ModelLoadResult load(WorldStore store, String depUrl) {
        WlaRef ref = WlaRef.parse(depUrl);
        if (ref == null) {
            return ModelLoadResult.error("not a wla:// reference: " + depUrl);
        }
        byte[] depBytes = read(store, ref.module(), ref.path());
        if (depBytes == null) {
            return ModelLoadResult.error("deployed model not found: " + depUrl);
        }
        DeployedModel model;
        try {
            model = deployedIo.parseDeployedModel(new ByteArrayInputStream(depBytes));
        } catch (RuntimeException ex) {
            return ModelLoadResult.error("bad .dep: " + ex.getMessage());
        }

        LoaderData loaderData = loadLoaderData(store, model.loaderDataURL());

        WlaRef modelRef = model.modelURL() == null ? null : WlaRef.parse(model.modelURL());
        if (modelRef == null) {
            return ModelLoadResult.error("collada reference not resolvable: " + model.modelURL());
        }
        byte[] modelBytes = read(store, modelRef.module(), modelRef.path());
        if (modelBytes == null) {
            return ModelLoadResult.error("collada not found: " + model.modelURL());
        }
        ColladaDocument document;
        try {
            document = colladaParser.parse(inflate(modelBytes));
        } catch (IOException | RuntimeException ex) {
            return ModelLoadResult.error("bad collada: " + ex.getMessage());
        }

        Set<String> present = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        // COLLADA <image>/<init_from> paths are relative to the .dae, so both the
        // Collada and the loader-data texture references resolve against the model
        // document's own directory -- the same base the 2006 KmzLoader used.
        String modelDir = WlaRef.parentDir(modelRef.path());
        for (String texture : textureCandidates(document, loaderData)) {
            String resolved = WlaRef.resolve(modelDir, texture);
            (store.present(ref.module(), resolved) ? present : missing).add(resolved);
        }
        return ModelLoadResult.loaded(model, loaderData, document, List.copyOf(present),
                List.copyOf(missing));
    }

    private LoaderData loadLoaderData(WorldStore store, String loaderUrl) {
        WlaRef ref = WlaRef.parse(loaderUrl);
        if (ref == null) {
            return null;
        }
        byte[] bytes = read(store, ref.module(), ref.path());
        if (bytes == null) {
            return null;
        }
        try {
            return deployedIo.parseLoaderData(new ByteArrayInputStream(bytes));
        } catch (RuntimeException ex) {
            return null; // a broken loader-data does not abort the model, matching 2006
        }
    }

    /** Union of the texture paths the Collada and the loader-data reference. */
    private static Set<String> textureCandidates(ColladaDocument document, LoaderData loaderData) {
        Set<String> out = new LinkedHashSet<>(document.texturePaths());
        if (loaderData != null) {
            out.addAll(loaderData.texturePaths());
        }
        return out;
    }

    /** Read a store asset fully; null when it is absent. */
    private static byte[] read(WorldStore store, String module, String path) {
        if (path == null) {
            return null;
        }
        return store.open(module, path).map(ModelLoader::readAll).orElse(null);
    }

    private static byte[] readAll(InputStream in) {
        try (InputStream stream = in) {
            return stream.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("failed reading asset", ex);
        }
    }

    /**
     * Inflate a gzip stream by its magic bytes; pass plain bytes through. The
     * worlds ship {@code .dae.gz}, but an author may drop an uncompressed .dae,
     * so the loader sniffs rather than assuming.
     */
    private static InputStream inflate(byte[] bytes) throws IOException {
        ByteArrayInputStream raw = new ByteArrayInputStream(bytes);
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
            return new GZIPInputStream(raw);
        }
        return raw;
    }
}
