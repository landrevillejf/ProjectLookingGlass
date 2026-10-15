/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
package org.jdesktop.lg3d.apps.wonderland;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.jdesktop.wonderland.client.cull.EyeProbe.EyePose;
import org.jdesktop.wonderland.client.cull.EyeProbe.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wonderland P5.3: the probe a person can write in Mandela, run here with no
 * display. The framing verdict is decided by {@code EyeProbe}; what is asserted
 * below is that the harness exposes it to a script and reports the script's
 * answer -- and, exactly as the behaviour bridge does, that a broken or absent
 * probe yields the fallback rather than an exception. This is what makes a later
 * camera-rig or renderer change assertable in CI instead of by a hand.
 */
class ProbeHarnessTest {

    private static final EyePose EYE = new EyePose(0, 0, 0, 0, 0, 1, 90f);

    private static List<Target> world() {
        return List.of(
                new Target("ahead", 0, 0, 10, 1),    // in frame
                new Target("behind", 0, 0, -10, 1),  // out
                new Target("angle", 5, 0, 8.66f, 0)); // in (30 deg off, 45 half-field)
    }

    @Test
    @DisplayName("a probe script reads the in-frame ids it is handed")
    void scriptReadsMembership() {
        assertEquals("ahead,angle", ProbeHarness.verdict(EYE, world(), "inFrame", "NONE"));
    }

    @Test
    @DisplayName("a probe can branch on a bound numeric fact")
    void scriptBranchesOnTheEye() {
        // The last VALUE expression of the script is the verdict; a comparison
        // yields a boolean (rendered "true"), proving the bound fov is numeric
        // and usable -- the same operation the shipped behaviour scripts rely on.
        assertEquals("true", ProbeHarness.verdict(EYE, world(), "fov > 45", "NONE"));
    }

    @Test
    @DisplayName("counts are bound so a script can test them arithmetically")
    void scriptTestsCounts() {
        assertEquals("true", ProbeHarness.verdict(EYE, world(), "visibleCount > 1", "NONE"));
    }

    @Test
    @DisplayName("the off-frame set is bound too")
    void scriptReadsTheHiddenSet() {
        assertEquals("behind", ProbeHarness.verdict(EYE, world(), "offFrame", "NONE"));
    }

    @Test
    @DisplayName("an absent or blank probe changes nothing")
    void absentProbeIsFallback() {
        assertEquals("FALLBACK", ProbeHarness.verdict(EYE, world(), null, "FALLBACK"));
        assertEquals("FALLBACK", ProbeHarness.verdict(EYE, world(), "   ", "FALLBACK"));
    }

    @Test
    @DisplayName("a probe that throws, or reads an unbound name, falls back")
    void brokenProbeIsFallback() {
        assertEquals("FALLBACK", ProbeHarness.verdict(EYE, world(), "1 / 0", "FALLBACK"));
        assertEquals("FALLBACK", ProbeHarness.verdict(EYE, world(), "noSuchFact + 1", "FALLBACK"));
    }
}
