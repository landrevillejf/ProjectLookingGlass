/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5a (colladaloader): the appearance / asset value types.
 * Pins the small normalisations each carries: the image init_from path strip
 * (file:// and the leading slash), the asset up-axis and unit-scale defaults,
 * the effect's defensive colour/sampler copies, and the material/scene accessors.
 */
package org.jdesktop.wonderland.modules.collada;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ColladaValueTypesTest {

    @Test
    void imageStripsFileUrlAndLeadingSlash() {
        assertThat(new ColladaImage("i", "untitled/t.png").normalizedPath())
                .isEqualTo("untitled/t.png");
        assertThat(new ColladaImage("i", "file://tmp/t.png").normalizedPath())
                .isEqualTo("tmp/t.png");
        assertThat(new ColladaImage("i", "file:///tmp/t.png").normalizedPath())
                .isEqualTo("tmp/t.png");
        assertThat(new ColladaImage("i", null).normalizedPath()).isNull();
        assertThat(new ColladaImage("i", "file:///t.png").initFrom()).isEqualTo("file:///t.png");
        assertThat(new ColladaImage("i", "x").id()).isEqualTo("i");
    }

    @Test
    void assetDefaultsTheUpAxisAndUnitScale() {
        assertThat(new ColladaAsset(null, 1.0, null, null, null).upAxis()).isEqualTo("Y_UP");
        assertThat(new ColladaAsset("Z_UP", 1.0, "tool", "c", "m").upAxis()).isEqualTo("Z_UP");
        assertThat(new ColladaAsset("X_UP", 0.0, null, null, null).unitMeter()).isEqualTo(1.0);
        assertThat(new ColladaAsset("X_UP", -2.0, null, null, null).unitMeter()).isEqualTo(1.0);
        assertThat(new ColladaAsset("X_UP", 0.0254, "SketchUp", "c", "m").unitMeter())
                .isEqualTo(0.0254);
        assertThat(new ColladaAsset("X_UP", 1, "t", "c", "m").authoringTool()).isEqualTo("t");
        assertThat(new ColladaAsset("X_UP", 1, "t", "c", "m").created()).isEqualTo("c");
        assertThat(new ColladaAsset("X_UP", 1, "t", "c", "m").modified()).isEqualTo("m");
    }

    @Test
    void effectCopiesItsColourAndSamplersDefensively() {
        double[] color = {1, 2, 3, 4};
        Map<String, String> samplers = new LinkedHashMap<>();
        samplers.put("sampler0", "img0");
        ColladaEffect effect = new ColladaEffect("e", "n", "lambert", color, 0.5, samplers);

        assertThat(effect.id()).isEqualTo("e");
        assertThat(effect.name()).isEqualTo("n");
        assertThat(effect.technique()).isEqualTo("lambert");
        assertThat(effect.transparency()).isEqualTo(0.5);
        assertThat(effect.samplers()).containsEntry("sampler0", "img0");

        effect.diffuseColor()[0] = 99; // mutating the copy must not touch the effect
        assertThat(effect.diffuseColor()[0]).isEqualTo(1.0);
    }

    @Test
    void effectWithoutADiffuseColourReadsAsNull() {
        ColladaEffect textureOnly = new ColladaEffect("e", null, "phong", null, 1.0, Map.of());
        assertThat(textureOnly.diffuseColor()).isNull();
    }

    @Test
    void materialAndSceneExposeTheirBindings() {
        ColladaMaterial material = new ColladaMaterial("m", "mat", "effect");
        assertThat(material.id()).isEqualTo("m");
        assertThat(material.name()).isEqualTo("mat");
        assertThat(material.instanceEffectUrl()).isEqualTo("effect");

        ColladaVisualScene scene = new ColladaVisualScene("s", "sc", List.of());
        assertThat(scene.id()).isEqualTo("s");
        assertThat(scene.name()).isEqualTo("sc");
        assertThat(scene.nodes()).isEmpty();
    }
}
