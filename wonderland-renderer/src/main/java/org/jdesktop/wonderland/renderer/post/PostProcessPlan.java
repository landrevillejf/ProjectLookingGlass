/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.2: what the platform ceiling decides, headless.
 *
 * Same shape as ShaderAppearancePlan for P2.2: the honest deliverable for a
 * GPU-bound phase is the DECISION about the GPU, stated where a human can
 * read it, plus the artefact that survives whatever the decision is. The
 * question here is one the roadmap already answers in prose -- where may a
 * bloom pass run? -- and this class makes it a checked answer instead of
 * folklore:
 *
 *   GPU_POSTPROCESS is the live-3D path the platform ceiling used to forbid and
 *   a two-file source patch lifted. The fork's Canvas3D now carries an
 *   overridable preSwap() that Java 3D calls in its SWAP handler between the
 *   glFinish and the buffer swap -- the one point where the just-rendered frame
 *   is complete and still resident on the GPU. A subclass blits that framebuffer
 *   into a texture, runs bright-pass -> separable Gaussian -> additive composite
 *   entirely in GL, and hands the context back untouched, so the glow is present
 *   in the very buffer that gets swapped: per frame, at native resolution, with
 *   ZERO CPU readback. WlProbe53 first proved the framebuffer-blit reachable and
 *   real-time; WlProbe55 MEASURED the whole chain on the reference card (GL 4.6
 *   Mesa radeonsi) at ~14 ms/frame (71 fps) at 1280x763 and, reading the
 *   composited buffer back once to check, found 7732 pixels lifted onto what was
 *   dark scene -- the visible halo, not a cost estimate. This route is chosen
 *   whenever GPU_POSTPROCESS_PROBED_USABLE is in evidence and is preferred over
 *   the readback route because it never pays the readback's cost. It requires the
 *   patched java3d-core (lg3d-core/src/patches/java3d-core-preswap); against a
 *   stock jar the hook is absent, the capability is not in evidence, and the plan
 *   falls through -- honest, never a silent claim.
 *
 *   OFFSCREEN_READBACK is the older live-3D path: the scene rendered into an
 *   offscreen image, bloomed in software, and composited back per frame. The
 *   fork's Canvas3D can render offscreen, and WlProbe52 MEASURED the per-frame
 *   readback round trip on the reference card: ~128 ms for a 640x363
 *   capture-convert-bloom and ~419 ms for a native 1920x1080 software bloom --
 *   correct, and it glowed a captured live frame (the halo around a hot quad
 *   lifted 69 -> 76), but far under a real-time rate. Now that the preSwap hook
 *   gives a shader a scene texture to sample, this is the fallback for a host
 *   without the patched jar: PROBE-GATED, enabled only where that measured cost
 *   is affordable -- a still, an on-demand snapshot or a reduced resolution --
 *   never per-frame at native res on an assumption.
 *
 *   SOFTWARE_CAPTURE is always available and is what BloomPass serves
 *   today: bloom the captured probe image, bloom the 2D desktop's
 *   composites. Slower per frame, but it is the truth the eye probe can
 *   inspect NOW, headless, without a driver in the room.
 *
 *   NONE is the user's own choice; a plan never overrides it.
 */
package org.jdesktop.wonderland.renderer.post;

import java.util.Set;

/** The P3.2 decision: which bloom route the platform and the evidence allow. */
public final class PostProcessPlan {

    public enum Path {
        /** None -- the shell asked for no post pass, and the plan obeys. */
        NONE,
        /** Bloom applied to captured/composited images in software. */
        SOFTWARE_CAPTURE,
        /** Bloom on the live 3D canvas via per-frame offscreen readback. */
        OFFSCREEN_READBACK,
        /**
         * Bloom on the live 3D canvas in GL at the patched Canvas3D.preSwap()
         * hook: the rendered scene blitted to a texture, bloomed on the GPU and
         * composited back into the buffer that gets swapped -- per frame, native
         * resolution, zero CPU readback. Chosen when
         * {@link Capability#GPU_POSTPROCESS_PROBED_USABLE} is in evidence.
         */
        GPU_POSTPROCESS,
    }

    /** What the host knows before deciding. */
    public enum Capability {
        /** The user wants the glow at all. */
        BLOOM_WANTED,
        /**
         * The user wants HDR tone mapping at all (P3.3). Like {@link #BLOOM_WANTED}
         * it is necessary but never sufficient: the tone map is a live GPU pass on
         * the same patched preSwap() hook, so it additionally needs
         * {@link #GPU_POSTPROCESS_PROBED_USABLE}.
         */
        HDR_WANTED,
        /**
         * The user wants image-space anti-aliasing at all (P3.4). Necessary but never
         * sufficient, exactly like {@link #HDR_WANTED}: FXAA is a live GPU pass on the
         * same patched preSwap() hook, so it additionally needs
         * {@link #GPU_POSTPROCESS_PROBED_USABLE}.
         */
        FXAA_WANTED,
        /** A probe run MEASURED the fork's readback round trip at a usable rate. */
        READBACK_PROBED_USABLE,
        /**
         * A probe run MEASURED the patched preSwap() GPU bloom as visible per
         * frame at a real-time rate with zero CPU readback (WlProbe53/WlProbe55).
         * Not in evidence against a stock java3d-core, which has no preSwap().
         */
        GPU_POSTPROCESS_PROBED_USABLE,
    }

