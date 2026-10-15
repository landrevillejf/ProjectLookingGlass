/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * Wonderland roadmap P5.3: a Mandela-driven probe over the eye's framing verdict.
 *
 * The roadmap's plan is that the probe harness makes "every later phase
 * verifiable without claiming untested success." {@link EyeProbe} (in
 * wonderland-client) already turns a look into pure data -- which cells are in
 * frame. This class is the scripting half: it hands that verdict to a Mandela
 * probe script as plain bound values and reads back the script's report, exactly
 * the shape {@link WorldBehavior} uses for cell annotations, so a person can
 * write "assert the clinic reception is in frame after the rig turns" as a
 * script the build can run headless -- no hand, no display.
 *
 * It lives here, not in wonderland-client, because Mandela must not be imported
 * into the client (whose dependency rule forbids it) and this is the one module
 * already on both the language and the renderer. Like WorldBehavior, nothing on
 * this path may throw onto a caller: a null script, a throwing script, a script
 * that reads an unbound name or leaves an empty value all collapse to the
 * fallback, because a broken probe is a missing report, never a crash.
 */
package org.jdesktop.lg3d.apps.wonderland;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jdesktop.lg3d.mandela.api.Mandela;
import org.jdesktop.wonderland.client.cull.EyeProbe;
import org.jdesktop.wonderland.client.cull.EyeProbe.EyePose;
import org.jdesktop.wonderland.client.cull.EyeProbe.Framing;
import org.jdesktop.wonderland.client.cull.EyeProbe.Target;

/** Runs a probe script over an eye's framing verdict. */
final class ProbeHarness {

    private ProbeHarness() {
    }

    /**
     * The framing facts a probe script may read, as the plain values
     * {@link Mandela#eval(String, Map)} binds: the ids in and out of frame as
     * comma-separated strings (so a script can test membership with a string
     * search), their counts, and the eye's field of view. No scene-graph handle
     * crosses this boundary -- a script reasons about framing, it does not touch
     * the renderer.
     */
    static Map<String, Object> bindingsOf(Framing framing) {
        Map<String, Object> bound = new LinkedHashMap<>();
        StringBuilder visible = new StringBuilder();
        StringBuilder hidden = new StringBuilder();
        framing.inFrame().forEach((id, isIn) -> {
            StringBuilder into = Boolean.TRUE.equals(isIn) ? visible : hidden;
            if (into.length() > 0) {
                into.append(',');
            }
            into.append(id);
        });
        bound.put("inFrame", visible.toString());
        bound.put("offFrame", hidden.toString());
        bound.put("visibleCount", (double) framing.visibleCount());
        bound.put("total", (double) framing.total());
        bound.put("fov", (double) framing.eye().fovDeg());
        bound.put("eyeX", (double) framing.eye().x());
        bound.put("eyeY", (double) framing.eye().y());
        bound.put("eyeZ", (double) framing.eye().z());
        return bound;
    }

    /**
     * Frame {@code world} against {@code eye} and let {@code script} report on
     * it, returning the script's value or {@code fallback} when the script is
     * absent, blank, throws, or leaves nothing. The report is the phase's
     * evidence: a probe asserts a cell is (or is not) in frame, and the result is
     * a string a CI test compares -- the headless stand-in for the live eye.
     */
    static String verdict(EyePose eye, Collection<Target> world, String script, String fallback) {
        if (script == null || script.isBlank()) {
            return fallback;
        }
        Framing framing = EyeProbe.frame(eye, world);
        Object result;
        try {
            result = Mandela.eval(script, bindingsOf(framing));
        } catch (Throwable probeFailed) {
            return fallback;
        }
        if (result == null) {
            return fallback;
        }
        String text = String.valueOf(result).strip();
        return text.isEmpty() ? fallback : text;
    }
}
