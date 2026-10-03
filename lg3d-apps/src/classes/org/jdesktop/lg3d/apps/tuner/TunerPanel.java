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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * The guitar / bass tuner's Swing face: a live cent-deviation meter, the detected
 * note, and a strip of the selected tuning's strings with the nearest one
 * highlighted.
 *
 * <p>Pitch detection is genuinely native and in-process: the JDK's
 * {@code javax.sound.sampled} opens the default capture line and a daemon thread
 * streams 16-bit mono PCM into the AWT-free {@link PitchDetector} (the YIN
 * algorithm), which resolves the fundamental frequency. No external tool, no
 * codec and no recording - just analysis - so there is nothing to install. All
 * the pitch/note/tuning maths lives in the pure {@link PitchDetector},
 * {@link Note} and {@link Tuning} model, which is unit-tested headless.</p>
 *
 * <p>The panel is plain Swing and touches no Java&nbsp;3D, so the one class
 * serves both desktops: in 3D the {@link Tuner} wrapper hosts it on a
 * {@code SwingNode} inside a {@code Frame3D} via {@code TitledSwingWindow}; in
 * the 2D/Swing desktop {@code Desktop2DAppRegistry.PANEL_APPS} opens the very
 * same panel as an MDI internal frame. No capture device is opened until the user
 * presses Start, so the panel constructs and is asserted on headless - the
 * {@link #applyReading} seam drives the UI without a microphone.</p>
 */
public class TunerPanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 620;
    /** Panel height in native pixels. */
    public static final int HEIGHT_PX = 420;

    /** Samples analysed per frame (~93 ms at 44.1 kHz, ~10 readings/second). */
    static final int BUFFER_SAMPLES = 4096;

    /** The deviation (in cents) inside which a string reads as in tune. */
    static final float TOLERANCE_CENTS = 5f;

    /** Capture sample rates tried in order until one opens. */
    private static final int[] CANDIDATE_RATES = {44100, 48000, 22050, 16000};

    private static final Color BACKDROP = new Color(0x2B, 0x33, 0x3D);
    private static final Color CARD = new Color(0x36, 0x40, 0x4C);
    private static final Color TEXT = new Color(0xEC, 0xF1, 0xF7);
    private static final Color TEXT_DIM = new Color(0xA8, 0xB4, 0xC2);
    private static final Color ACCENT = new Color(0x6F, 0xB5, 0xE8);
    private static final Color GREEN = new Color(0x4C, 0xAF, 0x50);
    private static final Color STRING_IDLE_BG = new Color(0x2C, 0x35, 0x40);
    private static final Color STRING_ACTIVE_BG = new Color(0x6F, 0xB5, 0xE8);

    private final JComboBox<Tuning> tuningBox =
            new JComboBox<>(Tuning.ALL.toArray(new Tuning[0]));
    private final JButton startStopButton = new JButton("Start");
    private final JButton closeButton = new JButton("Close");
    private final JLabel noteLabel = new JLabel("--", SwingConstants.CENTER);
    private final JLabel detailLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel statusLabel = new JLabel("Press Start and play a string.");
    private final TuningMeter meter = new TuningMeter();
    private final JPanel stringStrip = new JPanel(new GridLayout(1, 0, 8, 0));
    private final List<JLabel> stringLabels = new ArrayList<>();

    private volatile boolean listening;
    private volatile TargetDataLine audioLine;
    private Thread captureThread;
    private Runnable onClose;

    /** Builds the tuner panel. Opens no audio device. */
    public TunerPanel() {
        super(new BorderLayout(0, 8));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        setBackground(BACKDROP);
        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));

        tuningBox.setSelectedItem(Tuning.GUITAR_STANDARD);
        tuningBox.addActionListener(e -> onTuningChanged());
        startStopButton.addActionListener(e -> toggleListening());
        closeButton.addActionListener(e -> close());

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildStatus(), BorderLayout.SOUTH);

        meter.setToleranceCents(TOLERANCE_CENTS);
        noteLabel.setFont(noteLabel.getFont().deriveFont(Font.BOLD, 76f));
        noteLabel.setForeground(TEXT);
        detailLabel.setForeground(TEXT_DIM);

        rebuildStringStrip();
        updateButtons();
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private Component buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setOpaque(false);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        left.setOpaque(false);
        JLabel tuningLabel = new JLabel("Tuning:");
        tuningLabel.setForeground(TEXT_DIM);
        left.add(tuningLabel);
        left.add(tuningBox);
        left.add(startStopButton);
        bar.add(left, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 4));
        right.setOpaque(false);
        right.add(closeButton);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private Component buildCenter() {
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setOpaque(false);

        JPanel noteCard = new JPanel(new BorderLayout());
        noteCard.setBackground(CARD);
        noteCard.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        noteCard.add(noteLabel, BorderLayout.CENTER);
        noteCard.add(detailLabel, BorderLayout.SOUTH);
        noteCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
        center.add(noteCard);

        center.add(javax.swing.Box.createVerticalStrut(10));

        meter.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        meter.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(meter);

        center.add(javax.swing.Box.createVerticalStrut(10));

        stringStrip.setOpaque(false);
        stringStrip.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        stringStrip.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(stringStrip);
        return center;
    }

    private Component buildStatus() {
        statusLabel.setForeground(TEXT_DIM);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 2));
        return statusLabel;
    }

    /** Rebuilds the string indicator strip for the selected tuning. */
    private void rebuildStringStrip() {
        stringStrip.removeAll();
        stringLabels.clear();
        Tuning tuning = selectedTuning();
        if (tuning.getStringCount() == 0) {
            JLabel chromatic = new JLabel("Chromatic - nearest note", SwingConstants.CENTER);
            chromatic.setForeground(TEXT_DIM);
            stringStrip.add(chromatic);
        } else {
            for (int i = 0; i < tuning.getStringCount(); i++) {
                JLabel label = new JLabel(tuning.getStringNote(i).getLabel(),
                        SwingConstants.CENTER);
                label.setOpaque(true);
                label.setBackground(STRING_IDLE_BG);
                label.setForeground(TEXT_DIM);
                label.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(BACKDROP, 2),
                        BorderFactory.createEmptyBorder(8, 0, 8, 0)));
                stringStrip.add(label);
                stringLabels.add(label);
            }
        }
        stringStrip.revalidate();
        stringStrip.repaint();
    }

    private void highlightString(int index) {
        for (int i = 0; i < stringLabels.size(); i++) {
            JLabel label = stringLabels.get(i);
            boolean active = (i == index);
            label.setBackground(active ? STRING_ACTIVE_BG : STRING_IDLE_BG);
            label.setForeground(active ? BACKDROP : TEXT_DIM);
        }
    }

    // ------------------------------------------------------------------
    // Capture lifecycle
    // ------------------------------------------------------------------

    private void toggleListening() {
        if (listening) {
            stopListening();
        } else {
            startListening();
        }
    }

    /**
     * Opens the default microphone and starts the analysis thread. A failure
     * (no device, device busy, headless host with no audio) is surfaced in the
     * status line, never thrown, so the panel stays usable.
     */
    void startListening() {
        if (listening) {
            return;
        }
        TargetDataLine line = null;
        AudioFormat format = null;
        LineUnavailableException lastError = null;
        for (int rate : CANDIDATE_RATES) {
            AudioFormat candidate = micFormat(rate);
            try {
                line = AudioSystem.getTargetDataLine(candidate);
                line.open(candidate);
                format = candidate;
                break;
            } catch (IllegalArgumentException | LineUnavailableException e) {
                lastError = (e instanceof LineUnavailableException)
                        ? (LineUnavailableException) e : null;
                line = null;
            }
        }
        if (line == null || format == null) {
            setStatus("No microphone available"
                    + (lastError != null ? " (" + lastError.getMessage() + ")." : "."));
            return;
        }
        final TargetDataLine captureLine = line;
        try {
            captureLine.flush();
            captureLine.start();
            audioLine = captureLine;
            listening = true;
            final int sampleRate = (int) format.getSampleRate();
            captureThread = new Thread(() -> captureLoop(captureLine, sampleRate),
                    "lg3d-tuner-capture");
            captureThread.setDaemon(true);
            captureThread.start();
            startStopButton.setText("Stop");
            setStatus("Listening... play a string.");
        } catch (RuntimeException e) {
            audioLine = null;
            listening = false;
            captureLine.close();
            setStatus("Could not start the microphone (" + e.getMessage() + ").");
        }
        updateButtons();
    }

    /** Stops the analysis thread and releases the microphone. Safe when idle. */
    void stopListening() {
        listening = false;
        TargetDataLine line = audioLine;
        audioLine = null;
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (RuntimeException e) {
                // Releasing an already-closed line is not fatal.
            }
        }
        joinCaptureThread();
        captureThread = null;
        startStopButton.setText("Start");
        meter.reset();
        noteLabel.setText("--");
        noteLabel.setForeground(TEXT);
        detailLabel.setText(" ");
        highlightString(-1);
        setStatus("Stopped.");
        updateButtons();
    }

    private void joinCaptureThread() {
        Thread t = captureThread;
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void captureLoop(TargetDataLine line, int sampleRate) {
        PitchDetector detector = new PitchDetector(sampleRate);
        byte[] bytes = new byte[BUFFER_SAMPLES * 2];
        float[] samples = new float[BUFFER_SAMPLES];
        while (listening) {
            int read;
            try {
                read = line.read(bytes, 0, bytes.length);
            } catch (RuntimeException e) {
                // The line was closed under us (Stop / Close) or the device
                // vanished; end the loop quietly instead of dying loudly.
                break;
            }
            if (read <= 0) {
                if (!listening) {
                    break;
                }
                continue;
            }
            int n = read / 2;
            for (int i = 0; i < n; i++) {
                int low = bytes[2 * i] & 0xFF;
                int high = bytes[2 * i + 1];
                short pcm = (short) (low | (high << 8));
                samples[i] = pcm / 32768.0f;
            }
            for (int i = n; i < BUFFER_SAMPLES; i++) {
                samples[i] = 0f;
            }
            final PitchDetector.Pitch pitch = detector.detect(samples);
            SwingUtilities.invokeLater(() -> applyReading(pitch));
        }
    }

    // ------------------------------------------------------------------
    // Reading -> UI (EDT); also the headless test seam
    // ------------------------------------------------------------------

    /**
     * Applies one detection to the meter, note label and string strip. Runs on
     * the EDT. This is the seam the tests drive directly (no microphone): a
     * voiced frequency resolves to the nearest note, the nearest string of the
     * selected tuning, and the deviation in cents.
     *
     * @param pitch the detection result (may be null or unvoiced)
     */
    void applyReading(PitchDetector.Pitch pitch) {
        if (pitch == null || !pitch.isVoiced()) {
            meter.setReading(0f, false);
            noteLabel.setText("--");
            noteLabel.setForeground(TEXT);
            detailLabel.setText(" ");
            highlightString(-1);
            if (listening) {
                setStatus("Listening... play a string.");
            }
            return;
        }
        float frequency = pitch.getFrequency();
        Tuning tuning = selectedTuning();
        Note detected = Note.closestTo(frequency);
        float cents;
        int stringIndex;
        String detail;
        if (tuning.getStringCount() > 0) {
            stringIndex = tuning.closestString(frequency);
            Note target = tuning.getStringNote(stringIndex);
            cents = tuning.centsOff(frequency);
            detail = String.format("String %d \u00b7 target %s \u00b7 %.1f Hz",
                    stringIndex + 1, target.getLabel(),
                    tuning.getStringFrequency(stringIndex));
        } else {
            stringIndex = -1;
            cents = (detected != null) ? detected.getCents() : 0f;
            detail = String.format("Nearest note %s \u00b7 %.1f Hz",
                    (detected != null) ? detected.getLabel() : "--",
                    (detected != null) ? detected.getReferenceFrequency() : 0f);
        }
        boolean inTune = Math.abs(cents) <= TOLERANCE_CENTS;

        meter.setReading(cents, true);
        noteLabel.setText(detected != null ? detected.getLabel() : "--");
        noteLabel.setForeground(inTune ? GREEN : TEXT);
        detailLabel.setText(detail);
        highlightString(stringIndex);
        setStatus(String.format("%.1f Hz  \u00b7  %+.0f cents  \u00b7  clarity %.0f%%",
                frequency, cents, pitch.getClarity() * 100f));
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private void onTuningChanged() {
        rebuildStringStrip();
        meter.reset();
        noteLabel.setText("--");
        noteLabel.setForeground(TEXT);
        detailLabel.setText(" ");
        setStatus(listening ? "Listening... play a string."
                : "Press Start and play a string.");
    }

    private void updateButtons() {
        startStopButton.setEnabled(true);
        tuningBox.setEnabled(!listening);
    }

    private void setStatus(String text) {
        if (SwingUtilities.isEventDispatchThread()) {
            statusLabel.setText(text);
        } else {
            SwingUtilities.invokeLater(() -> statusLabel.setText(text));
        }
    }

    /** The currently selected tuning (never null). */
    Tuning selectedTuning() {
        Tuning tuning = (Tuning) tuningBox.getSelectedItem();
        return (tuning != null) ? tuning : Tuning.GUITAR_STANDARD;
    }

    /** Whether the microphone analysis is running. */
    boolean isListening() {
        return listening;
    }

    /** The current status-line text (test hook). */
    String statusText() {
        return statusLabel.getText();
    }

    /** The detected-note label text (test hook). */
    String noteText() {
        return noteLabel.getText();
    }

    /** The target/detail caption under the note (test hook). */
    String detailText() {
        return detailLabel.getText();
    }

    /** The live meter (test hook). */
    TuningMeter meter() {
        return meter;
    }

    /** The tuning selector (test hook). */
    JComboBox<Tuning> tuningBox() {
        return tuningBox;
    }

    /** Registers the callback invoked when the hosting window closes. */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /** Invoked by the host when the window is closing; stops any live capture. */
    void close() {
        stopListening();
        if (onClose != null) {
            onClose.run();
        }
    }

    /**
     * The 16-bit signed little-endian mono PCM format the capture line is opened
     * with. Building an {@link AudioFormat} touches no device, so this is safe to
     * call (and test) headless.
     *
     * @param sampleRate the capture rate in Hz
     * @return the linear-PCM mono format
     */
    static AudioFormat micFormat(int sampleRate) {
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                sampleRate, 16, 1, 2, sampleRate, false);
    }
}
