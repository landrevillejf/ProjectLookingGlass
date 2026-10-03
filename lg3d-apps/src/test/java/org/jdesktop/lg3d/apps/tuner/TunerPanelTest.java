/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.tuner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link TunerPanel}'s construction and its detection-to-UI seam without
 * ever opening a capture device: the panel builds headless, and the tests drive
 * {@link TunerPanel#applyReading} directly on the EDT to assert that a pitch
 * resolves to the nearest note, string and cents deviation. Because the panel
 * only opens the microphone when the user presses Start, all of this runs in CI.
 */
class TunerPanelTest {

    /** Runs a task on the EDT and blocks until it completes (Swing is headless-safe). */
    private static void onEdt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("EDT task interrupted", e);
        } catch (InvocationTargetException e) {
            throw new AssertionError("EDT task failed", e.getCause());
        }
    }

    @Test
    @DisplayName("a fresh panel is idle, on guitar standard, and shows no note")
    void defaultsAreIdle() {
        TunerPanel panel = new TunerPanel();
        assertFalse(panel.isListening());
        assertEquals("--", panel.noteText());
        assertSame(Tuning.GUITAR_STANDARD, panel.selectedTuning());
        assertTrue(panel.statusText().contains("Press Start"),
                "status should invite the user to start: " + panel.statusText());
        assertFalse(panel.meter().isVoiced());
    }

    @Test
    @DisplayName("the capture format is 16-bit little-endian mono linear PCM")
    void micFormatIsLinearPcm() {
        AudioFormat f = TunerPanel.micFormat(44100);
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, f.getEncoding());
        assertEquals(44100f, f.getSampleRate());
        assertEquals(16, f.getSampleSizeInBits());
        assertEquals(1, f.getChannels());
        assertEquals(2, f.getFrameSize());
        assertFalse(f.isBigEndian(), "capture PCM is little-endian");
        // A fully-specified linear format matches itself (device-free check).
        assertTrue(f.matches(TunerPanel.micFormat(44100)));
    }

    @Test
    @DisplayName("a voiced A2 (110 Hz) reading lights the note, string and in-tune meter")
    void voicedReadingResolvesNote() {
        TunerPanel panel = new TunerPanel();
        onEdt(() -> panel.applyReading(new PitchDetector.Pitch(110f, 0.95f)));
        assertEquals("A2", panel.noteText());
        assertTrue(panel.meter().isVoiced());
        assertTrue(panel.meter().isInTune(), "110 Hz is exactly the A2 open string");
        assertTrue(panel.statusText().contains("110.0 Hz"),
                "status should report the frequency: " + panel.statusText());
        assertTrue(panel.statusText().contains("cents"),
                "status should report the deviation: " + panel.statusText());
        assertTrue(panel.detailText().contains("String 2"),
                "detail should name the A string: " + panel.detailText());
    }

    @Test
    @DisplayName("a sharp reading drives the needle off centre and out of tune")
    void sharpReadingIsOutOfTune() {
        TunerPanel panel = new TunerPanel();
        // A2 sharp by ~40 cents.
        float sharp = (float) (110.0 * Math.pow(2.0, 40.0 / 1200.0));
        onEdt(() -> panel.applyReading(new PitchDetector.Pitch(sharp, 0.9f)));
        assertEquals("A2", panel.noteText());
        assertFalse(panel.meter().isInTune());
    }

    @Test
    @DisplayName("a bass reading resolves against the bass tuning")
    void bassReadingResolves() {
        TunerPanel panel = new TunerPanel();
        onEdt(() -> {
            panel.tuningBox().setSelectedItem(Tuning.BASS_STANDARD);
            panel.applyReading(new PitchDetector.Pitch(41.2f, 0.9f));
        });
        assertSame(Tuning.BASS_STANDARD, panel.selectedTuning());
        assertEquals("E1", panel.noteText());
        assertTrue(panel.meter().isInTune());
    }

    @Test
    @DisplayName("chromatic mode still names the nearest note")
    void chromaticModeNamesNote() {
        TunerPanel panel = new TunerPanel();
        onEdt(() -> {
            panel.tuningBox().setSelectedItem(Tuning.CHROMATIC);
            panel.applyReading(new PitchDetector.Pitch(440f, 0.99f));
        });
        assertEquals("A4", panel.noteText());
        assertTrue(panel.detailText().contains("Nearest note"),
                "chromatic detail should read 'Nearest note': " + panel.detailText());
    }

    @Test
    @DisplayName("an unvoiced or null reading resets the display")
    void unvoicedReadingResets() {
        TunerPanel panel = new TunerPanel();
        onEdt(() -> panel.applyReading(new PitchDetector.Pitch(196f, 0.9f)));
        assertEquals("G3", panel.noteText());
        onEdt(() -> panel.applyReading(PitchDetector.Pitch.unvoiced()));
        assertEquals("--", panel.noteText());
        assertFalse(panel.meter().isVoiced());
        onEdt(() -> panel.applyReading(null));
        assertEquals("--", panel.noteText());
    }

    @Test
    @DisplayName("changing the tuning resets the note display")
    void changingTuningResetsDisplay() {
        TunerPanel panel = new TunerPanel();
        onEdt(() -> panel.applyReading(new PitchDetector.Pitch(110f, 0.95f)));
        assertEquals("A2", panel.noteText());
        onEdt(() -> panel.tuningBox().setSelectedItem(Tuning.GUITAR_DROP_D));
        assertSame(Tuning.GUITAR_DROP_D, panel.selectedTuning());
        assertEquals("--", panel.noteText(), "switching tuning clears the stale note");
    }

    @Test
    @DisplayName("stopListening is a safe no-op when nothing is capturing")
    void stopIsSafeWhenIdle() {
        TunerPanel panel = new TunerPanel();
        onEdt(panel::stopListening);
        assertFalse(panel.isListening());
        assertEquals("--", panel.noteText());
        assertTrue(panel.statusText().contains("Stopped"),
                "status should report stopped: " + panel.statusText());
    }

    @Test
    @DisplayName("close stops any capture and fires the host callback")
    void closeStopsAndNotifies() {
        TunerPanel panel = new TunerPanel();
        boolean[] ran = {false};
        panel.setOnClose(() -> ran[0] = true);
        onEdt(panel::close);
        assertTrue(ran[0], "the host close callback should run");
        assertFalse(panel.isListening());
    }

    @Test
    @DisplayName("start then stop exercises the real capture line without leaking it")
    void startThenStopIsSafe() {
        // On a host with a microphone this opens and closes the real capture
        // line; on a mic-less CI host startListening degrades to a status
        // message. Either way no exception escapes, the line is released and the
        // panel ends idle - the microphone is never left hot.
        TunerPanel panel = new TunerPanel();
        onEdt(panel::startListening);
        boolean wasListening = panel.isListening();
        onEdt(panel::stopListening);
        assertFalse(panel.isListening(), "stop must always return the panel to idle");
        assertFalse(panel.statusText().isBlank(),
                "the status line always explains the outcome: " + panel.statusText());
        if (!wasListening) {
            assertTrue(panel.statusText().toLowerCase().contains("microphone"),
                    "a mic-less host reports no microphone: " + panel.statusText());
        }
    }

    @Test
    @DisplayName("querying line support for the capture format never throws")
    void micFormatLineQueryIsSafe() {
        // CI and headless hosts often have no audio device, in which case
        // isLineSupported simply returns false. This exercises the query path so
        // a malformed format would surface as an exception.
        AudioFormat f = TunerPanel.micFormat(48000);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, f);
        assertDoesNotThrow(() -> AudioSystem.isLineSupported(info));
    }
}
