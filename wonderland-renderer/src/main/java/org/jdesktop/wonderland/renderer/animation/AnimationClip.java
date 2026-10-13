/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M7c: one named, playable keyframe clip over a set of
 * scene nodes.
 *
 * The 2006 counterpart was a jME-1.x Animator clip baked into the .jme
 * binary (unrecoverable -- see docs/wonderland-migration.md M7 row); this is
 * the COLLADA-side rebuild: a clip owns, per animated node, the six
 * side-effect channels a 1.4.1 exporter emits (translation X/Y/Z, rotation
 * as an axis+angle bundle, scale X/Y/Z) and composes them into the node's
 * LOCAL matrix at a given time. The renderer then multiplies through the
 * static parent chain -- so a rigged body moves its hierarchy exactly the
 * way the flat M6b world-matrix scene could not.
 *
 * The composition math is hand-written Rodrigues (axis-angle -> 3x3) rather
 * than a Jogamp Transform3D: this class must stay constructible in a
 * headless JVM so every arithmetic path is CI-asserted, the standing
 * pure-vs-probe split of this fork. Composition order is T * R * S, the
 * COLLADA side-effect convention (translate, then rotate, then scale in the
 * node's frame).
 */
package org.jdesktop.wonderland.renderer.animation;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jogamp.vecmath.Matrix4d;

/** A named clip: per-node channel tracks plus the local-matrix sampling. */
public final class AnimationClip {

    /** The channel tracks one node contributes to a clip. */
    public static final class NodeTracks {
        private final KeyframeTrack[] translation = new KeyframeTrack[3];
        private final KeyframeTrack[] rotationAxis = new KeyframeTrack[3];
        private final KeyframeTrack[] scale = new KeyframeTrack[3];
        private KeyframeTrack rotationAngle;
        private double end;

        public void translation(int component, KeyframeTrack track) {
            translation[component] = track;
            note(track);
        }

        public void rotationAxis(int component, KeyframeTrack track) {
            rotationAxis[component] = track;
            note(track);
        }

        public void rotationAngle(KeyframeTrack track) {
            rotationAngle = track;
            note(track);
        }

        public void scale(int component, KeyframeTrack track) {
            scale[component] = track;
            note(track);
        }

        private void note(KeyframeTrack track) {
            if (track != null) {
                end = Math.max(end, track.endTime());
            }
        }

        /** The last key time across every track of this node. */
        public double endTime() {
            return end;
        }

        /** True when no channel carries anything: the node needs no override. */
        public boolean isEmpty() {
            return allNull(translation) && allNull(rotationAxis)
                    && allNull(scale) && rotationAngle == null;
        }

        /**
         * The node's animated LOCAL matrix at time t. Missing channels take
         * their neutral value (translation 0, scale 1, no rotation); an axis
         * that samples to zero length rotates nothing, whatever the angle.
         */
        public Matrix4d sampleMatrix(double t) {
            double tx = sample(translation[0], t);
            double ty = sample(translation[1], t);
            double tz = sample(translation[2], t);
            double sx = rotationAxis[0] == null && rotationAngle != null ? 0.0
                    : sample(rotationAxis[0], t);
            double sy = rotationAxis[1] == null && rotationAngle != null ? 0.0
                    : sample(rotationAxis[1], t);
            double sz = rotationAxis[2] == null && rotationAngle != null ? 1.0
                    : sample(rotationAxis[2], t);
            double angle = rotationAngle == null ? 0.0 : rotationAngle.sampleAt(t);
            double scx = scale[0] == null ? 1.0 : scale[0].sampleAt(t);
            double scy = scale[1] == null ? 1.0 : scale[1].sampleAt(t);
            double scz = scale[2] == null ? 1.0 : scale[2].sampleAt(t);

            Matrix4d m = new Matrix4d();
            m.setIdentity();
            setAxisAngleRotation(m, sx, sy, sz, angle);
            if (scx != 1.0 || scy != 1.0 || scz != 1.0) {
                Matrix4d s = new Matrix4d();
                s.setIdentity();
                s.m00 = scx;
                s.m11 = scy;
                s.m22 = scz;
                m.mul(m, s);
            }
            m.m03 = tx;
            m.m13 = ty;
            m.m23 = tz;
            return m;
        }

        private static double sample(KeyframeTrack track, double t) {
            return track == null ? 0.0 : track.sampleAt(t);
        }

        private static boolean allNull(Object[] a) {
            for (Object o : a) {
                if (o != null) {
                    return false;
                }
            }
            return true;
        }

        /** Rodrigues: row-major axis-angle into the upper-left 3x3 of {@code m}. */
        private static void setAxisAngleRotation(Matrix4d m, double ax, double ay,
                                                 double az, double angle) {
            double len = Math.sqrt(ax * ax + ay * ay + az * az);
            if (len < 1e-9 || angle == 0.0) {
                return; // degenerate axis or zero angle: identity rotation
            }
            double x = ax / len;
            double y = ay / len;
            double z = az / len;
            double c = Math.cos(angle);
            double s = Math.sin(angle);
            double t2 = 1.0 - c;
            m.m00 = t2 * x * x + c;
            m.m01 = t2 * x * y - s * z;
            m.m02 = t2 * x * z + s * y;
            m.m10 = t2 * x * y + s * z;
            m.m11 = t2 * y * y + c;
            m.m12 = t2 * y * z - s * x;
            m.m20 = t2 * x * z - s * y;
            m.m21 = t2 * y * z + s * x;
            m.m22 = t2 * z * z + c;
        }
    }

    private final String name;
    private final double duration;
    private final Map<String, NodeTracks> nodes;

    public AnimationClip(String name, Map<String, NodeTracks> nodes) {
        this.name = name;
        Map<String, NodeTracks> kept = new LinkedHashMap<>();
        double end = 0.0;
        for (Map.Entry<String, NodeTracks> e : nodes.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            kept.put(e.getKey(), e.getValue());
            end = Math.max(end, e.getValue().endTime());
        }
        this.nodes = Map.copyOf(kept);
        this.duration = Math.max(end, 0.0);
    }

    public String name() {
        return name;
    }

    /** The last key time across every track; the loop boundary the director uses. */
    public double duration() {
        return duration;
    }

    /** The animated node keys (sid/id), the ones sampleAt can return entries for. */
    public java.util.Set<String> animatedNodes() {
        return nodes.keySet();
    }

    /**
     * Local-matrix overrides for every animated node at time t. The renderer
     * writes these over the nodes' static local transforms; nodes the clip
     * does not mention keep their authored matrix.
     */
    public Map<String, Matrix4d> sampleAt(double t) {
        Map<String, Matrix4d> out = new LinkedHashMap<>();
        for (Map.Entry<String, NodeTracks> e : nodes.entrySet()) {
            out.put(e.getKey(), e.getValue().sampleMatrix(t));
        }
        return out;
    }
}