    /** The decision, with the reason kept beside it -- plans get re-read. */
    public record Decision(Path path, String reason) {
    }

    private PostProcessPlan() {
    }

    /**
     * The whole rule, four branches deep: wanting bloom is necessary but never
     * sufficient for a live route -- each live route needs its OWN measured
     * evidence, not a driver string. The GPU preSwap route is checked first
     * because it is strictly the best live path (native res, zero readback,
     * real-time) whenever a probe proved it; the readback route is the fallback
     * for a host without the patched jar; absent both, the software route is
     * chosen and SAYS why. A decision that silently picked the slow path would be
     * re-litigated every release; this one carries its sentence.
     */
    public static Decision decide(Set<Capability> capabilities) {
        if (capabilities == null || !capabilities.contains(Capability.BLOOM_WANTED)) {
            return new Decision(Path.NONE, "no bloom requested");
        }
        if (capabilities.contains(Capability.GPU_POSTPROCESS_PROBED_USABLE)) {
            return new Decision(Path.GPU_POSTPROCESS,
                    "a probe measured the patched Canvas3D.preSwap GPU bloom as "
                            + "visible per frame at a real-time rate with zero CPU "
                            + "readback (WlProbe55: ~14 ms/frame, 71 fps at 1280x763, "
                            + "7732 halo pixels lifted onto dark scene)");
        }
        if (capabilities.contains(Capability.READBACK_PROBED_USABLE)) {
            return new Decision(Path.OFFSCREEN_READBACK,
                    "a probe measured the offscreen round trip as usable");
        }
        return new Decision(Path.SOFTWARE_CAPTURE,
                "the live route stays probe-gated: WlProbe52 measured this fork's "
                        + "per-frame offscreen round trip at ~128 ms for 640x363 and "
                        + "~419 ms for a native 1920x1080 software bloom -- it glows "
                        + "correctly but not at a real-time rate, so it is confined to "
                        + "stills and reduced resolution");
    }

    /**
     * The P3.3 HDR tone-mapping decision. Tone mapping has exactly one honest live
     * route: the same patched {@code Canvas3D.preSwap()} GPU pass the bloom rides,
     * sampling the just-rendered framebuffer into {@code HdrKernel}'s curve with zero
     * CPU readback. There is deliberately NO software fallback branch -- a per-frame
     * offscreen tone map pays the same readback WlProbe52 measured at ~419 ms native,
     * and unlike a bloom (which degrades to a still) a tone map applied only to
     * captures would leave the live canvas un-tonemapped and lying about its own
     * radiance. So this returns {@link Path#GPU_POSTPROCESS} only when HDR is wanted
     * AND the hook is probe-present, and {@link Path#NONE} otherwise -- never a
     * half-applied curve.
     */
    public static Decision decideHdr(Set<Capability> capabilities) {
        if (capabilities == null || !capabilities.contains(Capability.HDR_WANTED)) {
            return new Decision(Path.NONE, "no HDR tone mapping requested");
        }
        if (capabilities.contains(Capability.GPU_POSTPROCESS_PROBED_USABLE)) {
            return new Decision(Path.GPU_POSTPROCESS,
                    "a probe measured the patched Canvas3D.preSwap GPU pass as usable "
                            + "per frame with zero CPU readback (WlProbe55/WlProbe56), "
                            + "so the tone map rides the same hook the bloom does");
        }
        return new Decision(Path.NONE,
                "tone mapping is a live GPU pass only: the preSwap() hook is absent "
                        + "(stock java3d-core), and a software readback tone map "
                        + "applied just to captures would leave the live canvas "
                        + "un-tonemapped, so nothing is claimed");
    }

    /**
     * The P3.4 FXAA decision, shaped exactly like {@link #decideHdr} for the same
     * reason: anti-aliasing the presented frame is a live GPU pass on the patched
     * {@code Canvas3D.preSwap()} hook with zero CPU readback, and there is no honest
     * software fallback -- an anti-alias applied only to captures would leave the live
     * canvas jagged while claiming otherwise. Returns {@link Path#GPU_POSTPROCESS} only
     * when FXAA is wanted AND the hook is probe-present, {@link Path#NONE} otherwise.
     */
    public static Decision decideFxaa(Set<Capability> capabilities) {
        if (capabilities == null || !capabilities.contains(Capability.FXAA_WANTED)) {
            return new Decision(Path.NONE, "no anti-aliasing requested");
        }
        if (capabilities.contains(Capability.GPU_POSTPROCESS_PROBED_USABLE)) {
            return new Decision(Path.GPU_POSTPROCESS,
                    "a probe measured the patched Canvas3D.preSwap GPU pass as usable "
                            + "per frame with zero CPU readback (WlProbe55/WlProbe56), "
                            + "so the anti-alias rides the same hook the bloom does");
        }
        return new Decision(Path.NONE,
                "anti-aliasing is a live GPU pass only: the preSwap() hook is absent "
                        + "(stock java3d-core), and a software readback anti-alias "
                        + "applied just to captures would leave the live canvas jagged, "
                        + "so nothing is claimed");
    }
}
