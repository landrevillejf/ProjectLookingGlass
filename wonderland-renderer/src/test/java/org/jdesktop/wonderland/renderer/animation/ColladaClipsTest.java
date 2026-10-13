/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M7c: the raw-to-playable factory, end to end from real
 * COLLADA XML (ColladaParser -> ColladaAnimation -> ColladaClips).
 *
 * These cases drive the PARSED path, not hand-built records: every scenario
 * is authored as the XML an exporter emits, so the parse half and the
 * factory half are proven to agree — the same way M7b's rasteriser test
 * drives PDFBox through real generated PDFs instead of mocks.
 */
package org.jdesktop.wonderland.renderer.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Map;
import org.jdesktop.wonderland.modules.collada.ColladaDocument;
import org.jdesktop.wonderland.modules.collada.ColladaParser;
import org.junit.jupiter.api.Test;

class ColladaClipsTest {

    private static final String HEAD =
            "<COLLADA xmlns=\"http://www.collada.org/2005/11/COLLADASchema\""
                    + " version=\"1.4.1\">";

    /** A source element with its float array and accessor stride. */
    private static String source(String id, String values, int stride) {
        String arrayId = id + "-array";
        int count = values.trim().split("\\s+").length;
        return "<source id=\"" + id + "\">"
                + "<float_array id=\"" + arrayId + "\" count=\"" + count + "\">"
                + values + "</float_array>"
                + "<technique_common><accessor source=\"#" + arrayId
                + "\" count=\"" + count + "\" stride=\"" + stride + "\"/></technique_common>"
                + "</source>";
    }

    /** A sampler with aligned (0 0 1 1 ...) index pairs against this input/output. */
    private static String sampler(String id, String inputId, String outputId,
                                  String interpolation, int keys) {
        StringBuilder pairs = new StringBuilder();
        for (int i = 0; i < keys; i++) {
            pairs.append(i).append(' ').append(i).append(' ');
        }
        return "<sampler id=\"" + id + "\""
                + (interpolation == null ? "" : " interpolation=\"" + interpolation + "\"") + ">"
                + "<input semantic=\"INPUT\" source=\"#" + inputId + "\"/>"
                + "<input semantic=\"OUTPUT\" source=\"#" + outputId + "\"/>"
                + "<technique_common><common_accessor><index>"
                + pairs.toString().trim() + "</index></common_accessor></technique_common>"
                + "</sampler>";
    }

    private static ColladaDocument doc(String library) {
        return new ColladaParser().parse(HEAD + library + "</COLLADA>");
    }

    /** One translation-y bob animation, three keys over 0..2 s. */
    private static String bobLibrary(String animId, String name, String interpolation) {
        return "<library_animations><animation id=\"" + animId + "\""
                + (name == null ? "" : " name=\"" + name + "\"") + ">"
                + source("t", "0 1 2", 1)
                + source("v", "0 0.5 0", 1)
                + sampler("s", "t", "v", interpolation, 3)
                + "<channel source=\"#s\" target=\"body/translation.Y\"/>"
                + "</animation></library_animations>";
    }

    @Test
    void aFullParsedDocumentProducesAPlayableClipWithSampledPoses() {
        ColladaDocument document = doc(bobLibrary("bob-anim", "walking", "LINEAR"));

        Map<String, AnimationClip> clips = ColladaClips.fromDocument(document);
        assertThat(clips).containsOnlyKeys("walking");
        AnimationClip clip = clips.get("walking");
        assertThat(clip.duration()).isEqualTo(2.0);

        var pose = clip.sampleAt(1.0).get("body");
        assertThat(pose.m13).isCloseTo(0.5, within(1e-9));
        assertThat(pose.m00).isEqualTo(1.0); // untouched channels stay neutral
    }

