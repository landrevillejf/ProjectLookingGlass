/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P3.2: the bloom route decision, headless.
 *
 * PostProcessPlan is the pure half of a GPU-bound phase -- the answer to "where
 * may a bloom run?" stated where a human and a test can both read it. Every branch
 * is a claim about evidence, so what this suite pins is exactly the contract a host
 * depends on: no bloom wanted is NONE whatever else is probed; the GPU preSwap
 * route is chosen only on its OWN measured capability and is preferred over the
 * readback route when both are in evidence (it never pays the readback's cost);
 * absent the GPU hook the readback route needs its own probe; and absent both the
 * software route is chosen and SAYS why, citing the measurement. What no branch
 * does is enable a live route on a wish -- each carries its sentence.
 */
package org.jdesktop.wonderland.renderer.post;

import java.util.Set;
import org.jdesktop.wonderland.renderer.post.PostProcessPlan.Capability;
import org.jdesktop.wonderland.renderer.post.PostProcessPlan.Decision;
import org.jdesktop.wonderland.renderer.post.PostProcessPlan.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PostProcessPlanTest {

    @Test
    void aNullOrEmptyCapabilitySetDecidesNone() {
        assertThat(PostProcessPlan.decide(null).path()).isEqualTo(Path.NONE);
        assertThat(PostProcessPlan.decide(Set.of()).path()).isEqualTo(Path.NONE);
    }

    @Test
    void noBloomWantedIsNoneWhateverElseIsProbed() {
        // The user's own choice is never overridden: probed evidence for a live
        // route is meaningless when nobody asked for a glow at all.
        assertThat(PostProcessPlan.decide(Set.of(Capability.GPU_POSTPROCESS_PROBED_USABLE)).path())
                .isEqualTo(Path.NONE);
        assertThat(PostProcessPlan.decide(Set.of(Capability.READBACK_PROBED_USABLE)).path())
                .isEqualTo(Path.NONE);
        assertThat(PostProcessPlan.decide(Set.of(Capability.GPU_POSTPROCESS_PROBED_USABLE,
                Capability.READBACK_PROBED_USABLE)).path())
                .isEqualTo(Path.NONE);
        assertThat(PostProcessPlan.decide(Set.of()).reason()).isEqualTo("no bloom requested");
    }

    @Test
    void bloomWantedAloneDecidesTheSoftwareRoute() {
        Decision d = PostProcessPlan.decide(Set.of(Capability.BLOOM_WANTED));

        assertThat(d.path()).isEqualTo(Path.SOFTWARE_CAPTURE);
        // The reason is the measured cost, not a shrug: a decision that silently
        // picked the slow path would be re-litigated every release.
        assertThat(d.reason())
                .contains("probe-gated")
                .contains("WlProbe52")
                .contains("419");
    }

    @Test
    void theGpuHookProbedUsableDecidesTheGpuRoute() {
        Decision d = PostProcessPlan.decide(
                Set.of(Capability.BLOOM_WANTED, Capability.GPU_POSTPROCESS_PROBED_USABLE));

        assertThat(d.path()).isEqualTo(Path.GPU_POSTPROCESS);
        // The reason names the patched hook and the probe that measured it visible
        // at a real-time rate with zero readback -- the claim, and its evidence.
        assertThat(d.reason())
                .contains("preSwap")
                .contains("WlProbe55")
                .contains("readback");
    }

    @Test
    void theGpuRouteIsPreferredOverReadbackWhenBothAreProbed() {
        Decision d = PostProcessPlan.decide(Set.of(Capability.BLOOM_WANTED,
                Capability.GPU_POSTPROCESS_PROBED_USABLE,
                Capability.READBACK_PROBED_USABLE));

        assertThat(d.path())
                .as("the zero-readback GPU route is strictly the better live path")
                .isEqualTo(Path.GPU_POSTPROCESS);
    }

    @Test
    void readbackProbedAloneDecidesTheOffscreenReadbackRoute() {
        Decision d = PostProcessPlan.decide(
                Set.of(Capability.BLOOM_WANTED, Capability.READBACK_PROBED_USABLE));

        assertThat(d.path()).isEqualTo(Path.OFFSCREEN_READBACK);
        assertThat(d.reason()).contains("offscreen round trip");
    }

    @Test
    void everyLiveDecisionCarriesANonBlankReasonBesideItsPath() {
        for (Capability[] combo : new Capability[][] {
                {Capability.BLOOM_WANTED},
                {Capability.BLOOM_WANTED, Capability.READBACK_PROBED_USABLE},
                {Capability.BLOOM_WANTED, Capability.GPU_POSTPROCESS_PROBED_USABLE}}) {
            Decision d = PostProcessPlan.decide(Set.of(combo));
            assertThat(d.reason()).as("%s must say why", d.path()).isNotBlank();
        }
    }

    @Test
    void thePlanExposesTheGpuRouteAndItsCapabilityAsFirstClassValues() {
        // A host switches on these; pinning them keeps a rename from silently
        // dropping the live GPU route out of the vocabulary.
        assertThat(Path.valueOf("GPU_POSTPROCESS")).isEqualTo(Path.GPU_POSTPROCESS);
        assertThat(Capability.valueOf("GPU_POSTPROCESS_PROBED_USABLE"))
                .isEqualTo(Capability.GPU_POSTPROCESS_PROBED_USABLE);
    }

    // ------------------------------------------------------------- P3.3 HDR

    @Test
    void hdrWithNoWantedFlagIsNoneWhateverElseIsProbed() {
        // The user's own choice is never overridden: a probe proving the hook is
        // present is meaningless when nobody asked for a tone map.
        assertThat(PostProcessPlan.decideHdr(null).path()).isEqualTo(Path.NONE);
        assertThat(PostProcessPlan.decideHdr(Set.of()).path()).isEqualTo(Path.NONE);
        assertThat(PostProcessPlan.decideHdr(
                Set.of(Capability.GPU_POSTPROCESS_PROBED_USABLE)).path())
                .isEqualTo(Path.NONE);
        assertThat(PostProcessPlan.decideHdr(Set.of()).reason())
                .isEqualTo("no HDR tone mapping requested");
    }

    @Test
    void hdrWantedAndTheHookProbedDecidesTheGpuRoute() {
        Decision d = PostProcessPlan.decideHdr(
                Set.of(Capability.HDR_WANTED, Capability.GPU_POSTPROCESS_PROBED_USABLE));
        assertThat(d.path()).isEqualTo(Path.GPU_POSTPROCESS);
        assertThat(d.reason()).contains("preSwap").contains("readback");
    }

    @Test
    void hdrWantedWithoutTheHookIsNoneNotASoftwareFallback() {
        // The whole point of decideHdr's shape: tone mapping has NO honest software
        // route, so absent the patched hook it declines rather than pretending a
        // capture-only curve tonemapped the live canvas.
        Decision d = PostProcessPlan.decideHdr(Set.of(Capability.HDR_WANTED));
        assertThat(d.path()).isEqualTo(Path.NONE);
        assertThat(d.reason()).contains("live GPU pass only");
    }

    @Test
    void theHdrWantedCapabilityIsAFirstClassValue() {
        assertThat(Capability.valueOf("HDR_WANTED")).isEqualTo(Capability.HDR_WANTED);
    }

    // ------------------------------------------------------------- P3.4 FXAA

    @Test
    void fxaaWithNoWantedFlagIsNoneWhateverElseIsProbed() {
        assertThat(PostProcessPlan.decideFxaa(null).path()).isEqualTo(Path.NONE);
        assertThat(PostProcessPlan.decideFxaa(Set.of()).path()).isEqualTo(Path.NONE);
        assertThat(PostProcessPlan.decideFxaa(
                Set.of(Capability.GPU_POSTPROCESS_PROBED_USABLE)).path())
                .isEqualTo(Path.NONE);
        assertThat(PostProcessPlan.decideFxaa(Set.of()).reason())
                .isEqualTo("no anti-aliasing requested");
    }

    @Test
    void fxaaWantedAndTheHookProbedDecidesTheGpuRoute() {
        Decision d = PostProcessPlan.decideFxaa(
                Set.of(Capability.FXAA_WANTED, Capability.GPU_POSTPROCESS_PROBED_USABLE));
        assertThat(d.path()).isEqualTo(Path.GPU_POSTPROCESS);
        assertThat(d.reason()).contains("preSwap").contains("readback");
    }

    @Test
    void fxaaWantedWithoutTheHookIsNoneNotASoftwareFallback() {
        Decision d = PostProcessPlan.decideFxaa(Set.of(Capability.FXAA_WANTED));
        assertThat(d.path()).isEqualTo(Path.NONE);
        assertThat(d.reason()).contains("live GPU pass only");
    }

    @Test
    void theFxaaWantedCapabilityIsAFirstClassValue() {
        assertThat(Capability.valueOf("FXAA_WANTED")).isEqualTo(Capability.FXAA_WANTED);
    }

    // -------------------------------------------------------------- P3.5 DOF

    @Test
    void dofWithNoWantedFlagIsNoneWhateverElseIsProbed() {
        assertThat(PostProcessPlan.decideDof(null).path()).isEqualTo(Path.NONE);
        assertThat(PostProcessPlan.decideDof(Set.of()).path()).isEqualTo(Path.NONE);
        assertThat(PostProcessPlan.decideDof(
                Set.of(Capability.GPU_POSTPROCESS_PROBED_USABLE)).path())
                .isEqualTo(Path.NONE);
        assertThat(PostProcessPlan.decideDof(Set.of()).reason())
                .isEqualTo("no depth of field requested");
    }

    @Test
    void dofWantedAndTheHookProbedDecidesTheGpuRoute() {
        Decision d = PostProcessPlan.decideDof(
                Set.of(Capability.DOF_WANTED, Capability.GPU_POSTPROCESS_PROBED_USABLE));
        assertThat(d.path()).isEqualTo(Path.GPU_POSTPROCESS);
        // The reason names the hook, the zero readback AND the depth the defocus blits
        // beside the colour -- the one thing that sets this link apart from the others.
        assertThat(d.reason()).contains("preSwap").contains("readback").contains("depth");
    }

    @Test
    void dofWantedWithoutTheHookIsNoneNotASoftwareFallback() {
        // The whole point of decideDof's shape: a defocus has NO honest software route,
        // so absent the patched hook it declines rather than pretending a capture-only
        // blur gave the live canvas a focus it does not have.
        Decision d = PostProcessPlan.decideDof(Set.of(Capability.DOF_WANTED));
        assertThat(d.path()).isEqualTo(Path.NONE);
        assertThat(d.reason()).contains("live GPU pass only");
    }

    @Test
    void theDofWantedCapabilityIsAFirstClassValue() {
        assertThat(Capability.valueOf("DOF_WANTED")).isEqualTo(Capability.DOF_WANTED);
    }

    // ------------------------------------------------------------- P2.7 SSAO

    @Test
    void ssaoWithNoWantedFlagIsNoneWhateverElseIsProbed() {
        assertThat(PostProcessPlan.decideSsao(null).path()).isEqualTo(Path.NONE);
        assertThat(PostProcessPlan.decideSsao(Set.of()).path()).isEqualTo(Path.NONE);
        assertThat(PostProcessPlan.decideSsao(
                Set.of(Capability.GPU_POSTPROCESS_PROBED_USABLE)).path())
                .isEqualTo(Path.NONE);
        assertThat(PostProcessPlan.decideSsao(Set.of()).reason())
                .isEqualTo("no ambient occlusion requested");
    }

    @Test
    void ssaoWantedAndTheHookProbedDecidesTheGpuRoute() {
        Decision d = PostProcessPlan.decideSsao(
                Set.of(Capability.SSAO_WANTED, Capability.GPU_POSTPROCESS_PROBED_USABLE));
        assertThat(d.path()).isEqualTo(Path.GPU_POSTPROCESS);
        // The reason names the hook, the zero readback AND the depth the occlusion
        // reconstructs its normal from -- the buffer that makes screen-space AO possible.
        assertThat(d.reason()).contains("preSwap").contains("readback").contains("depth");
    }

    @Test
    void ssaoWantedWithoutTheHookIsNoneNotASoftwareFallback() {
        // The whole point of decideSsao's shape: an occlusion pass has NO honest software
        // route, so absent the patched hook it declines rather than pretending a
        // capture-only darkening gave the live canvas contact shadows it does not have.
        Decision d = PostProcessPlan.decideSsao(Set.of(Capability.SSAO_WANTED));
        assertThat(d.path()).isEqualTo(Path.NONE);
        assertThat(d.reason()).contains("live GPU pass only");
    }

    @Test
    void theSsaoWantedCapabilityIsAFirstClassValue() {
        assertThat(Capability.valueOf("SSAO_WANTED")).isEqualTo(Capability.SSAO_WANTED);
    }
}
