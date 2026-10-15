/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client P4.1: the mixer's testable half, headless.
 *
 * A Clip needs a sound card, and CI has none -- so what is asserted here is
 * exactly the part that needs no device: the linear-gain-to-decibels curve
 * every MASTER_GAIN consumes, and the refusal contract (an unknown id is a
 * no-op on every verb; a load a device cannot honour leaves the world
 * playing nothing rather than throwing). Whether the bell actually rings is
 * the class's own honesty clause and belongs to a machine with speakers.
 */
package org.jdesktop.wonderland.client.audio;

import java.io.ByteArrayInputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class JavaSoundBackendTest {

    /** Eight bytes of silence at a format no real device has to support. */
    private static AudioInputStream silenceBytes() {
        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED, 8000, 8, 1, 1, 8000, false);
        return new AudioInputStream(new ByteArrayInputStream(new byte[8]), format, 8);
    }

    @Test
    void theDecibelCurveMatchesThePhysicsAtTheEndsAndFloorsBelowZero() {
        assertThat(JavaSoundBackend.gainToDb(1f)).isCloseTo(0f, within(1e-6f));
        assertThat(JavaSoundBackend.gainToDb(0f)).isEqualTo(JavaSoundBackend.MIN_DB);
        assertThat(JavaSoundBackend.gainToDb(-3f))
                .as("the kernel never sends this, but the door is locked anyway")
                .isEqualTo(JavaSoundBackend.MIN_DB);
        assertThat(JavaSoundBackend.gainToDb(0.5f))
                .as("half the amplitude is the textbook -6.02 dB")
                .isCloseTo(-6.0206f, within(1e-3f));
    }

    @Test
    void theCurveIsMonotoneAndNeverBoostsPastUnity() {
        float previous = JavaSoundBackend.gainToDb(0.01f);
        for (float gain = 0.02f; gain <= 1.0f; gain += 0.01f) {
            float db = JavaSoundBackend.gainToDb(gain);
            assertThat(db).isGreaterThanOrEqualTo(previous);
            previous = db;
        }
        assertThat(JavaSoundBackend.gainToDb(3f))
                .as("a gain above unity would boost; the kernel clamps to 1 and so does this")
                .isCloseTo(0f, within(1e-6f));
    }

    @Test
    void everyVerbOnAnUnknownIdIsANoOpRatherThanAnException() {
        JavaSoundBackend backend = new JavaSoundBackend();

        assertThatCode(() -> {
            backend.start("never-loaded");
            backend.pose("never-loaded", 0.5f, 0.2f);
            backend.stop("never-loaded");
            backend.stopAll();
            backend.close();
        }).doesNotThrowAnyException();
    }

    @Test
    void aRefusedDeviceStaysSilentAndANullStreamIsRefusedLoudly() {
        JavaSoundBackend backend = new JavaSoundBackend();

        // No assertion about whether the clip opened: CI may have no mixer and
        // a dev box may have one. What must hold everywhere is the failure
        // policy -- a refused device is absorbed, and the verbs after it no-op.
        assertThatCode(() -> backend.load("bell", silenceBytes(), true))
                .doesNotThrowAnyException();
        assertThatCode(() -> {
            backend.start("bell");
            backend.pose("bell", 1f, 0f);
            backend.stop("bell");
        }).doesNotThrowAnyException();

        assertThatThrownBy(() -> backend.load("bell", null, false))
                .isInstanceOf(NullPointerException.class);
        backend.close();
    }
}
