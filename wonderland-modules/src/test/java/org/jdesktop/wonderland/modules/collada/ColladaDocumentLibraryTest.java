/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5a (colladaloader): the library-id inventory.
 *
 * The read-only id accessors a compound reader walks to fold several documents
 * into one (the MTGame reader re-keys each library by these ids), and the counts
 * that summarise them. Pinned directly here because they are the document's own
 * contract -- each returns the keyed view's id set in parse order, and a missing
 * id resolves to null rather than throwing -- not merely a by-product of the fold.
 */
package org.jdesktop.wonderland.modules.collada;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ColladaDocumentLibraryTest {

    private static ColladaDocument document() {
        Map<String, ColladaGeometry> geometries = new java.util.LinkedHashMap<>();
        geometries.put("geomA", null);
        geometries.put("geomB", null);
        Map<String, ColladaMaterial> materials = new java.util.LinkedHashMap<>();
        materials.put("matA", new ColladaMaterial("matA", "Mat A", "#effA"));
        Map<String, ColladaEffect> effects = new java.util.LinkedHashMap<>();
        effects.put("effA", new ColladaEffect("effA", "Eff A", "lambert",
                new double[]{1, 1, 1, 1}, 1.0, Map.of("s0", "imgA")));
        Map<String, ColladaImage> images = new java.util.LinkedHashMap<>();
        images.put("imgA", new ColladaImage("imgA", "file://tex/a.png"));
        Map<String, ColladaVisualScene> scenes = new java.util.LinkedHashMap<>();
        scenes.put("sceneA", new ColladaVisualScene("sceneA", "Scene A", List.of()));
        return new ColladaDocument(new ColladaAsset(null, 1.0, null, null, null),
                geometries, materials, effects, images, scenes, "sceneA");
    }

    @Test
    void eachLibraryExposesItsIdsInParseOrder() {
        ColladaDocument doc = document();
        assertThat(doc.geometryIds()).containsExactly("geomA", "geomB");
        assertThat(doc.materialIds()).containsExactly("matA");
        assertThat(doc.effectIds()).containsExactly("effA");
        assertThat(doc.imageIds()).containsExactly("imgA");
        assertThat(doc.visualSceneIds()).containsExactly("sceneA");
    }

    @Test
    void aKnownIdResolvesAndAnUnknownOneIsNull() {
        ColladaDocument doc = document();
        assertThat(doc.material("matA")).isNotNull();
        assertThat(doc.effect("effA")).isNotNull();
        assertThat(doc.image("imgA")).isNotNull();
        assertThat(doc.visualScene("sceneA")).isNotNull();
        assertThat(doc.material("nope")).isNull();
        assertThat(doc.effect("nope")).isNull();
        assertThat(doc.image("nope")).isNull();
        assertThat(doc.visualScene("nope")).isNull();
        assertThat(doc.geometry("nope")).isNull();
    }

    @Test
    void theCountsSummariseEachLibrary() {
        ColladaDocument doc = document();
        assertThat(doc.geometryCount()).isEqualTo(2);
        assertThat(doc.materialCount()).isEqualTo(1);
        assertThat(doc.imageCount()).isEqualTo(1);
        assertThat(doc.animationCount()).isZero();
    }

    @Test
    void theRootSceneFallsBackToTheOnlySceneWhenNoneIsNamed() {
        Map<String, ColladaVisualScene> scenes = new java.util.LinkedHashMap<>();
        scenes.put("only", new ColladaVisualScene("only", "Only", List.of()));
        ColladaDocument doc = new ColladaDocument(new ColladaAsset(null, 1.0, null, null, null),
                Map.of(), Map.of(), Map.of(), Map.of(), scenes, "a-scene-that-is-not-there");
        assertThat(doc.rootVisualScene()).isNotNull();
        assertThat(doc.rootVisualScene().id()).isEqualTo("only");
    }
}
