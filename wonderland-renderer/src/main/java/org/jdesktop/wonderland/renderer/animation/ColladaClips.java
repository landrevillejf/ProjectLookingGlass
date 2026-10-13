/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M7c: the raw-to-playable clip conversion.
 *
 * Turns the parse-half records (wonderland-modules ColladaAnimation: sources,
 * samplers, channels -- DOM data with no interpretation) into AnimationClips.
 * All the vocabulary DECISIONS live here, headless and asserted:
 *
 *   - component slots: a translation/scale channel binds its array token to a
 *     component of the output accessor (X->0, Y->1, Z->2); a rotation channel
 *     on a STRIDE-4 bundle is the COLLADA "rotation" side-effect element --
 *     axis(3)+angle, so X/Y/Z fill the axis and ANGLE the angle. A stride-3
 *     rotation bundle would be Euler angles, which this animator does not
 *     compose: the channel is dropped, not misread. A stride-1 rotation with
 *     an ANGLE token (or no token -- the "rotation" sid alone) is the
 *     angle-only channel with the authored default axis.
 *   - index binding: the sampler's (inputIndex, outputIndex) pairs pick the
 *     time from the INPUT source and the accessor unit from the OUTPUT
 *     source; an empty index list (rare exporter) means "aligned by unit".
 *   - interpolation: LINEAR ships; NONE becomes STEP; BEZIER collapses to
 *     LINEAR because the tangent handles are not parsed (a wrong curve is
 *     worse than a straight one). Unknown interpolation is the default.
 *   - timing normalisation: every track of a clip shifts so the earliest key
 *     sits at t=0, the director's loop boundary then equals clip duration.
 *   - merge behaviour: channels with the same (node, slot) concatenate their
 *     keys and re-sort -- the group-animation flatten can legitimately emit
 *     two records for one joint.
 *   - degrade, never throw: an unresolvable sampler/source/target is a
 *     dropped channel; a clip whose every channel dropped is skipped whole.
 */
package org.jdesktop.wonderland.renderer.animation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jdesktop.wonderland.modules.collada.ColladaAnimation;
import org.jdesktop.wonderland.modules.collada.ColladaDocument;

/** Builds playable {@link AnimationClip}s from a parsed COLLADA document. */
public final class ColladaClips {

    /** One collected (time, value) key pending track assembly. */
    private record Key(double time, double value) { }

    /** nodeSid -> slot key ("T0", "ANG", "S2", "A1"...) -> collected keys. */
    private static final String TRANSLATION = "T";
    private static final String SCALE = "S";
    private static final String ROT_AXIS = "A";
    private static final String ROT_ANGLE = "ANG";

    private ColladaClips() {
    }

    /**
     * All clips of the document keyed by clip name (the {@code name}
     * attribute, falling back to the animation id). Insertion order matches
     * the library's parse order.
     */
    public static Map<String, AnimationClip> fromDocument(ColladaDocument document) {
        Map<String, AnimationClip> out = new LinkedHashMap<>();
        if (document == null) {
            return out;
        }
        for (ColladaAnimation anim : document.animations().values()) {
            AnimationClip clip = fromAnimation(anim);
            if (clip != null) {
                out.put(clip.name(), clip);
            }
        }
        return out;
    }

    /** One record -> one clip, or null when nothing in it is playable. */
    public static AnimationClip fromAnimation(ColladaAnimation anim) {
        if (anim == null || anim.isEmpty()) {
            return null;
        }
        String clipName = anim.name() != null ? anim.name() : anim.id();
        Map<String, Map<String, List<Key>>> byNode = new LinkedHashMap<>();

        for (ColladaAnimation.Channel channel : anim.channels()) {
            ColladaAnimation.Sampler sampler = anim.sampler(channel.samplerId());
            if (sampler == null) {
                continue; // unbound channel: dropped, the standing degrade rule
            }
            ColladaAnimation.FloatSource input = anim.source(sampler.inputSourceId());
            ColladaAnimation.FloatSource output = anim.source(sampler.outputSourceId());
            if (input == null || output == null) {
                continue;
            }
            ChannelTarget target = ChannelTarget.parse(channel.target());
            String slot = slotOf(target, output.stride());
            if (slot == null) {
                continue; // unsupported element or an array that cannot live here
            }
            List<Key> keys = collectKeys(input, output, sampler, target, slot);
            if (keys.isEmpty()) {
                continue;
            }
            byNode.computeIfAbsent(target.nodeSid(), n -> new LinkedHashMap<>())
                    .computeIfAbsent(slot, s -> new ArrayList<>())
                    .addAll(keys);
        }

        Map<String, AnimationClip.NodeTracks> nodes = new LinkedHashMap<>();
        double earliest = Double.MAX_VALUE;
        boolean any = false;
        for (Map<String, List<Key>> slots : byNode.values()) {
            for (List<Key> keys : slots.values()) {
                for (Key k : keys) {
                    earliest = Math.min(earliest, k.time());
                    any = true;
                }
            }
        }
        if (!any) {
            return null;
        }
        double shift = earliest == 0.0 ? 0.0 : earliest;
        for (Map.Entry<String, Map<String, List<Key>>> nodeEntry : byNode.entrySet()) {
            AnimationClip.NodeTracks tracks = new AnimationClip.NodeTracks();
            for (Map.Entry<String, List<Key>> slotEntry : nodeEntry.getValue().entrySet()) {
                KeyframeTrack track =
                        buildTrack(slotEntry.getValue(), interpolationOf(anim, slotEntry.getKey()), shift);
                if (track.keyCount() == 0) {
                    continue;
                }
                apply(tracks, slotEntry.getKey(), track);
            }
            if (!tracks.isEmpty()) {
                nodes.put(nodeEntry.getKey(), tracks);
            }
        }
        if (nodes.isEmpty()) {
            return null;
        }
        return new AnimationClip(clipName, nodes);
    }

