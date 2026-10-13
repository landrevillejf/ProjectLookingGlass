/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/* wonderland-renderer M7c: the catalog and its synthetic-fallback rule. */
package org.jdesktop.wonderland.renderer.animation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnimationLibraryTest {

    private static AnimationClip stubClip(String name) {
        Map<String, AnimationClip.NodeTracks> nodes = new LinkedHashMap<>();
        AnimationClip.NodeTracks tracks = new AnimationClip.NodeTracks();
        tracks.translation(0, new KeyframeTrack(new double[] {0, 1}, new double[] {0, 1},
                KeyframeTrack.Interpolation.LINEAR));
        nodes.put("n", tracks);
        return new AnimationClip(name, nodes);
    }

    @Test
    void lookupIsCaseInsensitiveBecauseTheWireStringIsArbitrary() {
        Map<String, AnimationClip> clips = new LinkedHashMap<>();
        clips.put("Walking", stubClip("Walking"));
        AnimationLibrary lib = AnimationLibrary.of(clips);

        assertThat(lib.clip("WALKING")).isNotNull();
        assertThat(lib.clip("walking")).isNotNull();
        assertThat(lib.names()).containsExactly("walking");
        assertThat(lib.clip("missing")).isNull();
        assertThat(lib.clip(null)).isNull();
    }

    @Test
    void anEmptyOrNullClipMapBuildsAnEmptyCatalog() {
        Map<String, AnimationClip> withNulls = new LinkedHashMap<>();
        withNulls.put("x", null);

        assertThat(AnimationLibrary.of(Map.of()).isEmpty()).isTrue();
        assertThat(AnimationLibrary.of(withNulls).isEmpty()).isTrue();
    }

    @Test
    void anUnriggedDocumentFallsBackToTheSyntheticTrio() {
        // a document with no <library_animations> at all
        var document = new org.jdesktop.wonderland.modules.collada.ColladaParser().parse(
                "<COLLADA><library_visual_scenes><visual_scene id=\"s\">"
                        + "<node id=\"body\"/></visual_scene></library_visual_scenes></COLLADA>");

        AnimationLibrary lib = AnimationLibrary.forDocument(document, "body");

        assertThat(lib.isSynthetic()).isTrue();
        assertThat(lib.names()).contains("idle", "walking", "waving");
        assertThat(lib.clip("idle").animatedNodes()).containsExactly("body");
    }

    @Test
    void anAuthoredDocumentWinsOverTheSyntheticFallback() {
        // the humanoid-a shape: one named animation parsed from XML
        var document = new org.jdesktop.wonderland.modules.collada.ColladaParser().parse(
                "<COLLADA><library_animations><animation id=\"a\" name=\"stomp\">"
                        + "<source id=\"t\"><float_array id=\"ta\" count=\"2\">0 1"
                        + "</float_array></source>"
                        + "<source id=\"v\"><float_array id=\"va\" count=\"2\">0 0.5"
                        + "</float_array></source>"
                        + "<sampler id=\"s\"><input semantic=\"INPUT\" source=\"#t\"/>"
                        + "<input semantic=\"OUTPUT\" source=\"#v\"/>"
                        + "<technique_common><common_accessor><index>0 0 1 1</index>"
                        + "</common_accessor></technique_common></sampler>"
                        + "<channel source=\"#s\" target=\"body/translation.Y\"/>"
                        + "</animation></library_animations>"
                        + "<library_visual_scenes><visual_scene id=\"sc\">"
                        + "<node id=\"body\"/></visual_scene></library_visual_scenes></COLLADA>");

        AnimationLibrary lib = AnimationLibrary.forDocument(document, "body");

        assertThat(lib.isSynthetic()).isFalse();
        assertThat(lib.names()).containsExactly("stomp"); // authored set only, no trio
    }
}
