/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client P4.1: the owner's bookkeeping, headless.
 *
 * The fake backend records every verb the manager says, so these asserts are
 * about the lifecycle: a sound starts when it first becomes audible and is
 * never started twice, walking out of earshot stops it, a re-registered
 * source replaces rather than ghosts, and unregistering a silent source
 * touches the mixer not at all. The numbers behind each verdict are the
 * kernel's, already pinned in SpatialAudioTest -- what is tested here is
 * who gets told what, and when.
 */
package org.jdesktop.wonderland.client.audio;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AudioManagerTest {

    /** Every backend call, in order, as one line of text. */
    private static final class RecordingBackend implements AudioBackend {
        final List<String> calls = new ArrayList<>();

        @Override
        public void start(String id) {
            calls.add("start " + id);
        }

        @Override
        public void pose(String id, float gain, float pan) {
            calls.add(String.format("pose %s %.2f %.2f", id, gain, pan));
        }

        @Override
        public void stop(String id) {
            calls.add("stop " + id);
        }

        @Override
        public void stopAll() {
            calls.add("stopAll");
        }
    }

    private static SpatialAudio.Source at(float z) {
        return new SpatialAudio.Source(0, 0, z, 1f, 10f, 1f);
    }

    @Test
    void anEmptyWorldSaysNothingToTheMixer() {
        RecordingBackend backend = new RecordingBackend();
        AudioManager manager = new AudioManager(backend);

        assertThat(manager.update()).isZero();
        assertThat(backend.calls).isEmpty();
    }

    @Test
    void aSourceStartsOnceAndIsThenPosedEveryFrame() {
        RecordingBackend backend = new RecordingBackend();
        AudioManager manager = new AudioManager(backend);
        manager.register("bell", at(2));

        assertThat(manager.update()).isEqualTo(1);
        assertThat(manager.update()).isEqualTo(1);

        assertThat(backend.calls).startsWith("start bell");
        assertThat(backend.calls.stream().filter(c -> c.startsWith("start ")).count())
                .as("never a second start while it keeps playing")
                .isEqualTo(1);
        assertThat(backend.calls).anySatisfy(call ->
                assertThat(call).startsWith("pose bell 0.50"));
    }

    @Test
    void walkingPastTheMaxDistanceStopsTheSoundOnce() {
        RecordingBackend backend = new RecordingBackend();
        AudioManager manager = new AudioManager(backend);
        manager.register("bell", at(2));
        manager.update();

        manager.register("bell", at(50)); // the cell moved out of earshot
        manager.update();
        manager.update();

        assertThat(backend.calls).containsExactly(
                "start bell", "pose bell 0.50 0.00", "stop bell");
    }

    @Test
    void unregisteringAMutedSourceNeverTouchesTheMixer() {
        RecordingBackend backend = new RecordingBackend();
        AudioManager manager = new AudioManager(backend);
        manager.register("far", at(50)); // registered, never audible, never started

        manager.unregister("far");
        manager.unregister("ghost"); // an unknown name is a no-op like the culler's

        assertThat(backend.calls).isEmpty();
        assertThat(manager.size()).isZero();
    }

    @Test
    void theListenerMovesTheVerdictsWithoutAnySourceMoving() {
        RecordingBackend backend = new RecordingBackend();
        AudioManager manager = new AudioManager(backend);
        manager.register("bell", at(2));
        manager.update();
        backend.calls.clear();

        manager.setListener(new SpatialAudio.Listener(0, 0, 100, 0)); // walked away
        int audible = manager.update();

        assertThat(audible).isZero();
        assertThat(backend.calls).containsExactly("stop bell");
    }

    @Test
    void silenceKeepsTheRegistrationsAndTheNextFrameRestartsThem() {
        RecordingBackend backend = new RecordingBackend();
        AudioManager manager = new AudioManager(backend);
        manager.register("bell", at(2));
        manager.update();

        manager.silence();
        assertThat(manager.size()).as("pause is not detach").isEqualTo(1);
        assertThat(backend.calls).endsWith("stop bell");

        backend.calls.clear();
        manager.update();
        assertThat(backend.calls).startsWith("start bell"); // and it plays again
    }

    @Test
    void clearDetachesEverythingAndSilencesTheWorld() {
        RecordingBackend backend = new RecordingBackend();
        AudioManager manager = new AudioManager(backend);
        manager.register("a", at(1));
        manager.register("b", at(2));
        manager.update();

        manager.clear();

        assertThat(manager.size()).isZero();
        assertThat(backend.calls).contains("stop a", "stop b");
        assertThat(manager.update()).isZero();
    }

    @Test
    void thePanArrivesAtTheMixerWithTheGain() {
        RecordingBackend backend = new RecordingBackend();
        AudioManager manager = new AudioManager(backend);
        manager.register("east", new SpatialAudio.Source(2, 0, 0, 1f, 10f, 1f));

        manager.update();

        assertThat(backend.calls).anySatisfy(call -> {
            assertThat(call).startsWith("pose east");
            assertThat(Float.parseFloat(call.split(" ")[3]))
                    .as("full right")
                    .isCloseTo(1f, within(0.01f));
        });
    }

    @Test
    void aManagerWithoutAMixerAndASourceWithoutANameAreBothRefused() {
        assertThatThrownBy(() -> new AudioManager(null))
                .isInstanceOf(NullPointerException.class);
        AudioManager manager = new AudioManager(new RecordingBackend());

        assertThatThrownBy(() -> manager.register(null, at(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> manager.setListener(null))
                .isInstanceOf(NullPointerException.class);
    }
}
