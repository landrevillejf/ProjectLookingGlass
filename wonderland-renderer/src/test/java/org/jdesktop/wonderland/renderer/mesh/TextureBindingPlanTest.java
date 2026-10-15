/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6a: the pure material -> effect -> sampler -> image
 * chase the M6 renderer uses to decide what each shape becomes at build time.
 *
 * The 2006 loader resolved a material symbol against the effect library, then
 * walked its sampler list to a set of <image> init_from paths, then asked the
 * deployed model whether each of those was actually present. A missing texture
 * degraded to the effect's diffuse colour and a warning -- never a load
 * failure. This class rebuilds exactly that decision, and every branch --
 * textured / colour-only / default grey on a missing effect, alpha from a
 * 4-wide diffuse vs from the transparency factor, first-present sampler wins
 * among several -- is asserted here so the probe side of the seam is left with
 * only the Java 3D constructors to run.
 */
package org.jdesktop.wonderland.renderer.mesh;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdesktop.wonderland.modules.collada.ColladaDocument;
import org.jdesktop.wonderland.modules.collada.ColladaEffect;
import org.jdesktop.wonderland.modules.collada.ColladaImage;
import org.jdesktop.wonderland.modules.collada.ColladaMaterial;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TextureBindingPlanTest {

    private static ColladaDocument emptyDoc() {
        return new ColladaDocument(null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null);
    }

    private static ColladaDocument doc(Map<String, ColladaMaterial> mats,
                                        Map<String, ColladaEffect> effects,
                                        Map<String, ColladaImage> images) {
        return new ColladaDocument(null, Map.of(), mats, effects, images, Map.of(), null);
    }

    @Test
    void aNullDocumentYieldsTheNeutralGreyOpaqueFallback() {
        TextureBindingPlan plan = new TextureBindingPlan(null, List.of());

        MaterialAppearance ap = plan.forMaterial("anything");

        assertThat(ap.red()).isEqualTo(0.5f);
        assertThat(ap.green()).isEqualTo(0.5f);
        assertThat(ap.blue()).isEqualTo(0.5f);
        assertThat(ap.alpha()).isEqualTo(1.0f);
        assertThat(ap.isTextured()).isFalse();
    }

    @Test
    void anUnknownMaterialSymbolStillYieldsGreySoTheShapeDoesNotDropOut() {
        TextureBindingPlan plan = new TextureBindingPlan(emptyDoc(), List.of());

        MaterialAppearance ap = plan.forMaterial("no-such-material");

        assertThat(ap.rgba()).containsExactly(0.5f, 0.5f, 0.5f, 1.0f);
        assertThat(ap.isTextured()).isFalse();
    }

    @Test
    void aMaterialWithNoEffectUrlYieldsGrey() {
        ColladaMaterial m = new ColladaMaterial("mat0", "mat0", null);
        ColladaDocument d = doc(Map.of("mat0", m), Map.of(), Map.of());

        MaterialAppearance ap = new TextureBindingPlan(d, List.of()).forMaterial("mat0");

        assertThat(ap.isTextured()).isFalse();
        assertThat(ap.rgba()).containsExactly(0.5f, 0.5f, 0.5f, 1.0f);
    }

    @Test
    void anEffectWithADiffuseAndNoSamplerRendersUntextured() {
        ColladaEffect e = new ColladaEffect("fx", "fx", "phong",
                new double[]{0.8, 0.2, 0.1, 1.0}, 1.0, Map.of());
        ColladaMaterial m = new ColladaMaterial("mat0", "mat0", "fx");
        ColladaDocument d = doc(Map.of("mat0", m), Map.of("fx", e), Map.of());

        MaterialAppearance ap = new TextureBindingPlan(d, List.of()).forMaterial("mat0");

        assertThat(ap.isTextured()).isFalse();
        assertThat(ap.red()).isEqualTo(0.8f);
        assertThat(ap.green()).isEqualTo(0.2f);
        assertThat(ap.blue()).isEqualTo(0.1f);
        assertThat(ap.alpha()).isEqualTo(1.0f);
    }

    @Test
    void aSamplerPointingAtAPresentImageBindsItsNormalizedPath() {
        ColladaImage img = new ColladaImage("img0", "file://./untitled/texture0.png");
        ColladaEffect e = new ColladaEffect("fx", "fx", "lambert",
                new double[]{1, 1, 1, 1}, 1.0, Map.of("sampler0", "img0"));
        ColladaMaterial m = new ColladaMaterial("mat0", "mat0", "fx");
        ColladaDocument d = doc(Map.of("mat0", m), Map.of("fx", e), Map.of("img0", img));

        MaterialAppearance ap = new TextureBindingPlan(d,
                List.of("./untitled/texture0.png")).forMaterial("mat0");

        assertThat(ap.isTextured()).isTrue();
        assertThat(ap.texturePath()).isEqualTo("./untitled/texture0.png");
        // the diffuse colour is kept as a tint even when textured, matching
        // the 2006 loader's MODULATE behaviour
        assertThat(ap.red()).isEqualTo(1.0f);
    }

    @Test
    void aSamplerMissingFromTheStoreFallsBackToColourOnly() {
        ColladaImage img = new ColladaImage("img0", "./untitled/texture9.png");
        ColladaEffect e = new ColladaEffect("fx", "fx", "lambert",
                new double[]{0.2, 0.3, 0.4}, 1.0, Map.of("sampler0", "img0"));
        ColladaMaterial m = new ColladaMaterial("mat0", "mat0", "fx");
        ColladaDocument d = doc(Map.of("mat0", m), Map.of("fx", e), Map.of("img0", img));

        // presentTextures is the store's inventory; texture9 is not there
        MaterialAppearance ap = new TextureBindingPlan(d,
                List.of("untitled/other.png")).forMaterial("mat0");

        assertThat(ap.isTextured()).isFalse();
        assertThat(ap.texturePath()).isNull();
        assertThat(ap.red()).isEqualTo(0.2f);
    }

    @Test
    void aThreeWideDiffuseTakesAlphaFromTheEffectsTransparencyFactor() {
        // COLLADA <transparent> factor 0.75 means 75% transparent -> alpha 0.25
        // is what the loader's convention was; the plan reads transparency as
        // the alpha directly (matching how the 2006 loader treated 1.0 as fully
        // opaque and lower values as see-through)
        ColladaEffect e = new ColladaEffect("fx", "fx", "lambert",
                new double[]{0.1, 0.2, 0.3}, 0.75, Map.of());
        ColladaMaterial m = new ColladaMaterial("mat0", "mat0", "fx");
        ColladaDocument d = doc(Map.of("mat0", m), Map.of("fx", e), Map.of());

        MaterialAppearance ap = new TextureBindingPlan(d, List.of()).forMaterial("mat0");

        assertThat(ap.alpha()).isEqualTo(0.75f);
    }

    @Test
    void aFourWideDiffusesOwnAlphaWinsOverTheTransparencyFactor() {
        ColladaEffect e = new ColladaEffect("fx", "fx", "phong",
                new double[]{0.5, 0.6, 0.7, 0.9}, 0.1, Map.of());
        ColladaMaterial m = new ColladaMaterial("mat0", "mat0", "fx");
        ColladaDocument d = doc(Map.of("mat0", m), Map.of("fx", e), Map.of());

        MaterialAppearance ap = new TextureBindingPlan(d, List.of()).forMaterial("mat0");

        assertThat(ap.alpha()).isEqualTo(0.9f); // diffuse[3], not transparency
    }

    @Test
    void aNullDiffuseFallsBackToTheNeutralGreyWithTransparencyAlpha() {
        // Texture-only <diffuse> is stored as a null diffuseColor; the plan
        // then uses the DEFAULT_COLOR rgb but still reads the transparency
        ColladaEffect e = new ColladaEffect("fx", "fx", "blinn",
                null, 0.4, Map.of());
        ColladaMaterial m = new ColladaMaterial("mat0", "mat0", "fx");
        ColladaDocument d = doc(Map.of("mat0", m), Map.of("fx", e), Map.of());

        MaterialAppearance ap = new TextureBindingPlan(d, List.of()).forMaterial("mat0");

        assertThat(ap.rgba()).containsExactly(0.5f, 0.5f, 0.5f, 0.4f);
    }

    @Test
    void theFirstPresentSamplerWinsWhenSeveralAreBound() {
        ColladaImage imgA = new ColladaImage("imgA", "./a.png");
        ColladaImage imgB = new ColladaImage("imgB", "./b.png");
        // samplers preserves encounter order; the plan takes the first whose
        // image path is in the present set
        ColladaEffect e = new ColladaEffect("fx", "fx", "lambert",
                new double[]{1, 1, 1, 1}, 1.0, Map.of("s0", "imgA", "s1", "imgB"));
        ColladaMaterial m = new ColladaMaterial("mat0", "mat0", "fx");
        ColladaDocument d = doc(Map.of("mat0", m), Map.of("fx", e),
                Map.of("imgA", imgA, "imgB", imgB));

        // A is absent, B is present: A skips, B wins. Map.of() ordering is
        // unspecified, so this test relies on the plan's contains() check
        // picking whichever sampler the iteration surfaces first.
        MaterialAppearance ap = new TextureBindingPlan(d,
                Set.of("./b.png")).forMaterial("mat0");

        assertThat(ap.isTextured()).isTrue();
        assertThat(ap.texturePath()).isEqualTo("./b.png");
    }

    @Test
    void anUnresolvableImageIdSkipsTheSamplerAndKeepsLooking() {
        ColladaImage imgGood = new ColladaImage("imgGood", "./good.png");
        ColladaEffect e = new ColladaEffect("fx", "fx", "lambert",
                new double[]{1, 1, 1, 1}, 1.0,
                Map.of("s0", "imgDangling", "s1", "imgGood"));
        ColladaMaterial m = new ColladaMaterial("mat0", "mat0", "fx");
        ColladaDocument d = doc(Map.of("mat0", m), Map.of("fx", e), Map.of("imgGood", imgGood));

        MaterialAppearance ap = new TextureBindingPlan(d,
                Set.of("./good.png")).forMaterial("mat0");

        // imgDangling resolves to no ColladaImage -> skipped; imgGood -> hit
        assertThat(ap.isTextured()).isTrue();
        assertThat(ap.texturePath()).isEqualTo("./good.png");
    }

    @Test
    void aNullPresentTexturesCollectionIsTreatedAsEmptyNotAsAThrow() {
        ColladaImage img = new ColladaImage("img0", "./a.png");
        ColladaEffect e = new ColladaEffect("fx", "fx", "lambert",
                new double[]{1, 1, 1, 1}, 1.0, Map.of("s0", "img0"));
        ColladaMaterial m = new ColladaMaterial("mat0", "mat0", "fx");
        ColladaDocument d = doc(Map.of("mat0", m), Map.of("fx", e), Map.of("img0", img));

        TextureBindingPlan plan = new TextureBindingPlan(d, null);

        assertThat(plan.forMaterial("mat0").isTextured()).isFalse();
    }

    @Test
    void forAllReturnsOneEntryPerDistinctIdInEncounterOrderDedupingRepeats() {
        ColladaEffect e = new ColladaEffect("fx", "fx", "lambert",
                new double[]{0.1, 0.1, 0.1, 1}, 1.0, Map.of());
        ColladaMaterial mA = new ColladaMaterial("A", "A", "fx");
        ColladaMaterial mB = new ColladaMaterial("B", "B", "fx");
        ColladaDocument d = doc(Map.of("A", mA, "B", mB), Map.of("fx", e), Map.of());

        Map<String, MaterialAppearance> map = new TextureBindingPlan(d, List.of())
                .forAll(List.of("A", "B", "A", "B", "A"));

        // LinkedHashMap: encounter order, repeats collapsed
        assertThat(map).containsOnlyKeys("A", "B");
        assertThat(map.keySet()).containsExactly("A", "B");
    }

    @Test
    void forAllPreservesANullEntryForAnUnknownSymbolSoTheCallerCanDecide() {
        ColladaDocument d = emptyDoc();

        Map<String, MaterialAppearance> map = new TextureBindingPlan(d, List.of())
                .forAll(List.of("unknown"));

        assertThat(map).containsKey("unknown");
        // the plan's forMaterial on an unknown id yields the neutral grey -- it
        // never returns null so the shape always has something to bind to
        assertThat(map.get("unknown").rgba()).containsExactly(0.5f, 0.5f, 0.5f, 1.0f);
    }

    @Test
    void aMaterialWhoseEffectReferencePointsToAMissingEffectYieldsGrey() {
        // instance_effect url set but the effect library has no such id -- the
        // 2006 loader treated that as an authoring warning and rendered the
        // default material, which is what forMaterial returns here
        ColladaMaterial m = new ColladaMaterial("mat0", "mat0", "missingFx");
        ColladaDocument d = doc(Map.of("mat0", m), Map.of(), Map.of());

        MaterialAppearance ap = new TextureBindingPlan(d, List.of()).forMaterial("mat0");

        assertThat(ap.rgba()).containsExactly(0.5f, 0.5f, 0.5f, 1.0f);
        assertThat(ap.isTextured()).isFalse();
    }

    // ---- roadmap P2.1: sampler classification into texture layers ----------

    /** Ordered samplers: Map.of() would make "which one is diffuse" a coin flip. */
    private static Map<String, String> samplers(String... imageNameIdPairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < imageNameIdPairs.length; i += 2) {
            map.put(imageNameIdPairs[i], imageNameIdPairs[i + 1]);
        }
        return map;
    }

    @Test
    void aNormalSamplerIsRecordedAsALayerAndNotBoundAsColour() {
        ColladaImage diffuse = new ColladaImage("imgD", "./wall.png");
        ColladaImage normal = new ColladaImage("imgN", "./wall-normal.png");
        ColladaEffect e = new ColladaEffect("fx", "fx", "phong",
                new double[]{1, 1, 1, 1}, 1.0,
                samplers("sampler-wall", "imgD", "sampler-normal", "imgN"));
        ColladaMaterial m = new ColladaMaterial("mat0", "mat0", "fx");
        ColladaDocument d = doc(Map.of("mat0", m), Map.of("fx", e),
                Map.of("imgD", diffuse, "imgN", normal));

        MaterialAppearance ap = new TextureBindingPlan(d,
                Set.of("./wall.png", "./wall-normal.png")).forMaterial("mat0");

        assertThat(ap.texturePath()).as("the colour layer is the ordinary sampler")
                .isEqualTo("./wall.png");
        assertThat(ap.normalMapPath()).isEqualTo("./wall-normal.png");
        assertThat(ap.hasSpecularMap()).isFalse();
        assertThat(ap.layerCount()).isEqualTo(2);
    }

    @Test
    void aSpecularSamplerFeedsTheSpecularLayer() {
        ColladaImage diffuse = new ColladaImage("imgD", "./floor.png");
        ColladaImage spec = new ColladaImage("imgS", "./floor-spec.png");
        ColladaEffect e = new ColladaEffect("fx", "fx", "phong",
                new double[]{1, 1, 1, 1}, 1.0,
                samplers("sampler-floor", "imgD", "sampler-spec", "imgS"));
        ColladaMaterial m = new ColladaMaterial("mat0", "mat0", "fx");
        ColladaDocument d = doc(Map.of("mat0", m), Map.of("fx", e),
                Map.of("imgD", diffuse, "imgS", spec));

        MaterialAppearance ap = new TextureBindingPlan(d,
                Set.of("./floor.png", "./floor-spec.png")).forMaterial("mat0");

        assertThat(ap.texturePath()).isEqualTo("./floor.png");
        assertThat(ap.specularMapPath()).isEqualTo("./floor-spec.png");
        assertThat(ap.layerCount()).isEqualTo(2);
    }

    @Test
    void theThreeLayerMaterialCarriesEveryPathAtOnce() {
        ColladaImage diffuse = new ColladaImage("imgD", "./panel.png");
        ColladaImage normal = new ColladaImage("imgN", "./panel-bump.png");
        ColladaImage spec = new ColladaImage("imgS", "./panel-spec.png");
        ColladaEffect e = new ColladaEffect("fx", "fx", "phong",
                new double[]{1, 1, 1, 1}, 1.0,
                samplers("s0", "imgD", "s1-normal", "imgN", "s2-spec", "imgS"));
        ColladaMaterial m = new ColladaMaterial("mat0", "mat0", "fx");
        ColladaDocument d = doc(Map.of("mat0", m), Map.of("fx", e),
                Map.of("imgD", diffuse, "imgN", normal, "imgS", spec));

        MaterialAppearance ap = new TextureBindingPlan(d,
                Set.of("./panel.png", "./panel-bump.png", "./panel-spec.png"))
                        .forMaterial("mat0");

        assertThat(ap.layerCount()).isEqualTo(3);
        assertThat(ap.texturePath()).isEqualTo("./panel.png");
        assertThat(ap.normalMapPath()).isEqualTo("./panel-bump.png");
        assertThat(ap.specularMapPath()).isEqualTo("./panel-spec.png");
    }

    @Test
    void aMissingLayerImageSimplyLeavesThatLayerUnresolved() {
        ColladaImage diffuse = new ColladaImage("imgD", "./wall.png");
        ColladaImage normal = new ColladaImage("imgN", "./wall-normal.png");
        ColladaEffect e = new ColladaEffect("fx", "fx", "phong",
                new double[]{1, 1, 1, 1}, 1.0,
                samplers("sampler-wall", "imgD", "sampler-normal", "imgN"));
        ColladaMaterial m = new ColladaMaterial("mat0", "mat0", "fx");
        ColladaDocument d = doc(Map.of("mat0", m), Map.of("fx", e),
                Map.of("imgD", diffuse, "imgN", normal));

        // the store holds the diffuse only -- the normal map failed to deploy
        MaterialAppearance ap = new TextureBindingPlan(d, Set.of("./wall.png"))
                .forMaterial("mat0");

        assertThat(ap.texturePath()).isEqualTo("./wall.png");
        assertThat(ap.hasNormalMap()).as("a named layer the store lacks is null, not an error")
                .isFalse();
    }

    @Test
    void aWorldWhoseOnlyTextureIsNamedNormalStillRendersLikeBefore() {
        // Backward-compat gate: an exporter that ships ONE texture and names it
        // "normal" must not leave the shape untextured where the pre-P2.1
        // first-present scan drew it. The path serves double duty -- colour for
        // the fixed pipeline, recorded layer for the shader path.
        ColladaImage normal = new ColladaImage("imgN", "./sculpt-normal.png");
        ColladaEffect e = new ColladaEffect("fx", "fx", "lambert",
                new double[]{1, 1, 1, 1}, 1.0, samplers("sampler-normal", "imgN"));
        ColladaMaterial m = new ColladaMaterial("mat0", "mat0", "fx");
        ColladaDocument d = doc(Map.of("mat0", m), Map.of("fx", e), Map.of("imgN", normal));

        MaterialAppearance ap = new TextureBindingPlan(d, Set.of("./sculpt-normal.png"))
                .forMaterial("mat0");

        assertThat(ap.isTextured()).isTrue();
        assertThat(ap.texturePath()).isEqualTo("./sculpt-normal.png");
        assertThat(ap.normalMapPath()).isEqualTo("./sculpt-normal.png");
    }
}