    @Test
    void theStride4RotationBundleDecomposesIntoAxisAndAngle() {
        ColladaDocument document = doc("<library_animations>"
                + "  <animation id=\"tilt\" name=\"tilting\">"
                + source("t", "0 1", 1)
                + source("r", "0 0 1 0.1  0 0 1 0.2", 4)
                + sampler("s", "t", "r", "LINEAR", 2)
                + "    <channel source=\"#s\" target=\"body/rotation.X\"/>"
                + "    <channel source=\"#s\" target=\"body/rotation.Y\"/>"
                + "    <channel source=\"#s\" target=\"body/rotation.Z\"/>"
                + "    <channel source=\"#s\" target=\"body/rotation.ANGLE\"/>"
                + "  </animation></library_animations>");

        AnimationClip clip = ColladaClips.fromDocument(document).get("tilting");
        assertThat(clip).isNotNull();
        // halfway: axis (0,0,1), angle 0.15 rad -> m01 = -sin(0.15)
        var pose = clip.sampleAt(0.5).get("body");
        assertThat(pose.m01).isCloseTo(-Math.sin(0.15), within(1e-9));
        assertThat(pose.m22).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void bezierCollapsesToLinearRatherThanToAWrongCurve() {
        ColladaDocument document = doc(bobLibrary("b", "x", "BEZIER"));

        AnimationClip clip = ColladaClips.fromDocument(document).get("x");
        assertThat(clip.sampleAt(0.5).get("body").m13).isCloseTo(0.25, within(1e-9));
    }

    @Test
    void noneInterpolationBecomesStep() {
        ColladaDocument document = doc(bobLibrary("n", "x", "NONE"));

        AnimationClip clip = ColladaClips.fromDocument(document).get("x");
        assertThat(clip.sampleAt(0.5).get("body").m13).isZero(); // holds key 0
        assertThat(clip.sampleAt(1.0).get("body").m13).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void keysDoNotHaveToStartAtZeroTheClipIsNormalised() {
        ColladaDocument document = doc("<library_animations><animation id=\"late\" name=\"x\">"
                + source("t", "5 6", 1)
                + source("v", "1 2", 1)
                + sampler("s", "t", "v", "LINEAR", 2)
                + "<channel source=\"#s\" target=\"body/translation.X\"/>"
                + "</animation></library_animations>");

        AnimationClip clip = ColladaClips.fromDocument(document).get("x");
        assertThat(clip.duration()).isEqualTo(1.0); // 5..6 shifted to 0..1
        assertThat(clip.sampleAt(0.0).get("body").m03).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void anUnnamedAnimationIsKeyedByItsId() {
        ColladaDocument document = doc(bobLibrary("solo-anim", null, null));

        assertThat(ColladaClips.fromDocument(document)).containsOnlyKeys("solo-anim");
    }

    @Test
    void aSamplerWithoutIndexPairsReadsTheSourcesAligned() {
        ColladaDocument document = doc("<library_animations><animation id=\"a\" name=\"x\">"
                + source("t", "0 2", 1)
                + source("v", "3 7", 1)
                + "  <sampler id=\"s\">"
                + "    <input semantic=\"INPUT\" source=\"#t\"/>"
                + "    <input semantic=\"OUTPUT\" source=\"#v\"/>"
                + "  </sampler>"
                + "<channel source=\"#s\" target=\"body/translation.Z\"/>"
                + "</animation></library_animations>");

        AnimationClip clip = ColladaClips.fromDocument(document).get("x");
        assertThat(clip.duration()).isEqualTo(2.0);
        assertThat(clip.sampleAt(1.0).get("body").m23).isCloseTo(5.0, within(1e-9));
    }

    @Test
    void unboundChannelsAndUnparsableTargetsAreDroppedNotFatal() {
        ColladaDocument document = doc("<library_animations><animation id=\"a\" name=\"mixed\">"
                + source("t", "0 1", 1)
                + source("v", "0 1", 1)
                + sampler("s", "t", "v", "LINEAR", 2)
                + "<channel source=\"#no-such-sampler\" target=\"body/translation.X\"/>"
                + "<channel source=\"#s\" target=\"body/matrix\"/>"           // unsupported kind
                + "<channel source=\"#s\" target=\"garbage-without-slash\"/>" // no element
                + "<channel source=\"#s\" target=\"body/translation.Y\"/>"    // the survivor
                + "</animation></library_animations>");

        AnimationClip clip = ColladaClips.fromDocument(document).get("mixed");
        assertThat(clip).isNotNull();
        assertThat(clip.animatedNodes()).containsExactly("body");
        // only the Y channel got in: X stayed neutral while Y reached its key
        assertThat(clip.sampleAt(1.0).get("body").m13).isCloseTo(1.0, within(1e-9));
        assertThat(clip.sampleAt(1.0).get("body").m03).isZero();
    }

    @Test
    void aStride3RotationBundleIsEulerAndGetsDroppedNotMisread() {
        ColladaDocument document = doc("<library_animations><animation id=\"e\" name=\"x\">"
                + source("t", "0 1", 1)
                + source("v", "0.1 0.2 0.3 0.4 0.5 0.6", 3)
                + sampler("s", "t", "v", "LINEAR", 2)
                + "<channel source=\"#s\" target=\"body/rotation.X\"/>"
                + "</animation></library_animations>");

        assertThat(ColladaClips.fromDocument(document)).isEmpty();
    }

    @Test
    void aClipWhoseEveryChannelDroppedProducesNothing() {
        ColladaDocument document = doc("<library_animations><animation id=\"d\" name=\"dead\">"
                + "<channel source=\"#ghost\" target=\"body/translation.X\"/>"
                + "</animation></library_animations>");

        assertThat(ColladaClips.fromDocument(document)).isEmpty();
    }

    @Test
    void nullDocumentsAndChannelLessRecordsAreRefusedWithoutThrowing() {
        assertThat(ColladaClips.fromDocument(null)).isEmpty();
        assertThat(ColladaClips.fromAnimation(null)).isNull();
    }
}
