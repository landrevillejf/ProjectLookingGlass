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
package org.jdesktop.lg3d.apps.recorder;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.TargetDataLine;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The recorder's user interface: an Audio tab that captures the microphone
 * natively to WAV, a Screen tab that captures the X11 display through an external
 * {@code ffmpeg}/{@code avconv}, and a history list of finished captures on the
 * right. One panel serves both the 3D desktop (hosted on a SwingNode inside a
 * Frame3D by the {@code Recorder} wrapper) and the 2D/Swing desktop (opened as an
 * MDI internal frame via {@code Desktop2DAppRegistry.PANEL_APPS}).
 *
 * <p>Audio recording is genuinely native ({@code javax.sound.sampled}); screen
 * recording has no in-JDK encoder, so it follows the honest split described on
 * {@link RecorderBackend} and hands the grab to a real external recorder. No
 * capture device is opened, no process started and no dialog shown until the user
 * presses a Record button, so the panel constructs and is asserted on headless -
 * the {@link RecorderBackend} / {@link RecordingSettings} logic behind it is pure
 * and unit-testable.</p>
 */
public class RecorderPanel extends JPanel {

    /** Preferred width in pixels. */
    public static final int WIDTH_PX = 840;
    /** Preferred height in pixels. */
    public static final int HEIGHT_PX = 560;

    private static final Integer[] RATES = {8000, 16000, 22050, 44100, 48000, 96000};
    private static final String[] CHANNELS = {"Mono", "Stereo"};

    private final RecorderStore store;
    private final List<Recording> history = new ArrayList<>();
    private final RecordingSettings settings;

    private final DefaultListModel<Recording> historyModel = new DefaultListModel<>();
    private final JList<Recording> historyList = new JList<>(historyModel);
    private final JLabel statusLabel = new JLabel("Ready");

    // Audio tab
    private final JComboBox<Integer> rateBox = new JComboBox<>(RATES);
    private final JComboBox<String> channelBox = new JComboBox<>(CHANNELS);
    private final JButton audioRecordBtn = new JButton("Record");
    private final JButton audioStopBtn = new JButton("Stop");
    private final JLabel audioElapsed = new JLabel("00:00");

    // Screen tab
    private final JTextField sourceField = new JTextField(RecorderBackend.DEFAULT_SOURCE, 8);
    private final JTextField resolutionField = new JTextField(10);
    private final JSpinner fpsSpinner = new JSpinner(new SpinnerNumberModel(
            RecorderBackend.DEFAULT_FPS, 1, 120, 1));
    private final JCheckBox audioWithScreen = new JCheckBox("Record microphone too");
    private final JComboBox<String> recorderBox = new JComboBox<>();
    private final JButton screenRecordBtn = new JButton("Record Screen");
    private final JButton screenStopBtn = new JButton("Stop");
    private final JLabel screenElapsed = new JLabel("00:00");

    private Runnable onClose;
    private Timer elapsedTimer;
    private long captureStartMillis;
    private volatile boolean recording;
    private volatile TargetDataLine audioLine;
    private volatile Process screenProcess;
    private Thread captureThread;
    private File lastAudioFile;
    private File lastVideoFile;

    /** Builds the panel with the default store. */
    public RecorderPanel() {
        this(new RecorderStore());
    }

    /**
     * Builds the panel over an explicit store (package-private for tests).
     *
     * @param store the settings / history store
     */
    RecorderPanel(RecorderStore store) {
        this.store = store;
        this.settings = store.loadSettings();

        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildTabs(), BorderLayout.CENTER);
        add(buildHistoryPane(), BorderLayout.EAST);
        add(buildBottomPane(), BorderLayout.SOUTH);

