/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5c (assetmgr): the deployed-model resolver.
 * The asset pipeline's read side, driven against an in-memory store so every
 * degrades-to-ERROR branch is reachable headless: a non-wla reference, an absent
 * .dep, a malformed .dep, an unresolvable / absent Collada, a bad Collada, and
 * the three loader-data null paths -- none of which may throw. Also proves the
 * gzip sniffing and the present/missing texture split (resolved against the
 * .dae directory, not the .dep).
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ModelLoaderTest {

    private static final String MODULE = "M";
    private static final ModelLoader LOADER = new ModelLoader();

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzip(byte[] raw) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(raw);
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final String DEP = "<deployed-model>"
            + "<modelURL>wla://M/dir/models/untitled.dae</modelURL>"
            + "<loaderDataURL>wla://M/dir/m.ldr</loaderDataURL>"
            + "</deployed-model>";

    private static final String DAE = "<COLLADA xmlns=\"http://www.collada.org/2005/11/COLLADASchema\""
            + " version=\"1.4.1\"><library_effects><effect id=\"e\"><profile_COMMON>"
            + "<technique_common><lambert><diffuse><texture texture=\"img0\"/>"
            + "<texture texture=\"img1\"/></diffuse>"
            + "<transparent><texture texture=\"img0\"/></transparent></lambert>"
            + "</technique_common></profile_COMMON></effect></library_effects>"
            + "<library_images>"
            + "<image id=\"img0\"><init_from>untitled/texture0.png</init_from></image>"
            + "<image id=\"img1\"><init_from>./untitled/texture1.png</init_from></image>"
            + "</library_images></COLLADA>";

    private static final String LDR = "<loader-data><version>1</version>"
            + "<deployedTextures value=\"./untitled/texture2.png\" key=\"k\"/></loader-data>";

    /** A store whose modules are maps of path -> bytes. */
    private static final class MapStore implements WorldStore {
        private final Map<String, byte[]> files = new HashMap<>();

        MapStore put(String path, byte[] content) {
            files.put(MODULE + "/" + path, content);
            return this;
        }

        @Override
        public Optional<InputStream> open(String module, String path) {
            byte[] content = files.get(module + "/" + path);
            return content == null ? Optional.empty()
                    : Optional.of(new java.io.ByteArrayInputStream(content));
        }

        @Override
        public boolean present(String module, String path) {
            return files.containsKey(module + "/" + path);
        }

        @Override
        public List<String> listAssets(String module, String suffix) {
            return List.of();
        }
    }

    private static ModelLoadResult loadWellFormed() {
        MapStore store = new MapStore()
                .put("dir/m.dep", bytes(DEP))
                .put("dir/m.ldr", bytes(LDR))
                .put("dir/models/untitled.dae", bytes(DAE))
                .put("dir/models/untitled/texture0.png", new byte[]{1});
        return LOADER.load(store, "wla://M/dir/m.dep");
    }

    @Test
    void aWellFormedChainLoadsAndSplitsPresentFromMissingTextures() {
        ModelLoadResult result = loadWellFormed();

        assertThat(result.isLoaded()).isTrue();
        assertThat(result.model().modelURL()).isEqualTo("wla://M/dir/models/untitled.dae");
        assertThat(result.loaderData()).isNotNull();
        assertThat(result.document()).isNotNull();
        assertThat(result.presentTextures()).containsExactly("dir/models/untitled/texture0.png");
        assertThat(result.missingTextures()).containsExactlyInAnyOrder(
                "dir/models/untitled/texture1.png", "dir/models/untitled/texture2.png");
    }

    @Test
    void aGzippedColladaIsInflatedByItsMagicBytes() {
        MapStore store = new MapStore()
                .put("dir/m.dep", bytes(DEP))
                .put("dir/models/untitled.dae", gzip(bytes(DAE)));
        ModelLoadResult result = LOADER.load(store, "wla://M/dir/m.dep");

        assertThat(result.isLoaded()).isTrue();
        assertThat(result.document().imageCount()).isEqualTo(2);
    }

    @Test
    void notAWlaReferenceIsAnError() {
        assertThat(LOADER.load(new MapStore(), "http://nope").error())
                .contains("not a wla://");
    }

    @Test
    void anAbsentDeployedModelIsAnError() {
        assertThat(LOADER.load(new MapStore(), "wla://M/dir/m.dep").error())
                .contains("deployed model not found");
    }

    @Test
    void aMalformedDeployedModelIsAnError() {
        MapStore store = new MapStore().put("dir/m.dep", bytes("<deployed-model>"));
        assertThat(LOADER.load(store, "wla://M/dir/m.dep").error()).contains("bad .dep");
    }

    @Test
    void anUnresolvableColladaReferenceIsAnError() {
        String dep = "<deployed-model></deployed-model>"; // no modelURL
        MapStore store = new MapStore().put("dir/m.dep", bytes(dep));
        assertThat(LOADER.load(store, "wla://M/dir/m.dep").error())
                .contains("collada reference not resolvable");
    }

    @Test
    void anAbsentColladaIsAnError() {
        String dep = "<deployed-model><modelURL>wla://M/absent.dae</modelURL></deployed-model>";
        MapStore store = new MapStore().put("dir/m.dep", bytes(dep));
        assertThat(LOADER.load(store, "wla://M/dir/m.dep").error()).contains("model not found");
    }

    @Test
    void aMalformedColladaIsAnError() {
        String dep = "<deployed-model><modelURL>wla://M/dir/m.dae</modelURL></deployed-model>";
        MapStore store = new MapStore()
                .put("dir/m.dep", bytes(dep))
                .put("dir/m.dae", bytes("<COLLADA>")); // unclosed
        assertThat(LOADER.load(store, "wla://M/dir/m.dep").error()).contains("bad collada");
    }

    @Test
    void everyLoaderDataNullPathStillLoadsTheModel() {
        // (a) loaderDataURL is not a wla reference
        assertThat(LOADER.load(loaderDataStore("wla-bogus"), "wla://M/dir/m.dep").loaderData())
                .isNull();
        // (b) the loader-data asset is absent
        assertThat(LOADER.load(loaderDataStore("wla://M/dir/missing.ldr"), "wla://M/dir/m.dep")
                .loaderData()).isNull();
        // (c) the loader-data is present but malformed
        assertThat(LOADER.load(loaderDataStore("wla://M/dir/bad.ldr"), "wla://M/dir/m.dep")
                .loaderData()).isNull();
    }

    private static MapStore loaderDataStore(String loaderUrl) {
        String dep = "<deployed-model>"
                + "<modelURL>wla://M/dir/m.dae</modelURL>"
                + "<loaderDataURL>" + loaderUrl + "</loaderDataURL>"
                + "</deployed-model>";
        MapStore store = new MapStore()
                .put("dir/m.dep", bytes(dep))
                .put("dir/m.dae", bytes(DAE));
        if (loaderUrl.endsWith("bad.ldr")) {
            store.put("dir/bad.ldr", bytes("<loader-data>")); // unclosed -> parse throws
        }
        return store;
    }

    // ---- which artifacts this pipeline may hand to a COLLADA parser -------

    @Test
    void aColladaDocumentIsReadWhateverTheDescriptorCallsItself() {
        // The document is the evidence. Every shipped stock world names one of three
        // loaders for the same .dae, and refusing by class name is what threw away
        // ten real models: JmeColladaLoader carries "jme" and IS a COLLADA reader.
        assertThat(ModelLoader.readsCollada(
                "org.jdesktop.wonderland.modules.jmecolladaloader.client"
                + ".JmeColladaLoader", "Arch-rustic.kmz/models/untitled.dae.gz"))
                .as("the auditorium's and the gallery's own loader")
                .isTrue();
        assertThat(ModelLoader.readsCollada(
                "org.jdesktop.wonderland.modules.collada.loader.ColladaLoader",
                "dir/models/untitled.dae")).isTrue();
        assertThat(ModelLoader.readsCollada(null, "dir/models/untitled.dae"))
                .as("no loader named at all is still a COLLADA document")
                .isTrue();
        assertThat(ModelLoader.readsCollada(null, "dir/scene.xml")).isTrue();
        assertThat(ModelLoader.readsCollada(null, "dir/scene.xml.gz")).isTrue();
    }

    @Test
    void anArtifactThatIsNotADocumentIsRefusedUnlessTheDescriptorSaysCollada() {
        // The meeting complex's orientation artifact: a binary that a DOM parser can
        // only answer with "Content is not allowed in prolog" on stderr. Refusing it
        // here is what turns that stack trace into one line naming the cell and why.
        assertThat(ModelLoader.readsCollada(
                "org.jdesktop.wonderland.modules.mtgloader.client.MtgLoader",
                "OrientationWorld.mtg")).isFalse();
        assertThat(ModelLoader.readsCollada(null, "models/mesh.ogre")).isFalse();
        assertThat(ModelLoader.readsCollada(null, null)).isFalse();
        // ... and a descriptor that does name a Collada loader is believed, even
        // about an unusual suffix, because the author's word is the only evidence left.
        assertThat(ModelLoader.readsCollada(
                "org.jdesktop.wonderland.modules.collada.loader.ColladaLoader",
                "models/untitled.collada")).isTrue();
    }

    @Test
    void theCaseOfTheSuffixAndTheClassNameIsNeitherOfThemMeaningful() {
        assertThat(ModelLoader.readsCollada(null, "dir/UNTITLED.DAE.GZ")).isTrue();
        assertThat(ModelLoader.readsCollada(
                "org.example.COLLADALOADER", "models/untitled")).isTrue();
    }

    @Test
    void aGenuinelyUnsupportedFormatIsSkippedWithAReasonRatherThanAParseFailure() {
        // A format with no reader at all -- not MTGame, which is now supported --
        // must still cost itself and not the world, and the message must name both
        // the loader and the artifact, since that message is the only record a
        // maintainer gets of a format still missing. An .ogre mesh is the honest
        // stand-in: no loader in the shipped worlds produces it, and reading its
        // bytes as a document is what used to print "prolog" into the sweep log.
        String dep = "<deployed-model>"
                + "<modelURL>wla://M/models/mesh.ogre</modelURL>"
                + "<modelLoaderClassname>org.example.SomeOgreLoader</modelLoaderClassname>"
                + "</deployed-model>";
        MapStore store = new MapStore()
                .put("dir/m.dep", bytes(dep))
                .put("models/mesh.ogre", new byte[]{0x00, 0x01, 0x02, (byte) 0xff});
        ModelLoadResult result = LOADER.load(store, "wla://M/dir/m.dep");

        assertThat(result.isLoaded()).isFalse();
        assertThat(result.error())
                .contains("no reader for loader")
                .contains("SomeOgreLoader")
                .contains("mesh.ogre")
                .as("never the parser's own complaint about a binary")
                .doesNotContain("prolog");
    }

    @Test
    void anMtgSceneDispatchesToItsReaderAndFoldsItsColladaInstances() {
        // The meeting complex's whole ground lives in one OrientationWorld.mtg, so
        // this is the cell that used to leave the world empty. The .dep names the
        // MtgLoader and points at the .mtg; a reader is now expected to interpret
        // the scene, fold each ColladaInstance's own .dae into one compound document
        // and land it loaded rather than refused.
        String dep = "<deployed-model>"
                + "<modelURL>wla://M/OrientationWorld.mtg</modelURL>"
                + "<modelLoaderClassname>"
                + "org.jdesktop.wonderland.modules.mtgloader.client.MtgLoader"
                + "</modelLoaderClassname>"
                + "</deployed-model>";
        String scene = "DataDir\n"
                + "Instance Terrain org.jdesktop.mtgame.ColladaInstance "
                + "0.0 0.0 0.0 0.0 1.0 0.0 0.0 1.0 1.0 1.0 terrain.dae.gz\n"
                + "Instance Building org.jdesktop.mtgame.ColladaInstance "
                + "10.0 0.0 5.0 0.0 1.0 0.0 0.8 1.0 1.0 1.0 building.dae.gz\n";
        MapStore store = new MapStore()
                .put("OrientationWorld.dep", bytes(dep))
                .put("OrientationWorld.mtg", bytes(scene))
                .put("terrain.dae.gz", gzip(bytes(MINIMAL_DAE)))
                .put("building.dae.gz", gzip(bytes(MINIMAL_DAE)));
        ModelLoadResult result = LOADER.load(store, "wla://M/OrientationWorld.dep");

        assertThat(result.isLoaded()).isTrue();
        assertThat(result.document()).isNotNull();
        assertThat(result.document().geometryCount())
                .as("the two instance files folded under their own namespaces")
                .isEqualTo(2);
        assertThat(result.document().worldMatrices())
                .as("one placed node per instance, transform intact")
                .isNotEmpty();
    }

    @Test
    void anMtgWithNoReadableInstanceErrorsRatherThanLoadingAnEmptyWorld() {
        // The failure the meeting complex showed before the reader existed, now
        // re-worded honestly: the scene dispatched to the reader but named no object
        // it could fold (here, garbage bytes with no Instance records at all). The
        // message says the scene had no readable instance, not the parser's complaint
        // about a binary, and the cell costs itself rather than aborting the world.
        String dep = "<deployed-model>"
                + "<modelURL>wla://M/OrientationWorld.mtg</modelURL>"
                + "<modelLoaderClassname>"
                + "org.jdesktop.wonderland.modules.mtgloader.client.MtgLoader"
                + "</modelLoaderClassname>"
                + "</deployed-model>";
        MapStore store = new MapStore()
                .put("OrientationWorld.dep", bytes(dep))
                .put("OrientationWorld.mtg", new byte[]{0x00, 0x01, 0x02, (byte) 0xff});
        ModelLoadResult result = LOADER.load(store, "wla://M/OrientationWorld.dep");

        assertThat(result.isLoaded()).isFalse();
        assertThat(result.error())
                .contains("mtg scene named no readable instance")
                .doesNotContain("prolog");
    }

    private static final String MINIMAL_DAE =
            "<COLLADA xmlns=\"http://www.collada.org/2005/11/COLLADASchema\" version=\"1.4.1\">"
            + "<library_geometries><geometry id=\"g\"><mesh><source id=\"s\">"
            + "<float_array id=\"a\" count=\"3\">0 0 0</float_array>"
            + "<technique_common><accessor source=\"#a\" count=\"1\" stride=\"3\">"
            + "<param name=\"X\" type=\"float\"/><param name=\"Y\" type=\"float\"/>"
            + "<param name=\"Z\" type=\"float\"/></accessor></technique_common></source>"
            + "</mesh></geometry></library_geometries>"
            + "<library_visual_scenes><visual_scene id=\"vs\"><node id=\"n\">"
            + "<instance_geometry url=\"#g\"/></node></visual_scene></library_visual_scenes>"
            + "</COLLADA>";
}
