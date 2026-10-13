/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M7c: the built-in clips for bodies that ship no
 * <library_animations>.
 *
 * The bundled M6b placeholder pool is three un-rigged box humanoids; a real
 * artist body with authored clips may take weeks longer than this milestone.
 * The 2006 desktop never showed a perfectly static peer avatar -- the jME
 * Animator kept idle/walk/gesture clips rolling for every body -- so
 * "no animation on an un-rigged body" would itself be a functionality loss.
 * These three hand-authored keyframe sets are the honest stand-in: whole-
 * body bobs and tilts on the body's root node, playable by the same animator
 * and the same director policy as parsed COLLADA clips, and superseded per
 * body as soon as that body carries real clips (AnimationLibrary only falls
 * back here when the document offers none).
 *
 * Names match the wire vocabulary the 2006 client sent in
 * MovableAvatarMessage.animationName ("idle", "walking", "waving"), so the
 * director's alias table resolves them without special cases.
 */
package org.jdesktop.wonderland.renderer.animation;

import java.util.LinkedHashMap;
import java.util.Map;

/** Hand-authored idle / walking / waving clips over one node. */
public final class SyntheticClips {

    /** The clip names every un-rigged body answers with. */
    public static final String IDLE = "idle";
    public static final String WALKING = "walking";
    public static final String WAVING = "waving";

    private SyntheticClips() {
    }

    /** The standard trio, keyed by name, animated on {@code nodeKey}. */
    public static Map<String, AnimationClip> defaults(String nodeKey) {
        Map<String, AnimationClip> out = new LinkedHashMap<>();
        out.put(IDLE, idle(nodeKey));
        out.put(WALKING, walking(nodeKey));
        out.put(WAVING, waving(nodeKey));
        return out;
    }

    /** A slow breathing bob: Y 0 -> 0.02 -> 0 over 4 s. */
    public static AnimationClip idle(String nodeKey) {
        return bob("idle", nodeKey,
                new double[] {0, 2, 4}, new double[] {0, 0.02, 0});
    }

    /** A step-cycle bob plus a slight Z-axis body tilt, 1 s per stride. */
    public static AnimationClip walking(String nodeKey) {
        Map<String, AnimationClip.NodeTracks> nodes = new LinkedHashMap<>();
        AnimationClip.NodeTracks tracks = new AnimationClip.NodeTracks();
        tracks.translation(1, new KeyframeTrack(
                new double[] {0, 0.5, 1.0}, new double[] {0, 0.06, 0},
                KeyframeTrack.Interpolation.LINEAR));
        // the same axis (0,0,1) at every key, tilting +-0.05 rad: stride-4
        // bundle style, exactly what the parsed path produces for body A
        double[] times = {0, 0.5, 1.0};
        tracks.rotationAxis(0, new KeyframeTrack(times, new double[] {0, 0, 0},
                KeyframeTrack.Interpolation.LINEAR));
        tracks.rotationAxis(1, new KeyframeTrack(times, new double[] {0, 0, 0},
                KeyframeTrack.Interpolation.LINEAR));
        tracks.rotationAxis(2, new KeyframeTrack(times, new double[] {1, 1, 1},
                KeyframeTrack.Interpolation.LINEAR));
        tracks.rotationAngle(new KeyframeTrack(times, new double[] {0.05, -0.05, 0.05},
                KeyframeTrack.Interpolation.LINEAR));
        nodes.put(nodeKey, tracks);
        return new AnimationClip("walking", nodes);
    }

    /** A one-shot greeting tilt: +0.35 rad about Z and back over 1.2 s. */
    public static AnimationClip waving(String nodeKey) {
        Map<String, AnimationClip.NodeTracks> nodes = new LinkedHashMap<>();
        AnimationClip.NodeTracks tracks = new AnimationClip.NodeTracks();
        double[] times = {0, 0.4, 0.8, 1.2};
        tracks.rotationAxis(0, new KeyframeTrack(times, new double[] {0, 0, 0, 0},
                KeyframeTrack.Interpolation.LINEAR));
        tracks.rotationAxis(1, new KeyframeTrack(times, new double[] {0, 0, 0, 0},
                KeyframeTrack.Interpolation.LINEAR));
        tracks.rotationAxis(2, new KeyframeTrack(times, new double[] {1, 1, 1, 1},
                KeyframeTrack.Interpolation.LINEAR));
        tracks.rotationAngle(new KeyframeTrack(times, new double[] {0, 0.35, 0.35, 0},
                KeyframeTrack.Interpolation.LINEAR));
        nodes.put(nodeKey, tracks);
        return new AnimationClip("waving", nodes);
    }

    private static AnimationClip bob(String name, String nodeKey,
                                     double[] times, double[] ys) {
        Map<String, AnimationClip.NodeTracks> nodes = new LinkedHashMap<>();
        AnimationClip.NodeTracks tracks = new AnimationClip.NodeTracks();
        tracks.translation(1, new KeyframeTrack(times, ys, KeyframeTrack.Interpolation.LINEAR));
        nodes.put(nodeKey, tracks);
        return new AnimationClip(name, nodes);
    }
}