        applySettingsToWidgets();
        history.addAll(store.loadHistory());
        refreshHistory();
        wireListeners();
        refreshRecorderBox();
        updateButtons();
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private Component buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        bar.setBorder(BorderFactory.createTitledBorder("Output folder"));
        JLabel dir = new JLabel("Captures save to:");
        bar.add(dir);
        JTextField field = new JTextField(outputDirLabel(), 28);
        field.setEditable(false);
        field.setName("outputDirField");
        bar.add(field);
        JButton choose = new JButton("Change...");
        choose.addActionListener(e -> chooseOutputDir());
        bar.add(choose);
        return bar;
    }

    private Component buildTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Audio", buildAudioTab());
        tabs.addTab("Screen", buildScreenTab());
        return tabs;
    }

    private Component buildAudioTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel format = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        format.setBorder(BorderFactory.createTitledBorder("Capture format"));
        format.add(new JLabel("Sample rate:"));
        format.add(rateBox);
        format.add(new JLabel("Channels:"));
        format.add(channelBox);
        panel.add(format);

        JPanel transport = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 12));
        transport.add(audioRecordBtn);
        transport.add(audioStopBtn);
        panel.add(transport);

        audioElapsed.setFont(audioElapsed.getFont().deriveFont(28f));
        audioElapsed.setHorizontalAlignment(JLabel.CENTER);
        audioElapsed.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(audioElapsed);

        JLabel note = new JLabel("<html><i>Microphone audio is recorded natively "
                + "to a WAV file - no external tool needed.</i></html>");
        note.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(note);
        return panel;
    }

    private Component buildScreenTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel source = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        source.setBorder(BorderFactory.createTitledBorder("Screen source"));
        source.add(new JLabel("Display:"));
        source.add(sourceField);
        source.add(new JLabel("Size (WxH, blank = full):"));
        source.add(resolutionField);
        source.add(new JLabel("FPS:"));
        source.add(fpsSpinner);
        source.add(audioWithScreen);
        panel.add(source);

        JPanel rec = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        rec.add(new JLabel("Recorder:"));
        recorderBox.setPreferredSize(new Dimension(140, 26));
        rec.add(recorderBox);
        panel.add(rec);

        JPanel transport = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 12));
        transport.add(screenRecordBtn);
        transport.add(screenStopBtn);
        panel.add(transport);

        screenElapsed.setFont(screenElapsed.getFont().deriveFont(28f));
        screenElapsed.setHorizontalAlignment(JLabel.CENTER);
        screenElapsed.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(screenElapsed);

        JLabel note = new JLabel("<html><i>The JDK has no screen encoder, so the "
                + "grab is handed to ffmpeg (or avconv) writing an MP4.</i></html>");
        note.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(note);
        return panel;
    }

    private Component buildHistoryPane() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("History"));
        panel.setPreferredSize(new Dimension(230, 100));
        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.add(new JScrollPane(historyList), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 2));
        buttons.add(action("Remove", this::removeSelectedHistory));
        buttons.add(action("Clear", this::clearHistory));
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private Component buildBottomPane() {
        JPanel panel = new JPanel(new BorderLayout(6, 4));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        JButton close = new JButton("Close");
        close.addActionListener(e -> {
            stopRecording();
            if (onClose != null) {
                onClose.run();
            }
        });
        right.add(close);
        panel.add(right, BorderLayout.NORTH);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        panel.add(statusLabel, BorderLayout.SOUTH);
        return panel;
    }

    private JButton action(String label, Runnable handler) {
        JButton button = new JButton(label);
        button.addActionListener(e -> handler.run());
        return button;
    }

    private void applySettingsToWidgets() {
        rateBox.setSelectedItem(settings.getSampleRate());
        channelBox.setSelectedIndex(settings.getChannels() <= 1 ? 0 : 1);
        sourceField.setText(settings.getScreenSource());
        resolutionField.setText(settings.getResolution());
        fpsSpinner.setValue(settings.getFps());
        audioWithScreen.setSelected(settings.isCaptureAudioWithScreen());
    }

    private void refreshRecorderBox() {
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        model.addElement("Auto-detect");
        for (String recorder : RecorderBackend.KNOWN_RECORDERS) {
            model.addElement(recorder);
        }
        recorderBox.setModel(model);
        String preferred = settings.getPreferredRecorder();
        recorderBox.setSelectedItem(
                (preferred == null || preferred.isBlank()) ? "Auto-detect" : preferred);
    }

    private void wireListeners() {
        audioRecordBtn.addActionListener(e -> startAudio());
        audioStopBtn.addActionListener(e -> stopRecording());
        screenRecordBtn.addActionListener(e -> startScreen());
        screenStopBtn.addActionListener(e -> stopRecording());
        rateBox.addActionListener(e -> readFormatSettings());
        channelBox.addActionListener(e -> readFormatSettings());
        sourceField.addActionListener(e -> readScreenSettings());
        resolutionField.addActionListener(e -> readScreenSettings());
        fpsSpinner.addChangeListener(e -> readScreenSettings());
        audioWithScreen.addActionListener(e -> readScreenSettings());
        recorderBox.addActionListener(e -> {
            Object sel = recorderBox.getSelectedItem();
            settings.setPreferredRecorder(
                    ("Auto-detect".equals(sel) || sel == null) ? "" : sel.toString());
            persist();
        });
    }

    private void readFormatSettings() {
        Object rate = rateBox.getSelectedItem();
        if (rate instanceof Integer) {
            settings.setSampleRate((Integer) rate);
        }
        settings.setChannels(channelBox.getSelectedIndex() == 0 ? 1 : 2);
        persist();
    }

    private void readScreenSettings() {
        settings.setScreenSource(sourceField.getText());
        settings.setResolution(resolutionField.getText());
        settings.setFps((Integer) fpsSpinner.getValue());
        settings.setCaptureAudioWithScreen(audioWithScreen.isSelected());
        persist();
    }

    // ------------------------------------------------------------------
    // Output folder
    // ------------------------------------------------------------------

    private void chooseOutputDir() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            settings.setOutputDir(chooser.getSelectedFile().getAbsolutePath());
            persist();
            setStatus("Captures save to " + settings.getOutputDir());
        }
    }

    private String outputDirLabel() {
        String dir = settings.getOutputDir();
        return (dir == null || dir.isBlank())
                ? System.getProperty("user.home") + File.separator + "Recordings" : dir;
    }

    /**
     * Resolves (and creates) the capture folder and returns {@code fileName}
     * inside it. Package-visible so a test can verify the default-folder
     * fallback without pressing Record.
     *
     * @param fileName the file name to place in the output folder
     * @return the target file
     */
    File resolveOutput(String fileName) {
        String dir = settings.getOutputDir();
        File folder = (dir == null || dir.isBlank())
                ? new File(System.getProperty("user.home"), "Recordings")
                : new File(dir);
        if (!folder.isDirectory()) {
            folder.mkdirs();
        }
        return new File(folder, fileName);
    }

    // ------------------------------------------------------------------
    // Audio capture (native)
    // ------------------------------------------------------------------

    private void startAudio() {
        if (recording) {
            setStatus("Already recording - stop the current capture first.");
            return;
        }
        readFormatSettings();
        AudioFormat format =
                RecorderBackend.wavFormat(settings.getSampleRate(), settings.getChannels());
        try {
            TargetDataLine line = AudioSystem.getTargetDataLine(format);
            line.open(format);
            line.start();
            audioLine = line;
            lastAudioFile = resolveOutput(RecorderBackend.defaultFileName("audio", "wav"));
            recording = true;
            captureStartMillis = System.currentTimeMillis();
            captureThread = new Thread(() -> writeWav(line, lastAudioFile),
                    "lg3d-audio-capture");
            captureThread.setDaemon(true);
            captureThread.start();
            startElapsed(audioElapsed);
            updateButtons();
            setStatus("Recording audio to " + lastAudioFile.getName());
        } catch (RuntimeException | javax.sound.sampled.LineUnavailableException e) {
            audioLine = null;
            recording = false;
            setStatus("Could not open the microphone (" + e.getMessage() + ").");
        }
    }

    private void writeWav(TargetDataLine line, File out) {
        try (AudioInputStream stream = new AudioInputStream(line)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out);
        } catch (IOException | RuntimeException e) {
            setStatus("Audio write failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Screen capture (external ffmpeg)
    // ------------------------------------------------------------------

    private void startScreen() {
        if (recording) {
            setStatus("Already recording - stop the current capture first.");
            return;
        }
        readScreenSettings();
        Optional<String> recorder = RecorderBackend.resolveRecorder(
                settings.getPreferredRecorder(), RecorderPanel::onPath);
        if (recorder.isEmpty()) {
            setStatus("No screen recorder found. Install ffmpeg to record the screen.");
            return;
        }
        lastVideoFile = resolveOutput(RecorderBackend.defaultFileName("screen", "mp4"));
        List<String> command = RecorderBackend.screenCommand(recorder.get(),
                lastVideoFile.getAbsolutePath(), settings.getScreenSource(),
                settings.getResolution(), settings.getFps(),
                settings.isCaptureAudioWithScreen());
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            String display = System.getProperty("lg.lgserverdisplay",
                    System.getenv("DISPLAY"));
            if (display != null) {
                pb.environment().put("DISPLAY", display);
            }
            screenProcess = pb.start();
            recording = true;
            captureStartMillis = System.currentTimeMillis();
            startElapsed(screenElapsed);
            updateButtons();
            setStatus("Recording screen via " + recorder.get() + " to "
                    + lastVideoFile.getName());
        } catch (IOException | RuntimeException ex) {
            screenProcess = null;
            recording = false;
            setStatus("Could not start " + recorder.get() + " (" + ex.getMessage() + ").");
        }
    }

    /** Stops whichever capture is running (audio line or screen process). */
    void stopRecording() {
        if (!recording) {
            return;
        }
        long duration = System.currentTimeMillis() - captureStartMillis;
        recording = false;
        stopElapsed();

        TargetDataLine line = audioLine;
        audioLine = null;
        if (line != null) {
            line.stop();
            line.close();
            joinCaptureThread();
            addRecording(finished(Recording.audio(pathOf(lastAudioFile)), duration));
            setStatus("Saved audio capture (" + describe(duration) + ").");
        }

        Process process = screenProcess;
        screenProcess = null;
        if (process != null) {
            quitGracefully(process);
            addRecording(finished(Recording.video(pathOf(lastVideoFile)), duration));
            setStatus("Saved screen capture (" + describe(duration) + ").");
        }
        updateButtons();
    }

    private void joinCaptureThread() {
        Thread t = captureThread;
        captureThread = null;
        if (t != null) {
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Asks ffmpeg to finalise the file with 'q', then destroys it if needed. */
    private void quitGracefully(Process process) {
        try {
            OutputStream in = process.getOutputStream();
            in.write("q".getBytes(StandardCharsets.US_ASCII));
            in.flush();
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroy();
            }
        } catch (IOException | RuntimeException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            process.destroy();
        }
    }

    private Recording finished(Recording r, long durationMillis) {
        r.setDurationMillis(durationMillis);
        return r;
    }

    private static String pathOf(File file) {
        return (file == null) ? "" : file.getAbsolutePath();
    }

    private static String describe(long millis) {
        long s = Math.max(0, millis / 1000);
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    // ------------------------------------------------------------------
    // Elapsed timer
    // ------------------------------------------------------------------

    private void startElapsed(JLabel label) {
        stopElapsed();
        label.setText("00:00");
        elapsedTimer = new Timer(1000, e -> {
            long s = (System.currentTimeMillis() - captureStartMillis) / 1000;
            label.setText(String.format("%02d:%02d", s / 60, s % 60));
        });
        elapsedTimer.start();
    }

    private void stopElapsed() {
        if (elapsedTimer != null) {
            elapsedTimer.stop();
            elapsedTimer = null;
        }
    }

    private void updateButtons() {
        audioRecordBtn.setEnabled(!recording);
        screenRecordBtn.setEnabled(!recording);
        audioStopBtn.setEnabled(recording);
        screenStopBtn.setEnabled(recording);
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    private void removeSelectedHistory() {
        int i = historyList.getSelectedIndex();
        if (i >= 0 && i < history.size()) {
            history.remove(i);
            refreshHistory();
            persist();
        }
    }

    private void clearHistory() {
        history.clear();
        refreshHistory();
        persist();
        setStatus("History cleared.");
    }

    /**
     * Adds a finished capture to the history and persists it. Package-visible so
     * a test can grow the history without opening a capture device.
     *
     * @param item the recording to add
     */
    void addRecording(Recording item) {
        if (item != null) {
            history.add(item);
            refreshHistory();
            persist();
        }
    }

    private void refreshHistory() {
        historyModel.clear();
        for (int i = history.size() - 1; i >= 0; i--) {
            historyModel.addElement(history.get(i));
        }
    }

    private void persist() {
        store.saveSettings(settings);
        store.saveHistory(new ArrayList<>(history));
    }

    /** True when {@code exe} is found on the PATH. */
    private static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (!dir.isBlank() && new File(dir, exe).canExecute()) {
                return true;
            }
        }
        return false;
    }

    private void setStatus(String text) {
        final String message = text;
        if (SwingUtilities.isEventDispatchThread()) {
            statusLabel.setText(message);
        } else {
            SwingUtilities.invokeLater(() -> statusLabel.setText(message));
        }
    }

    /** Wires the panel's Close button (used by both desktop hosts). */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    // ------------------------------------------------------------------
    // Test hooks
    // ------------------------------------------------------------------

    /** The number of history rows shown (newest first). */
    int historySize() {
        return historyModel.size();
    }

    /** An unmodifiable view of the history, oldest first. */
    List<Recording> history() {
        return List.copyOf(history);
    }

    /** True while an audio or screen capture is running. */
    boolean isRecording() {
        return recording;
    }

    /** The current status text. */
    String statusText() {
        return statusLabel.getText();
    }

    /** The live settings bean (package-visible for tests). */
    RecordingSettings settings() {
        return settings;
    }
}