    /**
     * The slot a (target, output stride) pair binds to, or null to drop the
     * channel. See the class header for the vocabulary rules.
     */
    private static String slotOf(ChannelTarget target, int stride) {
        if (!target.supported()) {
            return null;
        }
        String array = target.array();
        switch (target.kind()) {
            case TRANSLATION:
            case SCALE:
                int comp = xyzIndex(array);
                if (comp < 0 || (stride > 1 && comp >= stride)) {
                    return null;
                }
                return (target.kind() == ChannelTarget.Kind.TRANSLATION ? TRANSLATION : SCALE) + comp;
            case ROTATION:
                if (stride == 4) { // axis+angle bundle, the COLLADA rotation element
                    if ("ANGLE".equals(array)) {
                        return ROT_ANGLE;
                    }
                    int axis = xyzIndex(array);
                    return axis < 0 ? null : ROT_AXIS + axis;
                }
                if (stride == 1 && (array == null || "ANGLE".equals(array))) {
                    return ROT_ANGLE; // angle-only channel
                }
                return null; // stride-3 euler or anything else: not composed, not misread
            default:
                return null;
        }
    }

    private static int xyzIndex(String array) {
        if ("X".equals(array)) {
            return 0;
        }
        if ("Y".equals(array)) {
            return 1;
        }
        if ("Z".equals(array)) {
            return 2;
        }
        return -1;
    }

    private static List<Key> collectKeys(ColladaAnimation.FloatSource input,
                                         ColladaAnimation.FloatSource output,
                                         ColladaAnimation.Sampler sampler,
                                         ChannelTarget target,
                                         String slot) {
        List<Key> keys = new ArrayList<>();
        double[] times = input.data();
        double[] values = output.data();
        int stride = output.stride();
        int component = componentWithinBundle(slot, target, stride);
        long[] pairs = sampler.indexPairs();
        if (pairs.length >= 2) {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                long timeIndex = pairs[i];
                long unitIndex = pairs[i + 1];
                if (timeIndex < 0 || timeIndex >= times.length) {
                    continue;
                }
                int valueAt = (int) (unitIndex * stride + component);
                if (unitIndex < 0 || valueAt >= values.length) {
                    continue;
                }
                keys.add(new Key(times[(int) timeIndex], values[valueAt]));
            }
        } else {
            int units = Math.min(times.length, values.length / stride);
            for (int i = 0; i < units; i++) {
                keys.add(new Key(times[i], values[i * stride + component]));
            }
        }
        keys.sort((a, b) -> Double.compare(a.time(), b.time()));
        return keys;
    }

    /**
     * The float offset inside one output accessor unit. Bundles put the
     * token's component into the stride (X/Y/Z=0/1/2, axis ANGLE=3); a
     * single-value source (stride 1) carries only its own value at 0.
     */
    private static int componentWithinBundle(String slot, ChannelTarget target, int stride) {
        if (stride <= 1) {
            return 0;
        }
        if (ROT_ANGLE.equals(slot)) {
            return 3; // tested before the axis prefix: "ANG" starts with "A"
        }
        if (slot.startsWith(ROT_AXIS)) {
            return Integer.parseInt(slot.substring(ROT_AXIS.length()));
        }
        return Integer.parseInt(slot.substring(1));
    }

    private static KeyframeTrack.Interpolation interpolationOf(ColladaAnimation anim, String slot) {
        // every slot of one record shares the record's channels; resolve from
        // the first channel that bound to it (samplers are read linearly)
        for (ColladaAnimation.Channel channel : anim.channels()) {
            ColladaAnimation.Sampler sampler = anim.sampler(channel.samplerId());
            if (sampler == null) {
                continue;
            }
            ColladaAnimation.FloatSource output = anim.source(sampler.outputSourceId());
            if (output == null) {
                continue;
            }
            ChannelTarget target = ChannelTarget.parse(channel.target());
            if (slot.equals(slotOf(target, output.stride()))) {
                String declared = sampler.interpolation();
                if ("NONE".equalsIgnoreCase(declared)) {
                    return KeyframeTrack.Interpolation.STEP;
                }
                return KeyframeTrack.Interpolation.LINEAR; // BEZIER collapses; see header
            }
        }
        return KeyframeTrack.Interpolation.LINEAR;
    }

    private static KeyframeTrack buildTrack(List<Key> keys, KeyframeTrack.Interpolation interp,
                                            double timeShift) {
        double[] times = new double[keys.size()];
        double[] values = new double[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            times[i] = keys.get(i).time() - timeShift;
            values[i] = keys.get(i).value();
        }
        return new KeyframeTrack(times, values, interp);
    }

    private static void apply(AnimationClip.NodeTracks tracks, String slot, KeyframeTrack track) {
        if (ROT_ANGLE.equals(slot)) {
            tracks.rotationAngle(track);
        } else if (slot.startsWith(ROT_AXIS)) {
            tracks.rotationAxis(Integer.parseInt(slot.substring(ROT_AXIS.length())), track);
        } else if (slot.startsWith(TRANSLATION)) {
            tracks.translation(Integer.parseInt(slot.substring(TRANSLATION.length())), track);
        } else if (slot.startsWith(SCALE)) {
            tracks.scale(Integer.parseInt(slot.substring(SCALE.length())), track);
        }
    }
}
