/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7c: the <library_animations> parse half.
 *
 * Asserts the raw vocabulary the renderer's clip factory consumes: sources
 * keep their float data and accessor stride, samplers keep the hash-stripped
 * INPUT/OUTPUT source ids, the interpolation attribute and the flat index
 * pairs, channels keep the raw target string untouched (interpretation is
 * deliberately NOT here). The group-animation flatten is pinned too: it is
 * the only structural rewrite the parse half performs.
 */
package org.jdesktop.wonderland.modules.collada;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ColladaAnimationTest {

    private static final String HEAD =
            "<COLLADA xmlns=\"http://www.collada.org/2005/11/COLLADASchema\""
                    + " version=\"1.4.1\">";

    private static final ColladaParser PARSER = new ColladaParser();

    /** parse takes the document BODY; the helper supplies root + close. */
    private static ColladaDocument parse(String body) {
        return PARSER.parse(HEAD + body + "</COLLADA>");
    }

    private static final String ONE_ANIMATION
            = "<library_animations>"
            + "  <animation id=\"walk-anim\" name=\"walking\">"
            + "    <source id=\"times\">"
            + "      <float_array id=\"t-array\" count=\"2\">0 1</float_array>"
            + "      <technique_common><accessor source=\"#t-array\" count=\"2\""
            + "        stride=\"1\"/></technique_common>"
            + "    </source>"
            + "    <source id=\"bob\">"
            + "      <float_array id=\"b-array\" count=\"4\">0 0.5 1 0.5</float_array>"
            + "      <technique_common><accessor source=\"#b-array\" count=\"2\""
            + "        stride=\"2\"/></technique_common>"
            + "    </source>"
            + "    <sampler id=\"bob-sampler\" interpolation=\"LINEAR\">"
            + "      <input semantic=\"INPUT\" source=\"#times\" offset=\"0\"/>"
            + "      <input semantic=\"OUTPUT\" source=\"#bob\" offset=\"1\"/>"
            + "      <technique_common><common_accessor>"
            + "        <index>0 0 1 1</index>"
            + "      </common_accessor></technique_common>"
            + "    </sampler>"
            + "    <channel source=\"#bob-sampler\" target=\"node-1/translation.Y\"/>"
            + "  </animation>"
            + "</library_animations>"
            + "<library_visual_scenes><visual_scene id=\"s\">"
            + "  <node id=\"node-1\" name=\"n1\"/></visual_scene></library_visual_scenes>";

    @Test
    void libraryIsKeyedByIdAndKeepsTheName() {
        ColladaDocument doc = parse(ONE_ANIMATION);

        assertThat(doc.animationCount()).isEqualTo(1);
        ColladaAnimation anim = doc.animations().get("walk-anim");
        assertThat(anim).isNotNull();
        assertThat(anim.name()).isEqualTo("walking");
        assertThat(anim.isEmpty()).isFalse();
    }

    @Test
    void sourcesKeepDataAndAccessorStride() {
        ColladaAnimation anim = parse(ONE_ANIMATION).animations().get("walk-anim");

        ColladaAnimation.FloatSource times = anim.source("times");
        assertThat(times.id()).isEqualTo("times");
        assertThat(times.data()).containsExactly(0.0, 1.0);
        assertThat(times.stride()).isEqualTo(1);

        ColladaAnimation.FloatSource bob = anim.source("bob");
        assertThat(bob.data()).containsExactly(0.0, 0.5, 1.0, 0.5);
        assertThat(bob.stride()).isEqualTo(2);
    }

    @Test
    void samplerKeepsHashStrippedSourceIdsInterpolationAndIndexPairs() {
        ColladaAnimation anim = parse(ONE_ANIMATION).animations().get("walk-anim");

        ColladaAnimation.Sampler sampler = anim.sampler("bob-sampler");
        assertThat(sampler.id()).isEqualTo("bob-sampler");
        assertThat(sampler.inputSourceId()).isEqualTo("times");
        assertThat(sampler.outputSourceId()).isEqualTo("bob");
        assertThat(sampler.interpolation()).isEqualTo("LINEAR");
        assertThat(sampler.indexPairs()).containsExactly(0L, 0L, 1L, 1L);
    }

    @Test
    void channelKeepsTheRawTargetAndStrippedSamplerRef() {
        ColladaAnimation anim = parse(ONE_ANIMATION).animations().get("walk-anim");

        assertThat(anim.channels()).hasSize(1);
        assertThat(anim.channels().get(0).samplerId()).isEqualTo("bob-sampler");
        // the parse half does NOT split "node-1/translation.Y" -- that is the
        // renderer's ChannelTarget's job
        assertThat(anim.channels().get(0).target()).isEqualTo("node-1/translation.Y");
    }

    @Test
    void groupAnimationFlattensItsChildrenIntoTheParent() {
        // Blender-style export: the outer <animation> owns no channels and
        // only nests per-joint children; the merged record must carry both
        // children's sources, samplers and channels under the parent's name
        ColladaDocument doc = parse("<library_animations>"
                + "  <animation id=\"rig-anim\" name=\"dance\">"
                + "    <animation id=\"arm-anim\">"
                + "      <source id=\"arm-times\"><float_array id=\"at\" count=\"1\">0"
                + "        </float_array></source>"
                + "      <source id=\"arm-vals\"><float_array id=\"av\" count=\"1\">0.5"
                + "        </float_array></source>"
                + "      <sampler id=\"arm-s\"><input semantic=\"INPUT\" source=\"#arm-times\"/>"
                + "        <input semantic=\"OUTPUT\" source=\"#arm-vals\"/></sampler>"
                + "      <channel source=\"#arm-s\" target=\"l-arm/rotation.ANGLE\"/>"
                + "    </animation>"
                + "    <animation id=\"leg-anim\">"
                + "      <source id=\"leg-times\"><float_array id=\"lt\" count=\"1\">0"
                + "        </float_array></source>"
                + "      <sampler id=\"leg-s\"><input semantic=\"INPUT\" source=\"#leg-times\"/>"
                + "        <input semantic=\"OUTPUT\" source=\"#leg-times\"/></sampler>"
                + "      <channel source=\"#leg-s\" target=\"l-leg/translation.Z\"/>"
                + "    </animation>"
                + "  </animation>"
                + "</library_animations>");

        Map<String, ColladaAnimation> anims = doc.animations();
        assertThat(anims).containsOnlyKeys("rig-anim");
        ColladaAnimation dance = anims.get("rig-anim");
        assertThat(dance.name()).isEqualTo("dance");
        assertThat(dance.channels()).hasSize(2);
        assertThat(dance.sampler("arm-s")).isNotNull();
        assertThat(dance.sampler("leg-s")).isNotNull();
        assertThat(dance.source("arm-vals")).isNotNull();
        // the child records themselves are not separately registered
        assertThat(anims.containsKey("arm-anim")).isFalse();
    }

    @Test
    void aDocumentWithoutTheLibraryHasNoAnimations() {
        ColladaDocument doc = parse("<library_visual_scenes><visual_scene id=\"s\">"
                + "  <node id=\"n\"/></visual_scene></library_visual_scenes>");

        assertThat(doc.animations()).isEmpty();
        assertThat(doc.animationCount()).isZero();
    }

    @Test
    void aSamplerWithoutTheCommonTechniqueCarriesNoIndexPairs() {
        // some minimal exports omit <index> entirely; the factory then reads
        // the sources aligned by unit -- the parse half must not throw here
        ColladaDocument doc = parse("<library_animations><animation id=\"a\" name=\"n\">"
                + "  <source id=\"s1\"><float_array id=\"x\" count=\"1\">1</float_array></source>"
                + "  <sampler id=\"smp\">"
                + "    <input semantic=\"INPUT\" source=\"#s1\"/>"
                + "    <input semantic=\"OUTPUT\" source=\"#s1\"/>"
                + "  </sampler>"
                + "  <channel source=\"#smp\" target=\"node/translation.X\"/>"
                + "</animation></library_animations>");

        ColladaAnimation.Sampler sampler = doc.animations().get("a").sampler("smp");
        assertThat(sampler.indexPairs()).isEmpty();
        assertThat(sampler.interpolation()).isNull();
    }

    @Test
    void thePreM7cSevenArgConstructorStillDefaultsToNoAnimations() {
        // the M5-era constructor is frozen for compatibility: documents built
        // the old way honestly answer "no animations"
        ColladaDocument legacy = new ColladaDocument(null, Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), null);

        assertThat(legacy.animations()).isEmpty();
        assertThat(legacy.animationCount()).isZero();
    }
}
