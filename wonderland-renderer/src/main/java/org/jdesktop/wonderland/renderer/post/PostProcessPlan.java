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
 *   OFFSCREEN_READBACK is the live-3D path: the scene rendered into an
 *   offscreen image, bloomed, and composited back per frame. The fork's
 *   Canvas3D can render offscreen, but a per-frame readback round trip has
 *   never been measured here, and the fork has no scene-to-texture path a
 *   shader could sample -- so this route is PROBE-GATED: it may not be
 *   enabled on an assumption, only on a WlProbe run that measured the frame
 *   cost on a real card.
 *
 *   SOFTWARE_CAPTURE is always available and is what BloomKernel serves
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
    }

    /** What the host knows before deciding. */
    public enum Capability {
        /** The user wants the glow at all. */
        BLOOM_WANTED,
        /** A probe run MEASURED the fork's readback round trip at a usable rate. */
        READBACK_PROBED_USABLE,
    }

    /** The decision, with the reason kept beside it -- plans get re-read. */
    public record Decision(Path path, String reason) {
    }

    private PostProcessPlan() {
    }

    /**
     * The whole rule, three branches deep: wanting bloom is necessary
     * but never sufficient for the live route -- READBACK_PROBED_USABLE is
     * evidence, not a driver string, and absent it the software route is
     * chosen and SAYS why. A decision that silently picked the slow path
     * would be re-litigated every release; this one carries its sentence.
     */
    public static Decision decide(Set<Capability> capabilities) {
        if (capabilities == null || !capabilities.contains(Capability.BLOOM_WANTED)) {
            return new Decision(Path.NONE, "no bloom requested");
        }
        if (capabilities.contains(Capability.READBACK_PROBED_USABLE)) {
            return new Decision(Path.OFFSCREEN_READBACK,
                    "a probe measured the offscreen round trip as usable");
        }
        return new Decision(Path.SOFTWARE_CAPTURE,
                "the live route stays probe-gated: no measured readback cost exists yet "
                        + "for this fork's per-frame offscreen round trip");
    }
}
