/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7c (colladaloader): a COLLADA 1.4.1 <library_animations>
 * entry, kept as raw parsed data.
 *
 * The 2006 asset pipeline had no animation loader at all -- avatars were
 * jME-1.x .jme binaries carrying their own Animator clips, unrecoverable here
 * (see docs/wonderland-migration.md, M7 row). The modern replacement is the
 * COLLADA animation vocabulary: <source> float arrays, <sampler> bindings and
 * <channel> targets. This class is the PARSE HALF of that vocabulary: exactly
 * what the DOM holds, with no interpretation. Deciding what a channel means
 * (which node, which array, how to interpolate) is the renderer's job --
 * wonderland-renderer/animation turns these records into playable clips --
 * because this module must stay a data model, not a playback engine.
 *
 * A group <animation> (the Blender-style wrapper that owns no channels and
 * only nests per-joint <animation> children) is flattened at parse time: the
 * children merge into the parent's record, keyed by the parent's name. That
 * keeps every downstream lookup on ONE map keyed by clip name or id.
 */
package org.jdesktop.wonderland.modules.collada;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One {@code <animation>} of {@code <library_animations>}, sources and channels included. */
public final class ColladaAnimation {

    /** A {@code <source>} inside an animation: its float data and accessor stride. */
    public static final class FloatSource {
        private final String id;
        private final double[] data;
        private final int stride;

        public FloatSource(String id, double[] data, int stride) {
            this.id = id;
            this.data = data.clone();
            this.stride = Math.max(stride, 1);
        }

        public String id() {
            return id;
        }

        public double[] data() {
            return data.clone();
        }

        /** The accessor stride of the output components (1 when single-valued). */
        public int stride() {
            return stride;
        }
    }

    /** A {@code <sampler>} binding an INPUT time source to an OUTPUT value source. */
    public static final class Sampler {
        private final String id;
        private final String inputSourceId;
        private final String outputSourceId;
        private final String interpolation;
        private final long[] indexPairs;

        public Sampler(String id, String inputSourceId, String outputSourceId,
                       String interpolation, long[] indexPairs) {
            this.id = id;
            this.inputSourceId = inputSourceId;
            this.outputSourceId = outputSourceId;
            this.interpolation = interpolation;
            this.indexPairs = indexPairs.clone();
        }

        public String id() {
            return id;
        }

        /** The referenced time source id (hash stripped), or null when unbound. */
        public String inputSourceId() {
            return inputSourceId;
        }

        /** The referenced value source id (hash stripped), or null when unbound. */
        public String outputSourceId() {
            return outputSourceId;
        }

        /** The declared interpolation (LINEAR / BEZIER / NONE), or null for the default. */
        public String interpolation() {
            return interpolation;
        }

        /** The flat (inputIndex, outputIndex) pairs from the sampler's index array. */
        public long[] indexPairs() {
            return indexPairs.clone();
        }
    }

    /** A {@code <channel>} tying one sampler to one animated element target. */
    public static final class Channel {
        private final String samplerId;
        private final String target;

        public Channel(String samplerId, String target) {
            this.samplerId = samplerId;
            this.target = target;
        }

        /** The referenced sampler id (hash stripped), or null when unbound. */
        public String samplerId() {
            return samplerId;
        }

        /** The raw {@code node/element[.ARRAY]} target string; interpreted by the renderer. */
        public String target() {
            return target;
        }
    }

    private final String id;
    private final String name;
    private final Map<String, FloatSource> sources;
    private final Map<String, Sampler> samplers;
    private final List<Channel> channels;

    public ColladaAnimation(String id, String name,
                            Map<String, FloatSource> sources,
                            Map<String, Sampler> samplers,
                            List<Channel> channels) {
        this.id = id;
        this.name = name;
        this.sources = new LinkedHashMap<>(sources);
        this.samplers = new LinkedHashMap<>(samplers);
        this.channels = List.copyOf(channels);
    }

    public String id() {
        return id;
    }

    /** The {@code name} attribute -- the clip name the renderer keys playback on. */
    public String name() {
        return name;
    }

    public FloatSource source(String id) {
        return sources.get(id);
    }

    public Sampler sampler(String id) {
        return samplers.get(id);
    }

    public List<Channel> channels() {
        return channels;
    }

    /** True when this record carries nothing a clip could be built from. */
    public boolean isEmpty() {
        return channels.isEmpty();
    }

    /**
     * Flatten a group animation: absorb every child record's sources, samplers
     * and channels into a new record keyed by this id / name. Used by the
     * parser for the wrapper-<animation> export style.
     */
    public ColladaAnimation mergedWith(List<ColladaAnimation> children) {
        Map<String, FloatSource> mergedSources = new LinkedHashMap<>(sources);
        Map<String, Sampler> mergedSamplers = new LinkedHashMap<>(samplers);
        List<Channel> mergedChannels = new ArrayList<>(channels);
        for (ColladaAnimation child : children) {
            child.sources.forEach(mergedSources::putIfAbsent);
            child.samplers.forEach(mergedSamplers::putIfAbsent);
            mergedChannels.addAll(child.channels);
        }
        return new ColladaAnimation(id, name, mergedSources, mergedSamplers, mergedChannels);
    }
}
