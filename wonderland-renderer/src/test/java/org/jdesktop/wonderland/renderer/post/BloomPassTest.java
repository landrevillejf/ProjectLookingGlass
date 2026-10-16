/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.2: the software-capture pass, headless.
 *
 * BloomPass is the seam a capture calls, so what a test may assert is exactly
 * what a caller depends on: the NONE path is the pixel-perfect identity (a world
 * that never asked for bloom is unchanged by the pass being wired), a bloom path
 * really glows a hot spot's dark neighbour, the BufferedImage seam round-trips,
 * and the plan reason now carries the MEASURED cost WlProbe52 produced rather
 * than the old "never measured" shrug. What no test claims is a per-frame glow on
 * a live canvas -- that is the OFFSCREEN_READBACK route the measurement confines
 * to stills and reduced resolution.
 */
package org.jdesktop.wonderland.renderer.post;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Set;
import org.jdesktop.wonderland.renderer.post.BloomPass.Settings;
import org.jdesktop.wonderland.renderer.post.PostProcessPlan.Capability;
import org.jdesktop.wonderland.renderer.post.PostProcessPlan.Decision;
import org.jdesktop.wonderland.renderer.post.PostProcessPlan.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BloomPassTest {

    private static final int OPAQUE_BLACK = 0xFF000000;

    private static Decision none() {
        return new Decision(Path.NONE, "no bloom requested");
    }

    private static Decision software() {
        return new Decision(Path.SOFTWARE_CAPTURE, "software");
    }

    private static Decision readback() {
        return new Decision(Path.OFFSCREEN_READBACK, "readback");
    }

    /** A 15x15 frame, black except a hot white pixel at (7,7). */
    private static BloomKernel.Frame hotSpot() {
        int[] pixels = new int[15 * 15];
        Arrays.fill(pixels, OPAQUE_BLACK);
        pixels[7 * 15 + 7] = 0xFFFFFFFF;
        return new BloomKernel.Frame(15, 15, pixels);
    }

    private static int lum(BloomKernel.Frame f, int x, int y) {
        return BloomKernel.luminance(f.argb()[y * f.width() + x]);
    }

    @Test
    void theDefaultSettingsAreTheOnesTheProbeMeasuredAndProved() {
        Settings d = Settings.DEFAULT;
        assertThat(d.threshold()).isEqualTo(128f);
        assertThat(d.knee()).isEqualTo(32f);
        assertThat(d.radius()).isEqualTo(3);
        assertThat(d.passes()).isEqualTo(2);
        assertThat(d.strength()).isEqualTo(0.6f);
    }

    @Test
    void settingsRefuseEveryNegativeMagnitude() {
        // Each operand of the guard is a branch a caller could trip; each is
        // proved to throw, so no negative sneaks through one unchecked field.
        assertThatThrownBy(() -> new Settings(-1f, 0f, 0, 0, 0f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(0f, -1f, 0, 0, 0f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(0f, 0f, -1, 0, 0f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(0f, 0f, 0, -1, 0f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(0f, 0f, 0, 0, -1f))
                .isInstanceOf(IllegalArgumentException.class);
        // And the all-valid corner does not throw.
        assertThat(new Settings(0f, 0f, 0, 0, 0f)).isNotNull();
    }

    @Test
    void theNonePathIsTheExactIdentityNotACopy() {
        BloomKernel.Frame frame = hotSpot();

        BloomKernel.Frame out = BloomPass.apply(frame, none(), Settings.DEFAULT);

        assertThat(out).as("no glow requested = the very frame rendered, not a clone")
                .isSameAs(frame);
        assertThat(lum(out, 8, 7)).as("the dark neighbour stayed dark").isZero();
    }

    @Test
    void aNullDecisionIsTreatedAsNoGlowAndANullFrameIsNotInvented() {
        BloomKernel.Frame frame = hotSpot();

        assertThat(BloomPass.apply(frame, (Decision) null, Settings.DEFAULT)).isSameAs(frame);
        assertThat(BloomPass.apply((BloomKernel.Frame) null, software(), Settings.DEFAULT))
                .as("a capture that produced nothing is not this pass's error")
                .isNull();
    }

    @Test
    void aBloomPathGlowsTheHotSpotsDarkNeighbour() {
        BloomKernel.Frame frame = hotSpot();

        BloomKernel.Frame out = BloomPass.apply(frame, software(), Settings.DEFAULT);

        assertThat(lum(out, 8, 7)).as("light bled into the neighbour that was black")
                .isGreaterThan(0);
        assertThat(lum(out, 7, 7)).as("the hot spot itself is still the brightest")
                .isGreaterThan(lum(out, 8, 7));
    }

    @Test
    void theOffscreenReadbackPathBloomsExactlyLikeTheSoftwareOne() {
        BloomKernel.Frame frame = hotSpot();

        BloomKernel.Frame viaSoftware = BloomPass.apply(frame, software(), Settings.DEFAULT);
        BloomKernel.Frame viaReadback = BloomPass.apply(frame, readback(), Settings.DEFAULT);

        assertThat(viaReadback.argb())
                .as("both non-NONE paths run the same kernel at the same settings")
                .isEqualTo(viaSoftware.argb());
    }

    @Test
    void theCapabilityOverloadDecidesThenApplies() {
        BloomKernel.Frame frame = hotSpot();

        assertThat(BloomPass.apply(frame, Set.of(), Settings.DEFAULT))
                .as("no capabilities decide NONE, so the identity")
                .isSameAs(frame);

        BloomKernel.Frame wanted = BloomPass.apply(
                frame, Set.of(Capability.BLOOM_WANTED), Settings.DEFAULT);
        assertThat(lum(wanted, 8, 7)).as("BLOOM_WANTED alone decides SOFTWARE_CAPTURE")
                .isGreaterThan(0);

        BloomKernel.Frame probed = BloomPass.apply(frame,
                Set.of(Capability.BLOOM_WANTED, Capability.READBACK_PROBED_USABLE),
                Settings.DEFAULT);
        assertThat(probed.argb()).as("the probed capability still blooms the same pixels")
                .isEqualTo(wanted.argb());
    }

    @Test
    void nullSettingsFallBackToTheMeasuredDefault() {
        BloomKernel.Frame frame = hotSpot();

        BloomKernel.Frame explicit = BloomPass.apply(frame, software(), Settings.DEFAULT);
        BloomKernel.Frame fallback = BloomPass.apply(frame, software(), null);

        assertThat(fallback.argb()).isEqualTo(explicit.argb());
    }

    @Test
    void theBufferedImageSeamReturnsTheSameImageForNoneAndBloomsOtherwise() {
        BufferedImage image = new BufferedImage(15, 15, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 15; y++) {
            for (int x = 0; x < 15; x++) {
                image.setRGB(x, y, OPAQUE_BLACK);
            }
        }
        image.setRGB(7, 7, 0xFFFFFFFF);

        assertThat(BloomPass.apply(image, none(), Settings.DEFAULT))
                .as("NONE hands back the caller's own image, un-copied")
                .isSameAs(image);
        assertThat(BloomPass.apply((BufferedImage) null, software(), Settings.DEFAULT)).isNull();

        BufferedImage bloomed = BloomPass.apply(image, software(), Settings.DEFAULT);
        assertThat(bloomed).isNotSameAs(image);
        assertThat(BloomKernel.luminance(bloomed.getRGB(8, 7)))
                .as("the neighbour that was black now glows")
                .isGreaterThan(0);
        assertThat(bloomed.getRGB(7, 7) & 0xFF000000)
                .as("alpha is carried through the seam")
                .isEqualTo(0xFF000000);
    }

    @Test
    void toFrameAndToImageRoundTripThePixelsExactly() {
        int[] pixels = {0xFF102030, 0xFF405060, 0xFF708090, 0xFFA0B0C0};
        BloomKernel.Frame frame = new BloomKernel.Frame(2, 2, pixels);

        BufferedImage image = BloomPass.toImage(frame);
        BloomKernel.Frame back = BloomPass.toFrame(image);

        assertThat(back.width()).isEqualTo(2);
        assertThat(back.height()).isEqualTo(2);
        assertThat(back.argb()).isEqualTo(pixels);
    }

    @Test
    void thePlanReasonNowCarriesTheMeasuredCostNotTheOldShrug() {
        String reason = PostProcessPlan.decide(Set.of(Capability.BLOOM_WANTED)).reason();

        assertThat(reason)
                .as("the software route says WHY, citing the probe that measured it")
                .contains("probe-gated")
                .contains("WlProbe52")
                .contains("419");
    }
}
