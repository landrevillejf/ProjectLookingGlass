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
        // The {@code .dep} names the loader the 2006 client would have run, and not
        // every one of them produced COLLADA. The meeting complex's orientation
        // artifact is an {@code .mtg} handed to {@code MtgLoader}, and reading its
        // bytes as a document is what printed
        // "Content is not allowed in prolog" into the sweep log: the answer was
        // never a broken model, it is an art format this port has no reader for.
        // Refuse it on the document's own evidence -- see {@link #readsCollada},
        // which records the time refusing on the loader's <em>name</em> scrapped
        // ten real models -- so the cell is skipped with a reason rather than a
        // stack trace, exactly as the 2006 client skipped a module it lacked.
        if (!readsCollada(model.modelLoaderClassname(), modelRef.path())) {
            return ModelLoadResult.error("no reader for loader "
                    + model.modelLoaderClassname() + " ("
                    + WlaRef.baseName(modelRef.path()) + ")");
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

    /**
     * Whether this pipeline can read the artifact a {@code .dep} points at.
     *
     * <p>The document decides, not the loader's class name. The first cut here
     * refused by class name and threw away ten models of the shipped worlds,
     * because {@code org.jdesktop.wonderland.modules.jmecolladaloader.client.
     * JmeColladaLoader} carries {@code jme} in its name and is nonetheless a
     * COLLADA reader -- so it silently scrapped the auditorium, the gallery's main
     * building and five cells of the combined world. That is the exact failure this
     * port is being judged for, and a name is not evidence.
     *
     * <p>A {@code .dae} (optionally gzipped) or a {@code .xml} is a COLLADA
     * document by construction and is read. Anything else -- the meeting complex's
     * {@code OrientationWorld.mtg} -- is only read if the descriptor names a
     * Collada loader, which {@code MtgLoader} does not.
     *
     * @param loaderClassname the {@code modelLoaderClassname} the {@code .dep}
     *                        recorded, or null when it recorded none
     * @param modelPath       the artifact path the {@code modelURL} resolved to
     */
    static boolean readsCollada(String loaderClassname, String modelPath) {
        if (modelPath != null) {
            String lower = modelPath.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".dae") || lower.endsWith(".dae.gz")
                    || lower.endsWith(".xml") || lower.endsWith(".xml.gz")) {
                return true;
            }
        }
        return loaderClassname != null
                && loaderClassname.toLowerCase(java.util.Locale.ROOT).contains("collada");
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
