/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland roadmap P5.3: the pure eye-framing probe.
 *
 * The live `WorldViewer` probe is the project's answer to "how do we know a
 * scene-graph change worked when there is no headless way to render it" (see
 * lg3d-core/AGENTS.md, Verifying UI changes): a display is required, so a human
 * looks. That is the ceiling the roadmap keeps hitting on P2/P3/P4 -- every
 * visual phase ends in "verified by the eye probe," which is true but not CI.
 *
 * What CAN be decided without a display is *framing*: given where the eye is,
 * which way it looks and its field of view, is a cell's bounding sphere inside
 * the sight cone? That is a dot-product and an angle, no OpenGL, no Canvas3D.
 * This kernel makes that question a first-class, testable answer, so the probe
 * can assert "after the camera rig (P3.1) moves, exactly these cells are in
 * frame" headless -- and, through the Mandela harness in lg3d-apps, so a
 * behaviour script can make that assertion too.
 *
 * The test is a cone, not a full view frustum: a real aspect-ratio frustum would
 * be more exact, but framing only needs to know whether a target is *roughly*
 * before the eye within its field, and a cone keeps the whole thing a single
 * cosine comparison -- the shape a headless test can reason about and a probe can
 * drive from the eye transform the renderer already has.
 */
package org.jdesktop.wonderland.client.cull;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Decides, without a display, which of the world's cells an eye is looking at. */
public final class EyeProbe {

    /** A directed eye: a position, the way it looks, and a vertical field of view. */
    public record EyePose(float x, float y, float z,
            float dirX, float dirY, float dirZ, float fovDeg) {
    }

    /** One framable cell: its id, its world centre and a bounding radius. */
    public record Target(String id, float x, float y, float z, float radius) {
    }

    /**
     * The framing verdict for one look: every target marked in- or out-of-frame,
     * in the order it was offered, plus the eye it answers for. A probe reads
     * {@link #inFrame} to assert membership; a behaviour script binds it.
     */
    public record Framing(Map<String, Boolean> inFrame, EyePose eye) {

        public boolean isInFrame(String id) {
            return Boolean.TRUE.equals(inFrame.get(id));
        }

        public int visibleCount() {
            return (int) inFrame.values().stream().filter(Boolean::booleanValue).count();
        }

        public int total() {
            return inFrame.size();
        }
    }

    private EyeProbe() {
    }

    /**
     * Whether one sphere sits inside the eye's sight cone. A target is in frame
     * when the vector to its centre is within (half the field of view) plus the
     * angle its own radius subtends -- so a cell that merely straddles the edge
     * counts as visible (framing errs generous, dropping nothing that shows a
     * sliver). A target at the eye is trivially in frame; a zero-length look
     * direction frames nothing rather than throwing.
     */
    public static boolean inFrame(EyePose eye, Target t) {
        float toX = t.x() - eye.x();
        float toY = t.y() - eye.y();
        float toZ = t.z() - eye.z();
        float dist = (float) Math.sqrt(toX * toX + toY * toY + toZ * toZ);
        if (dist <= 1e-6f) {
            return true; // the eye is inside this cell
        }
        float dirLen = (float) Math.sqrt(eye.dirX() * eye.dirX()
                + eye.dirY() * eye.dirY() + eye.dirZ() * eye.dirZ());
        if (dirLen <= 1e-6f) {
            return false; // no look direction: frames nothing
        }
        float dot = (toX * eye.dirX() + toY * eye.dirY() + toZ * eye.dirZ()) / (dirLen * dist);
        dot = Math.max(-1f, Math.min(1f, dot));
        double angleToCentre = Math.acos(dot);
        // Half the field, plus the target's own angular radius (capped at a
        // hemisphere so a cell wrapped around the eye never produces NaN).
        double halfFov = Math.toRadians(eye.fovDeg()) / 2.0;
        double subtended = t.radius() <= 0 ? 0
                : Math.min(Math.PI / 2.0, Math.asin(Math.min(1.0, t.radius() / dist)));
        return angleToCentre <= halfFov + subtended;
    }

    /** Frame a whole collection against one eye, preserving offer order. */
    public static Framing frame(EyePose eye, Collection<Target> world) {
        Map<String, Boolean> verdict = new LinkedHashMap<>();
        for (Target t : world) {
            verdict.put(t.id(), inFrame(eye, t));
        }
        return new Framing(verdict, eye);
    }
}
