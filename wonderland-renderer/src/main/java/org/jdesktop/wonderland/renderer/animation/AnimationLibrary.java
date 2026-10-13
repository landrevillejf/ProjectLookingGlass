/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M7c: the named clip set one avatar body plays from.
 *
 * The 2006 counterpart was the jME-1.x Animator's clip collection inside the
 * .jme binary. Here the collection is built per loaded document: parsed
 * COLLADA clips win, and a document with no <library_animations> (the two
 * un-rigged placeholder bodies, any future static prop) falls back to the
 * SyntheticClips trio so the avatar still breathes, walks and waves exactly
 * like the 2006 client rendered every peer whether the artist rigged it or
 * not. Lookup is name-insensitive (lower-cased) because the wire string
 * comes from arbitrary client code and the director normalises too.
 */
package org.jdesktop.wonderland.renderer.animation;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jdesktop.wonderland.modules.collada.ColladaDocument;

/** A read-only clip catalog: name -> clip, with the synthetic fallback rule. */
public final class AnimationLibrary {

    private final Map<String, AnimationClip> clips;

    private AnimationLibrary(Map<String, AnimationClip> clips) {
        Map<String, AnimationClip> lower = new LinkedHashMap<>();
        for (Map.Entry<String, AnimationClip> e : clips.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            lower.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        this.clips = Map.copyOf(lower);
    }

    /**
     * The body's catalog: every clip parsed from the document when it has
     * any; otherwise the synthetic trio keyed to {@code fallbackNodeKey}
     * (the body's root node id/name, where a whole-body override must land).
     */
    public static AnimationLibrary forDocument(ColladaDocument document, String fallbackNodeKey) {
        Map<String, AnimationClip> parsed = ColladaClips.fromDocument(document);
        return new AnimationLibrary(parsed.isEmpty()
                ? SyntheticClips.defaults(fallbackNodeKey) : parsed);
    }

    /** A catalog from an explicit clip map (tests, and future programmatic clips). */
    public static AnimationLibrary of(Map<String, AnimationClip> clips) {
        return new AnimationLibrary(clips);
    }

    /** The lower-cased clip names, in insertion order. */
    public Set<String> names() {
        return clips.keySet();
    }

    /** Case-insensitive clip lookup, or null. */
    public AnimationClip clip(String name) {
        return name == null ? null : clips.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean isEmpty() {
        return clips.isEmpty();
    }

    /** True when the catalog is the synthetic fallback (no authored clips). */
    public boolean isSynthetic() {
        return clips.keySet().equals(Set.of(
                SyntheticClips.IDLE, SyntheticClips.WALKING, SyntheticClips.WAVING));
    }
}
