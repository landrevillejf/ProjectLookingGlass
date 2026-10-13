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

    /**
     * The document's order is the contract, not the keys' hashes. Both views are
     * consumed as sequences: {@code names()} is the clip listing, and
     * {@code animatedNodes()} / {@code sampleAt()} are the order the live
     * renderer walks when it writes the per-node LOCAL transforms. An
     * unordered immutable copy ({@code Map.copyOf}) reorders by hash and its
     * sequence varies between JVM runs, so this pins the guarantee at the three
     * places that made it.
     */
    @Test
    void clipAndNodeOrderIsTheAuthorsNotTheHashesOfTheNames() {
        // (a) the catalog keeps the order it was handed, whatever the names hash to
        Map<String, AnimationClip> clips = new LinkedHashMap<>();
        clips.put("zulu", stubClip("zulu"));
        clips.put("alpha", stubClip("alpha"));
        clips.put("mike", stubClip("mike"));

        assertThat(AnimationLibrary.of(clips).names())
                .containsExactly("zulu", "alpha", "mike");

        // (b) a clip keeps its node order through the walk the renderer uses
        Map<String, AnimationClip.NodeTracks> nodes = new LinkedHashMap<>();
        for (String key : new String[] {"zulu-node", "alpha-node", "mike-node"}) {
            AnimationClip.NodeTracks tracks = new AnimationClip.NodeTracks();
            tracks.translation(0, new KeyframeTrack(new double[] {0, 1},
                    new double[] {0, 1}, KeyframeTrack.Interpolation.LINEAR));
            nodes.put(key, tracks);
        }
        AnimationClip clip = new AnimationClip("walk", nodes);

        assertThat(clip.animatedNodes())
                .containsExactly("zulu-node", "alpha-node", "mike-node");
        assertThat(clip.sampleAt(0.5).keySet())
                .containsExactly("zulu-node", "alpha-node", "mike-node");

        // (c) end to end: two <animation> elements parse in document order, and
        // the catalog built from them lists them the same way
        var document = new org.jdesktop.wonderland.modules.collada.ColladaParser().parse(
                "<COLLADA><library_animations>"
                        + animationXml("z", "zulu")
                        + animationXml("a", "alpha")
                        + "</library_animations>"
                        + "<library_visual_scenes><visual_scene id=\"s\">"
                        + "<node id=\"body\"/></visual_scene></library_visual_scenes></COLLADA>");

        assertThat(document.animations().keySet()).containsExactly("z", "a");
        assertThat(AnimationLibrary.forDocument(document, "body").names())
                .containsExactly("zulu", "alpha");
    }

    /** One minimal playable named animation, in the 1.4.1 exporter's shape. */
    private static String animationXml(String id, String name) {
        return "<animation id=\"" + id + "\" name=\"" + name + "\">"
                + "<source id=\"" + id + "-t\"><float_array id=\"" + id + "-ta\""
                + " count=\"2\">0 1</float_array></source>"
                + "<source id=\"" + id + "-v\"><float_array id=\"" + id + "-va\""
                + " count=\"2\">0 0.5</float_array></source>"
                + "<sampler id=\"" + id + "-s\"><input semantic=\"INPUT\""
                + " source=\"#" + id + "-t\"/>"
                + "<input semantic=\"OUTPUT\" source=\"#" + id + "-v\"/>"
                + "<technique_common><common_accessor><index>0 0 1 1</index>"
                + "</common_accessor></technique_common></sampler>"
                + "<channel source=\"#" + id + "-s\" target=\"body/translation.Y\"/>"
                + "</animation>";
    }
}
