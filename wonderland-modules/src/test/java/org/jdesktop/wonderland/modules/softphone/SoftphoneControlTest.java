/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d: the SoftphoneControl state machine against a
 * recording backend — the stub that stands in for the external process in
 * CI, exactly like the recording rasteriser did for PDFBox in M7b. Every
 * 2006 behaviour with a decision in it (exit-once, the recorder's pause
 * rules, the engine-overrides-local event flow, the Noop's honest failure)
 * is asserted here without a binary, a socket or a sound card.
 */
package org.jdesktop.wonderland.modules.softphone;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SoftphoneControlTest {

    private RecordingBackend backend;
    private SoftphoneControl control;
    private Recorder recorder;

    @BeforeEach
    void setUp() {
        backend = new RecordingBackend();
        // the control exposes the handler a live backend would install at
        // construction; the test wires it by hand so fire() can reach it
        control = new SoftphoneControl(backend);
        backend.handler = control.eventHandler();
        recorder = new Recorder();
        control.addSoftphoneListener(recorder);
    }

    private String startPhone() {
        String callId = control.startSoftphone("sip.example", "alice", 5060, "20315");
        assertThat(callId).isNotNull();
        return callId;
    }

    @Test
    @DisplayName("the Noop backend fails honestly: no call id, one problem")
    void noopStartFails() {
        SoftphoneControl headless = new SoftphoneControl(NoopSoftphoneBackend.INSTANCE);
        Recorder problems = new Recorder();
        headless.addSoftphoneListener(problems);
        assertThat(headless.startSoftphone("h", "u", 5060, "1")).isNull();
        assertThat(headless.isRunning()).isFalse();
        assertThat(problems.events).hasSize(1);
        assertThat(problems.events.get(0)).startsWith("problem=");
    }

    @Test
    @DisplayName("a nonsense port is refused before anything spawns")
    void invalidPort() {
        Recorder fresh = new Recorder();
        SoftphoneControl c = new SoftphoneControl(backend);
        c.addSoftphoneListener(fresh);
        assertThat(c.startSoftphone("h", "u", 0, "1")).isNull();
        assertThat(fresh.events).containsExactly("problem=Invalid port: 0");
    }

    @Test
    @DisplayName("start hands back a call id and writes the hello pair")
    void startWritesHelloPair() {
        String callId = startPhone();
        assertThat(control.getCallID()).isEqualTo(callId);
        assertThat(control.isRunning()).isTrue();
        assertThat(backend.lines).containsExactly(
                "START sip.example:5060 alice 20315",
                "callID=" + callId,
                "register alice");
    }

    @Test
    @DisplayName("a second start on a live phone is a problem, not a process")
    void doubleStartRefused() {
        startPhone();
        recorder.events.clear();
        assertThat(control.startSoftphone("h", "u", 5060, "1")).isNull();
        assertThat(recorder.events).containsExactly("problem=softphone already running");
    }

    @Test
    @DisplayName("mute and unmute flip the state, write the verbs, fire once each")
    void muteRoundTrip() {
        startPhone();
        recorder.events.clear();
        assertThat(control.mute()).isTrue();
        assertThat(control.isMuted()).isTrue();
        assertThat(control.unmute()).isTrue();
        assertThat(control.isMuted()).isFalse();
        assertThat(backend.lines).contains("mute", "Unmute");
        assertThat(recorder.events).containsExactly("muted=true", "muted=false");
    }

    @Test
    @DisplayName("the engine's echo overrides the local guess")
    void engineEchoWins() {
        startPhone();
        control.mute();
        backend.fire(new SoftphoneEvent.Muted(false));   // the process disagrees
        assertThat(control.isMuted()).isFalse();
        assertThat(recorder.events).endsWith("muted=false");
    }

    @Test
    @DisplayName("visibility and connection are engine-driven")
    void engineDrivenState() {
        startPhone();
        assertThat(control.isVisible()).isFalse();
        backend.fire(new SoftphoneEvent.Visible(true));
        assertThat(control.isVisible()).isTrue();
        assertThat(recorder.events).contains("visible=true");

        assertThat(control.isConnected()).isFalse();
        backend.fire(new SoftphoneEvent.Connected(true));
        assertThat(control.isConnected()).isTrue();
        // the local setVisible still tells the process
        assertThat(control.setVisible(false)).isTrue();
        assertThat(backend.lines).contains("invisible");
    }

    @Test
    @DisplayName("softphoneExited fires AT MOST ONCE per process")
    void exitFiresOnce() {
        startPhone();
        backend.fire(new SoftphoneEvent.Exited(0));
        backend.fire(new SoftphoneEvent.Exited(0));      // a second death notice
        assertThat(recorder.events).containsExactly("exited");
        assertThat(control.isRunning()).isFalse();
        assertThat(control.isConnected()).isFalse();
    }

    @Test
    @DisplayName("a crash exit adds its status problem before the goodbye")
    void crashExitReportsStatus() {
        startPhone();
        recorder.events.clear();
        backend.fire(new SoftphoneEvent.Exited(3));
        assertThat(recorder.events).containsExactly(
                "problem=softphone exited with status 3", "exited");
    }

    @Test
    @DisplayName("stopSoftphone: graceful, idempotent, and a fresh start re-arms")
    void stopThenRestart() {
        String first = startPhone();
        control.stopSoftphone();
        assertThat(recorder.events).contains("exited");
        control.stopSoftphone();                          // idempotent: no second exit
        assertThat(recorder.events.stream().filter("exited"::equals).count())
                .isEqualTo(1);
        String second = startPhone();                     // the once-flag re-arms
        assertThat(second).isNotNull().isNotEqualTo(first);
    }

    @Test
    @DisplayName("the recorder honours its pause/resume/stop state table")
    void recordingStates() {
        startPhone();
        assertThat(control.pauseRecordingAudio()).isFalse();     // nothing running
        assertThat(control.recordAudio("/tmp/a.wav", true)).isTrue();
        assertThat(control.getRecordingPath()).isEqualTo("/tmp/a.wav");
        assertThat(backend.lines).contains("recordAudio=/tmp/a.wav", "record");
        assertThat(control.pauseRecordingAudio()).isTrue();
        assertThat(control.pauseRecordingAudio()).isFalse();     // already paused
        assertThat(control.resumeRecordingAudio()).isTrue();
        assertThat(control.resumeRecordingAudio()).isFalse();    // already running
        assertThat(backend.lines).contains("pause", "record");
        assertThat(control.stopRecordingAudio()).isTrue();
        assertThat(control.stopRecordingAudio()).isFalse();      // stopped is stopped
        assertThat(control.getRecordingPath()).isNull();
        assertThat(control.recordAudio("/tmp/b.wav", false)).isTrue();  // stop verb again
        assertThat(control.stopRecordingAudio()).isFalse();
    }

    @Test
    @DisplayName("quality persists even when the engine is dead; lines only live")
    void qualityState() {
        assertThat(control.getAudioQuality()).isEqualTo(AudioQuality.VPN);
        assertThat(control.setAudioQuality(AudioQuality.VPN)).isTrue();   // no-op true
        assertThat(control.setAudioQuality(AudioQuality.BEST)).isTrue();  // dead: state only
        assertThat(control.getAudioQuality()).isEqualTo(AudioQuality.BEST);
        assertThat(backend.lines).doesNotContain("quality BEST");
        startPhone();
        assertThat(control.setAudioQuality(AudioQuality.MINIMUM)).isTrue();
        assertThat(backend.lines).contains("quality MINIMUM");
        assertThat(control.setAudioQuality(null)).isFalse();
    }

    @Test
    @DisplayName("VU meters feed only when switched on")
    void vuMeterGating() {
        startPhone();
        List<String> mic = new ArrayList<>();
        List<String> spk = new ArrayList<>();
        control.addMicrophoneInfoListener(new MicrophoneInfoListener() {
            @Override
            public void vuMeterValue(String value) {
                mic.add(value);
            }

            @Override
            public void volume(String value) {
                mic.add("volume=" + value);
            }
        });
        control.addSpeakerInfoListener(new SpeakerInfoListener() {
            @Override
            public void vuMeterValue(String value) {
                spk.add(value);
            }

            @Override
            public void volume(String value) {
                spk.add("volume=" + value);
            }
        });
        control.feedMicrophoneSamples(new short[]{100, 200});
        assertThat(mic).isEmpty();                        // meter off: silent
        assertThat(control.isMicVuMeterRunning()).isFalse();
        control.startMicVuMeter(true);
        assertThat(control.isMicVuMeterRunning()).isTrue();
        control.feedMicrophoneSamples(new short[]{100, 200});
        assertThat(mic).hasSize(1);
        assertThat(mic.get(0)).startsWith("Microphone level: ");
        control.startMicVuMeter(false);
        control.feedMicrophoneSamples(new short[]{30000});
        assertThat(mic).hasSize(1);                       // off again
        control.feedSpeakerSamples(new short[]{100});
        assertThat(spk).isEmpty();                        // speaker never started
    }

    @Test
    @DisplayName("a hot mic reaches the listener as the 2006 gain warning")
    void gainWarningSurfaces() {
        startPhone();
        control.startMicVuMeter(true);
        control.feedMicrophoneSamples(hot());            // latch the warning
        recorder.events.clear();
        control.feedMicrophoneSamples(hot());            // fires on the next read
        assertThat(recorder.events).contains("gainTooHigh");
    }

    private static short[] hot() {
        short[] buffer = new short[160];
        java.util.Arrays.fill(buffer, (short) 30000);
        return buffer;
    }

    @Test
    @DisplayName("the engine's own meter lines route by device")
    void engineMeterLines() {
        startPhone();
        List<String> mic = new ArrayList<>();
        control.addMicrophoneInfoListener(new MicrophoneInfoListener() {
            @Override
            public void vuMeterValue(String value) {
                mic.add(value);
            }

            @Override
            public void volume(String value) {
                mic.add(value);
            }
        });
        backend.fire(new SoftphoneEvent.VuLevel("Microphone", "55%"));
        assertThat(mic).containsExactly("Microphone level: 55%");
    }

    @Test
    @DisplayName("the UDP line test round-trips through the listener")
    void lineTestReachesListener() {
        startPhone();
        recorder.events.clear();
        assertThat(control.runLineTest()).isTrue();
        assertThat(backend.lines).contains("linetest");
        backend.fire(new SoftphoneEvent.TestUdpPorts(5004, 5004));
        assertThat(recorder.events).containsExactly("udptest=5004/5004");
    }

    @Test
    @DisplayName("a listener that throws cannot deafen the rest of the room")
    void throwingListenerIsIsolated() {
        control.addSoftphoneListener(new SoftphoneListener() {
            @Override
            public void softphoneVisible(boolean visible) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void softphoneMuted(boolean muted) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void softphoneConnected(boolean connected) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void softphoneExited() {
                throw new IllegalStateException("boom");
            }

            @Override
            public void softphoneProblem(String message) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void softphoneTestUDPPort(int sentPort, int receivedPort) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void microphoneGainTooHigh() {
                throw new IllegalStateException("boom");
            }
        });
        startPhone();
        recorder.events.clear();
        control.mute();
        assertThat(recorder.events).containsExactly("muted=true");
    }

    @Test
    @DisplayName("raw lines pass through verbatim; the dead phone stays quiet")
    void verbatimAndDeadPhone() {
        assertThat(control.sendCommandToSoftphone("anything")).isFalse();
        assertThat(control.mute()).isFalse();
        assertThat(control.setVisible(true)).isFalse();
        assertThat(control.runLineTest()).isFalse();
        assertThat(control.register("x")).isFalse();
        startPhone();
        assertThat(control.sendCommandToSoftphone("  someDialect  ")).isTrue();
        assertThat(backend.lines).contains("someDialect");
        assertThat(control.register("bob")).isTrue();
        assertThat(backend.lines).contains("register bob");
    }

    @Test
    @DisplayName("setCallID re-binds a live phone and is silent on a dead one")
    void callIdRebind() {
        control.setCallID("dead-bind");                   // no throw, no line
        assertThat(control.getCallID()).isEqualTo("dead-bind");
        assertThat(backend.lines).isEmpty();
        startPhone();
        control.setCallID("c-2");
        assertThat(backend.lines).contains("callID=c-2");
    }

    @Test
    @DisplayName("logAudioProblem reaches listeners without touching the process")
    void localProblemOnly() {
        startPhone();
        recorder.events.clear();
        control.logAudioProblem("no microphone found");
        assertThat(recorder.events).containsExactly("problem=no microphone found");
        assertThat(backend.lines).noneMatch(line -> line.contains("no microphone"));
    }

    @Test
    @DisplayName("a null backend is refused at construction")
    void nullBackendIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new SoftphoneControl(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("backend");
    }

    @Test
    @DisplayName("the dead-phone guards: unmute and the recorder refuse too")
    void deadPhoneRefusesTheRest() {
        assertThat(control.unmute()).isFalse();
        assertThat(control.recordAudio("/tmp/dead.wav", true)).isFalse();
        assertThat(control.recordAudio("/tmp/dead.wav", false)).isFalse();
        assertThat(control.resumeRecordingAudio()).isFalse();
        assertThat(control.stopRecordingAudio()).isFalse();
        assertThat(backend.lines).isEmpty();
    }

    @Test
    @DisplayName("the speaker meter starts, feeds, and resets like the mic one")
    void speakerMeterLifecycle() {
        startPhone();
        List<String> spk = new ArrayList<>();
        control.addSpeakerInfoListener(new SpeakerInfoListener() {
            @Override
            public void vuMeterValue(String value) {
                spk.add(value);
            }

            @Override
            public void volume(String value) {
                spk.add("volume=" + value);
            }
        });
        assertThat(control.isSpeakerVuMeterRunning()).isFalse();
        control.startSpeakerVuMeter(true);
        assertThat(control.isSpeakerVuMeterRunning()).isTrue();
        control.feedSpeakerSamples(new short[]{100, 200});
        assertThat(spk).hasSize(1);
        assertThat(spk.get(0)).startsWith("Speaker level: ");
        control.startSpeakerVuMeter(false);
        assertThat(control.isSpeakerVuMeterRunning()).isFalse();
        control.feedSpeakerSamples(new short[]{30000});   // off again: silent
        assertThat(spk).hasSize(1);
    }

    @Test
    @DisplayName("every listener add has a working remove")
    void listenerRemoval() {
        startPhone();
        control.removeSoftphoneListener(recorder);
        List<String> mic = new ArrayList<>();
        MicrophoneInfoListener micListener = new MicrophoneInfoListener() {
            @Override
            public void vuMeterValue(String value) {
                mic.add(value);
            }

            @Override
            public void volume(String value) {
                mic.add(value);
            }
        };
        List<String> spk = new ArrayList<>();
        SpeakerInfoListener spkListener = new SpeakerInfoListener() {
            @Override
            public void vuMeterValue(String value) {
                spk.add(value);
            }

            @Override
            public void volume(String value) {
                spk.add(value);
            }
        };
        control.addMicrophoneInfoListener(micListener);
        control.addSpeakerInfoListener(spkListener);
        control.startMicVuMeter(true);
        control.startSpeakerVuMeter(true);
        control.removeMicrophoneInfoListener(micListener);
        control.removeSpeakerInfoListener(spkListener);
        control.feedMicrophoneSamples(new short[]{500});
        control.feedSpeakerSamples(new short[]{500});
        backend.fire(new SoftphoneEvent.Connected(true));
        assertThat(mic).isEmpty();                        // detached, deaf
        assertThat(spk).isEmpty();
        assertThat(recorder.events).isEmpty();
    }

    @Test
    @DisplayName("the engine's gain and speaker-meter lines route like local ones")
    void engineGainAndSpeakerLines() {
        startPhone();
        List<String> spk = new ArrayList<>();
        control.addSpeakerInfoListener(new SpeakerInfoListener() {
            @Override
            public void vuMeterValue(String value) {
                spk.add(value);
            }

            @Override
            public void volume(String value) {
                spk.add(value);
            }
        });
        recorder.events.clear();
        backend.fire(new SoftphoneEvent.MicrophoneGainTooHigh());
        assertThat(recorder.events).containsExactly("gainTooHigh");
        backend.fire(new SoftphoneEvent.VuLevel("Speaker", "40%"));
        assertThat(spk).containsExactly("Speaker level: 40%");
    }

    @Test
    @DisplayName("a Problem line surfaces verbatim; an empty one is dropped")
    void problemEventRouting() {
        startPhone();
        recorder.events.clear();
        backend.fire(new SoftphoneEvent.Problem(""));     // nothing to say
        assertThat(recorder.events).isEmpty();
        backend.fire(new SoftphoneEvent.Problem("media failure"));
        assertThat(recorder.events).containsExactly("problem=media failure");
    }

    @Test
    @DisplayName("one deaf listener cannot mute the room on ANY callback")
    void throwingListenerIsIsolatedOnEveryFanOut() {
        control.addSoftphoneListener(new SoftphoneListener() {
            @Override
            public void softphoneVisible(boolean visible) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void softphoneMuted(boolean muted) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void softphoneConnected(boolean connected) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void softphoneExited() {
                throw new IllegalStateException("boom");
            }

            @Override
            public void softphoneProblem(String message) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void softphoneTestUDPPort(int sentPort, int receivedPort) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void microphoneGainTooHigh() {
                throw new IllegalStateException("boom");
            }
        });
        startPhone();
        recorder.events.clear();
        backend.fire(new SoftphoneEvent.Visible(true));
        backend.fire(new SoftphoneEvent.Connected(true));
        control.logAudioProblem("hic");
        backend.fire(new SoftphoneEvent.MicrophoneGainTooHigh());
        backend.fire(new SoftphoneEvent.Exited(0));       // exited once-guard too
        assertThat(recorder.events).containsExactly(
                "visible=true", "connected=true", "problem=hic",
                "gainTooHigh", "exited");
    }

    @Test
    @DisplayName("the shipped Noop answers false to every question, never throws")
    void noopBackendIsHonestlyDead() {
        assertThat(NoopSoftphoneBackend.INSTANCE
                .start("h", "u", 5060, "1")).isFalse();
        assertThat(NoopSoftphoneBackend.INSTANCE
                .send(SoftphoneCommand.mute())).isFalse();
        assertThat(NoopSoftphoneBackend.INSTANCE.isRunning()).isFalse();
        NoopSoftphoneBackend.INSTANCE.stop();             // nothing to reap
    }

    @Test
    @DisplayName("an engine that stops accepting lines degrades every verb")
    void deafEngineMidCall() {
        // the 2006 stdin could die while the process was "running" (a full
        // pipe, a half-crashed child); every verb reports false, none throws.
        // The start hello is fire-and-forget like the 2006 write pair, so
        // the phone still comes up — the DEAFING shows on the next verb.
        SoftphoneBackend firstOk = new SoftphoneBackend() {
            private boolean wrote;

            @Override
            public boolean start(String host, String user, int port, String number) {
                return true;
            }

            @Override
            public boolean send(SoftphoneCommand command) {
                if (!wrote) {
                    wrote = true;
                    return true;                      // the callID hello lands
                }
                return false;                         // then the pipe dies
            }

            @Override
            public boolean isRunning() {
                return wrote;
            }

            @Override
            public void stop() {
            }
        };
        SoftphoneControl live = new SoftphoneControl(firstOk);
        assertThat(live.startSoftphone("h", "u", 5060, "1")).isNotNull();
        assertThat(live.register("alice")).isFalse();
        assertThat(live.setVisible(true)).isFalse();
        assertThat(live.setAudioQuality(AudioQuality.BEST)).isFalse();
        assertThat(live.getAudioQuality()).isEqualTo(AudioQuality.BEST); // state kept
        assertThat(live.recordAudio("/tmp/x.wav", true)).isFalse();
        assertThat(live.recordAudio("/tmp/x.wav", false)).isFalse();
        assertThat(live.pauseRecordingAudio()).isFalse();
        assertThat(live.resumeRecordingAudio()).isFalse();
        assertThat(live.stopRecordingAudio()).isFalse();
        assertThat(live.sendCommandToSoftphone("anything")).isFalse();
        assertThat(live.runLineTest()).isFalse();

        // and the mid-state: the retarget line lands, the resume write dies —
        // the && chain of recordAudio(path, true) must report the failure
        SoftphoneBackend noResume = new SoftphoneBackend() {
            @Override
            public boolean start(String host, String user, int port, String number) {
                return true;
            }

            @Override
            public boolean send(SoftphoneCommand command) {
                return !"record".equals(command.toLine());
            }

            @Override
            public boolean isRunning() {
                return true;
            }

            @Override
            public void stop() {
            }
        };
        SoftphoneControl rec = new SoftphoneControl(noResume);
        assertThat(rec.startSoftphone("h", "u", 5060, "1")).isNotNull();
        assertThat(rec.recordAudio("/tmp/x.wav", true)).isFalse();
        assertThat(rec.getRecordingPath()).isEqualTo("/tmp/x.wav"); // retarget kept
        assertThat(rec.pauseRecordingAudio()).isTrue();              // pause lands
        assertThat(rec.pauseRecordingAudio()).isFalse();             // already paused
        assertThat(rec.resumeRecordingAudio()).isFalse();            // resume dies
        // stop WHILE PAUSED is still legal (the 2006 rule keyed on the path,
        // not the pause flag) and closes the file
        assertThat(rec.stopRecordingAudio()).isTrue();
        assertThat(rec.pauseRecordingAudio()).isFalse();             // no path left
        assertThat(rec.resumeRecordingAudio()).isFalse();            // ditto
    }
}
