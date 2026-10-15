/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client P4.1: the mixer's whole verb contract, headless and on-device-equal.
 *
 * The clip-present path used to be reachable ONLY on a machine with a sound
 * card: CI called AudioSystem.getClip(), got LineUnavailableException, and every
 * line past it stayed uncovered -- which is what let wonderland-client's coverage
 * read 98.4 % on a dev box and 97.3 % on CI. The ClipSource seam turns that
 * device dependence into injected behaviour, so this suite drives a fake Clip
 * through open / start / loop / pose / stop / close / re-load on BOTH CI and a
 * desktop, identically. The only thing a fake still cannot prove is that real
 * air moves -- that clause stays in the class header. A real FloatControl backs
 * the pose path (it is an abstract class, not a proxy), and the no-device
 * refusal is reproduced deterministically rather than left to the runner's luck.
 */
package org.jdesktop.wonderland.client.audio;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class JavaSoundBackendMixerTest {

    /** Eight bytes of silence at a format no real device has to support. */
    private static AudioInputStream silenceBytes() {
        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED, 8000, 8, 1, 1, 8000, false);
        return new AudioInputStream(new ByteArrayInputStream(new byte[8]), format, 8);
    }

    /**
     * A {@link Clip} that records every method name it receives and answers with
     * device-shaped defaults, so the backend's clip-present code runs with no
     * sound card. {@code running} drives isRunning(), {@code controls} whether
     * the mixer claims to support the gain/pan controls pose() reaches for.
     */
    private static Clip fakeClip(List<String> calls, AtomicBoolean running,
                                 AtomicBoolean controls) {
        FloatControl master = new FloatControl(
                FloatControl.Type.MASTER_GAIN, -120f, 0f, 1f, 0, 0f, null) {};
        FloatControl pan = new FloatControl(
                FloatControl.Type.PAN, -1f, 1f, 0.1f, 0, 0f, null) {};
        return (Clip) Proxy.newProxyInstance(
                JavaSoundBackendMixerTest.class.getClassLoader(),
                new Class<?>[] {Clip.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    calls.add(name);
                    switch (name) {
                        case "isRunning":
                            return running.get();
                        case "isOpen":
                            return true;
                        case "isActive":
                            return true;
                        case "isControlSupported":
                            return controls.get();
                        case "getControl":
                            return FloatControl.Type.PAN.equals(args[0]) ? pan : master;
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "fake-clip";
                        default:
                            return primitiveDefault(method.getReturnType());
                    }
                });
    }

    private static Object primitiveDefault(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        return null; // void and unmatched object returns
    }

    @Test
    void aLoadedClipOpensStartsIsPosedStopsAndClosesHeadlessly() {
        List<String> calls = new ArrayList<>();
        JavaSoundBackend backend = new JavaSoundBackend(
                () -> fakeClip(calls, new AtomicBoolean(false), new AtomicBoolean(true)));

        backend.load("bell", silenceBytes(), false);

        assertThat(backend.isLoaded("bell")).as("the bytes reached a fake clip").isTrue();
        backend.start("bell");
        backend.pose("bell", 0.5f, 0.3f);
        backend.stop("bell");
        backend.close();

        assertThat(backend.isLoaded("bell")).as("close clears the table").isFalse();
        assertThat(calls)
                .as("the full play-through the mixer never got to run on CI")
                .contains("open", "setFramePosition", "start", "getControl", "stop", "close");
    }

    @Test
    void aLoopingIdLoopsForeverRatherThanStartingOnce() {
        List<String> calls = new ArrayList<>();
        JavaSoundBackend backend = new JavaSoundBackend(
                () -> fakeClip(calls, new AtomicBoolean(false), new AtomicBoolean(true)));

        backend.load("pond", silenceBytes(), true);
        backend.start("pond");

        assertThat(calls).as("a looped source loops instead of one-shot start")
                .contains("loop")
                .doesNotContain("start");
        backend.close();
    }

    @Test
    void startOnAnAlreadyRunningClipIsIgnored() {
        List<String> calls = new ArrayList<>();
        JavaSoundBackend backend = new JavaSoundBackend(
                () -> fakeClip(calls, new AtomicBoolean(true), new AtomicBoolean(true)));

        backend.load("bell", silenceBytes(), false);
        calls.clear(); // keep only what start() itself does
        backend.start("bell");

        assertThat(calls).as("a running clip is not restarted")
                .doesNotContain("setFramePosition")
                .doesNotContain("start");
        backend.close();
    }

    @Test
    void aControlTheMixerLacksIsSkippedNotFatal() {
        List<String> calls = new ArrayList<>();
        JavaSoundBackend backend = new JavaSoundBackend(
                () -> fakeClip(calls, new AtomicBoolean(false), new AtomicBoolean(false)));

        backend.load("bell", silenceBytes(), false);
        assertThatCode(() -> backend.pose("bell", 0.5f, 0f)).doesNotThrowAnyException();

        assertThat(calls).as("pose asked the clip, the clip refused the control")
                .contains("isControlSupported")
                .doesNotContain("getControl");
        backend.close();
    }

    @Test
    void aReloadReplacesAndClosesThePreviousLine() {
        List<String> calls = new ArrayList<>();
        JavaSoundBackend backend = new JavaSoundBackend(
                () -> fakeClip(calls, new AtomicBoolean(false), new AtomicBoolean(true)));

        backend.load("bell", silenceBytes(), false);
        backend.load("bell", silenceBytes(), false); // re-load must not leak the old line

        assertThat(calls).as("the replaced clip was closed")
                .contains("close");
        assertThat(backend.isLoaded("bell")).isTrue();
        backend.close();
    }

    @Test
    void aDeviceThatRefusesTheLineLeavesTheWorldSilentAndUnloadable() {
        // The exact CI path, made deterministic instead of left to the runner:
        // getClip() throws, load() absorbs it, nothing is loaded, every verb no-ops.
        JavaSoundBackend backend = new JavaSoundBackend(() -> {
            throw new LineUnavailableException("no device on this runner");
        });

        assertThatCode(() -> backend.load("bell", silenceBytes(), false))
                .as("a refused device is a runtime fact, not an exception")
                .doesNotThrowAnyException();
        assertThat(backend.isLoaded("bell")).isFalse();
        assertThatCode(() -> {
            backend.start("bell");
            backend.pose("bell", 1f, 0f);
            backend.stop("bell");
        }).doesNotThrowAnyException();
    }

    @Test
    void theFakeAnswersTheStatusQueriesTheBackendReliesOn() {
        // Guards the fake's pass-through so pose()/start() cannot be fooled by an
        // unexpected Clip accessor: isRunning/isOpen speak the same words.
        Clip clip = fakeClip(new ArrayList<>(), new AtomicBoolean(true), new AtomicBoolean(true));
        assertThat(clip.isRunning()).isTrue();
        assertThat(clip.isOpen()).isTrue();
    }
}
