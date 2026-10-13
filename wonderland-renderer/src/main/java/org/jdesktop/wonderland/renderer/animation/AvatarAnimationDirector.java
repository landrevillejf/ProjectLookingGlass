/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M7c: the pure playback brain of the avatar animator.
 *
 * This is the subsystem the 2006 client had as jME-1.x's Animator: something
 * turns the wire's animationName string (carried since M6b on
 * AvatarTriggerState.lastAnimationName, delivered here by the renderer on
 * every trigger) into "which clip plays, how far through it are we, when do
 * we stop". Everything is driven by an EXPLICIT clock -- the renderer's tick
 * calls {@link #advance(double)} with the elapsed seconds -- so every policy
 * decision is assertable in a headless test without any wall clock at all.
 *
 * Policy, matching the documented 2006 behaviour:
 *   - null / blank / unknown names resolve to the idle clip when the library
 *     has one; a library with nothing playable resolves to nothing (the body
 *     simply keeps its static pose -- degrade, never throw);
 *   - the 2006 gesture vocabulary is aliased onto the clips that actually
 *     exist ("walk"->walking, "sit"/"sitting"/"onPhone"->idle ...): an exact
 *     library hit always wins before aliasing, so a body authored with a
 *     "running" clip gets it, not the walking stand-in;
 *   - gait/stance clips loop (time wraps at the clip boundary); gesture
 *     clips play ONCE and revert to idle when they finish. The LOOPING
 *     table below is the whole policy -- deliberately smaller and readable
 *     than the 2006 heuristics, and enough for every shipped clip name;
 *   - re-requesting the running gesture restarts it (clicking a peer mid-
 *     wave waves again), re-requesting the running loop does not reset it
 *     (walking keeps its stride phase).
 */
package org.jdesktop.wonderland.renderer.animation;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Decides the active clip and its time from name requests plus a clock. */
public final class AvatarAnimationDirector {

    /** Clip names that loop instead of playing once. */
    private static final Set<String> LOOPING = Set.of(
            "idle", "walking", "running", "swimming", "flying", "dancing");

    /** The 2006 wire vocabulary aliased onto canonical clip names. */
    private static final Map<String, String> ALIASES = buildAliases();

    private final AnimationLibrary library;

    private String activeName;   // lower-cased library key, or null = nothing playing
    private AnimationClip activeClip;
    private double time;

    public AvatarAnimationDirector(AnimationLibrary library) {
        this.library = library == null ? AnimationLibrary.of(Map.of()) : library;
        // the 2006 avatars were never frozen: an idle clip starts rolling
        // the moment a body loads (documented deviation: the M6b static
        // pose only survives when the library has no idle clip at all)
        select("idle");
    }

    /** Resolve a fresh animationName from the trigger path. Null/blank = idle. */
    public void requestAnimation(String rawName) {
        select(rawName == null || rawName.isBlank() ? "idle" : rawName);
    }

    /**
     * Move the clock by {@code dtSeconds} (negative or NaN input is ignored,
     * the renderer's ticker is allowed to be sloppy about scheduling gaps).
     * A finished one-shot gesture reverts to idle.
     */
    public void advance(double dtSeconds) {
        if (activeClip == null || !(dtSeconds > 0.0) || Double.isInfinite(dtSeconds)) {
            return;
        }
        double next = time + dtSeconds;
        double duration = activeClip.duration();
        if (LOOPING.contains(activeName)) {
            time = duration <= 0.0 ? 0.0 : next % duration;
            return;
        }
        if (next >= duration) {
            select("idle"); // the gesture ended; back to the stance clip
            return;
        }
        time = next;
    }

    /** The clip whose overrides the renderer should write this tick, or null. */
    public AnimationClip activeClip() {
        return activeClip;
    }

    /** The current time within the active clip (its loop or play head). */
    public double time() {
        return time;
    }

    /** True when the director is producing pose overrides at all. */
    public boolean isPlaying() {
        return activeClip != null;
    }

    /** The lower-cased name now playing, or null. Exposed for the probe/tests. */
    public String activeName() {
        return activeName;
    }

    /** The pure name resolution: exact hit, then alias, then idle, then nothing. */
    public String resolveClipName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return library.clip("idle") != null ? "idle" : null;
        }
        String lower = rawName.trim().toLowerCase(Locale.ROOT);
        if (library.clip(lower) != null) {
            return lower;
        }
        String aliased = ALIASES.get(lower);
        if (aliased != null && library.clip(aliased) != null) {
            return aliased;
        }
        return library.clip("idle") != null ? "idle" : null;
    }

    private void select(String rawName) {
        String resolved = resolveClipName(rawName);
        if (resolved == null) {
            activeName = null;
            activeClip = null;
            time = 0.0;
            return;
        }
        if (resolved.equals(activeName)) {
            // re-requesting a gesture restarts it; a loop keeps its phase
            if (!LOOPING.contains(resolved)) {
                time = 0.0;
            }
            return;
        }
        activeName = resolved;
        activeClip = library.clip(resolved);
        time = 0.0;
    }

    private static Map<String, String> buildAliases() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String s : new String[] {"walk", "walking", "run", "running"}) {
            m.put(s, "walking");
        }
        for (String s : new String[] {"sit", "sitting", "onphone", "phone",
                "stand", "standing", "idle"}) {
            m.put(s, "idle");
        }
        for (String s : new String[] {"wave", "waving"}) {
            m.put(s, "waving");
        }
        for (String s : new String[] {"swim", "swimming"}) {
            m.put(s, "swimming");
        }
        for (String s : new String[] {"fly", "flying"}) {
            m.put(s, "flying");
        }
        for (String s : new String[] {"dance", "dancing"}) {
            m.put(s, "dancing");
        }
        // talking / laughing / clapping: no canonical clip in this fork's
        // placeholder vocabulary -- an exact library hit (a rigged body that
        // ships "talking") resolves before aliases anyway; without one the
        // request falls back to idle, the same as the 2006 client with a
        // missing .jme animation file
        return Map.copyOf(m);
    }
}
