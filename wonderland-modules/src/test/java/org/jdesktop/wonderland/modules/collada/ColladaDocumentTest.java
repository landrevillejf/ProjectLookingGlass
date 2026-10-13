/*
 * wonderland-modules M5a (colladaloader): the document aggregate walks.
 * Pins the reference-chasing the asset pipeline relies on -- the root-scene
 * selection and its fallback, the world-matrix flatten (and its empty case),
 * and texturePaths, which skips a sampler whose image is absent or whose
 * init_from is null so a partial authoring export is never a load failure.
 */
package org.jdesktop.wonderland.modules.collada;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ColladaDocumentTest {

    private static ColladaDocument document(String rootSceneId,
                                            Map<String, ColladaEffect> effects,
                                            Map<String, ColladaImage> images,
                                            Map<String, ColladaVisualScene> scenes) {
        return new ColladaDocument(null, Map.of(), Map.of(), effects, images, scenes, rootSceneId);
    }

    private static ColladaEffect effectSampling(String... imageIds) {
        Map<String, String> samplers = new java.util.LinkedHashMap<>();
        for (int i = 0; i < imageIds.length; i++) {
            samplers.put("sampler" + i, imageIds[i]);
        }
        return new ColladaEffect("e", null, "lambert", null, 1.0, samplers);
    }

    @Test
    void texturePathsChaseSamplerToImageAndSkipUnresolvableOnes() {
        ColladaDocument doc = document("S",
                Map.of("e", effectSampling("present", "absent-id", "null-init")),
                Map.of(
                        "present", new ColladaImage("present", "models/tex.png"),
                        "null-init", new ColladaImage("null-init", null)),
                Map.of());

        assertThat(doc.texturePaths()).containsExactly("models/tex.png");
    }

    @Test
    void theRootSceneIsTheSelectedOneOrFallsBackToTheFirst() {
        ColladaVisualScene scene = new ColladaVisualScene("only", "n", List.of());
        Map<String, ColladaVisualScene> scenes = Map.of("only", scene);

        assertThat(document("only", Map.of(), Map.of(), scenes).rootVisualScene()).isSameAs(scene);
        // a selected id that does not exist falls back to the only scene
        assertThat(document("dangling", Map.of(), Map.of(), scenes).rootVisualScene()).isSameAs(scene);
        // no selection at all also falls back
        assertThat(document(null, Map.of(), Map.of(), scenes).rootVisualScene()).isSameAs(scene);
        // no scenes at all -> null, and the world walk is empty rather than throwing
        assertThat(document(null, Map.of(), Map.of(), Map.of()).rootVisualScene()).isNull();
        assertThat(document(null, Map.of(), Map.of(), Map.of()).worldMatrices()).isEmpty();
        // the by-id accessor returns the scene or null -- it is not the fallback walk
        assertThat(document("only", Map.of(), Map.of(), scenes).visualScene("only")).isSameAs(scene);
        assertThat(document("only", Map.of(), Map.of(), scenes).visualScene("absent")).isNull();
    }

    @Test
    void worldMatricesFlattenTheRootSceneSubtree() {
        ColladaNode leaf = new ColladaNode("leaf", "leaf", "NODE", List.of(), List.of(),
                List.of(), List.of());
        ColladaNode root = new ColladaNode("root", "root", "NODE", List.of(), List.of(leaf),
                List.of(), List.of());
        ColladaVisualScene scene = new ColladaVisualScene("S", "s", List.of(root));

        ColladaDocument doc = document("S", Map.of(), Map.of(), Map.of("S", scene));
        assertThat(doc.worldMatrices()).hasSize(2);
    }

    @Test
    void aNullAssetBecomesTheDefaultAsset() {
        ColladaDocument doc = new ColladaDocument(null, Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), null);
        assertThat(doc.asset().upAxis()).isEqualTo("Y_UP");
    }
}
