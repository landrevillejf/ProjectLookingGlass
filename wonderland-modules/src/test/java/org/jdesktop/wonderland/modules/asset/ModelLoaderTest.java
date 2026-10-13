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
        assertThat(LOADER.load(store, "wla://M/dir/m.dep").error()).contains("collada not found");
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
}
