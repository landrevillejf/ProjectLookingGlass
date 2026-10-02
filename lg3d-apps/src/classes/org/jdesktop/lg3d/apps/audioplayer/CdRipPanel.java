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
package org.jdesktop.lg3d.apps.audioplayer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

/**
 * The Audio Player's "Rip CD" tab: it reads the disc's table of contents, looks
 * the album up in the CD database (MusicBrainz) to prefill artist / album / track
 * names and fetch the cover art, then rips the selected tracks to MP3 or WAV at a
 * chosen sampling rate.
 *
 * <p>Following the desktop's honest external-tool split ({@link CdRipBackend},
 * {@link AudioBackend}, {@link RecorderBackend}), no codec is bundled and no
 * device is touched until the user acts: reading the TOC and ripping both drive
 * real tools ({@code cdparanoia} to extract, {@code ffmpeg}/{@code lame} to
 * encode) over a guarded {@link ProcessBuilder}, and when a tool is missing the
 * status line says so plainly instead of failing silently. All decisions
 * (commands, formats, rates, metadata parsing) live in the pure, unit-tested
 * seams; this panel is the thin EDT/worker glue, so it constructs headless and
 * only launches processes on a button press.</p>
 *
 * <p>Ripped tracks are handed back through {@code onRipped} so the host panel can
 * add them to the shared library and persist them.</p>
 */
public class CdRipPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final String[] FORMATS = {"MP3", "WAV"};

    private final AudioPlayerStore store;
    private final RipSettings settings;
    private final Consumer<List<MediaItem>> onRipped;

    private final JTextField deviceField = new JTextField(12);
    private final JTextField outputField = new JTextField(24);
    private final JTextField artistField = new JTextField(18);
    private final JTextField albumField = new JTextField(18);

    private final JList<String> formatList = new JList<>(FORMATS);
    private final JList<Integer> rateList = new JList<>(boxed(CdRipBackend.SAMPLE_RATES));
    private final JList<Integer> bitrateList = new JList<>(boxed(CdRipBackend.MP3_BITRATES));

    private final DefaultListModel<String> trackModel = new DefaultListModel<>();
    private final JList<String> trackList = new JList<>(trackModel);

    private final JButton readBtn = new JButton("Read Disc");
    private final JButton ripBtn = new JButton("Rip");
    private final JButton selectAllBtn = new JButton("All");
    private final JButton selectNoneBtn = new JButton("None");
    private final JLabel statusLabel = new JLabel("Insert an audio CD, then Read Disc.");
    private final JProgressBar progress = new JProgressBar(0, 100);

    /** Track numbers currently shown in {@link #trackList}, index-aligned. */
    private final List<Integer> trackNumbers = new ArrayList<>();

    private volatile Toc currentToc;
    private volatile String currentMbid = "";
    private volatile String coverPath = "";
    private volatile Process currentProcess;
    private volatile boolean busy;

    /**
     * Builds the rip tab.
     *
     * @param store    the library / settings store
     * @param onRipped receives the freshly ripped, tagged tracks (on the EDT)
     */
    public CdRipPanel(AudioPlayerStore store, Consumer<List<MediaItem>> onRipped) {
        this.store = store;
        this.onRipped = onRipped;
        this.settings = store.loadRipSettings();

        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(buildOptions(), BorderLayout.NORTH);
        add(buildTracks(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        applySettingsToWidgets();
        wireListeners();
        ripBtn.setEnabled(false);
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private JPanel buildOptions() {
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));

        JPanel device = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        device.setBorder(BorderFactory.createTitledBorder("Source & destination"));
        device.add(new JLabel("CD device:"));
        device.add(deviceField);
        JButton detect = new JButton("Detect");
        detect.addActionListener(e -> detectDevice());
        device.add(detect);
        device.add(new JLabel("  Rip to:"));
        outputField.setEditable(false);
        device.add(outputField);
        JButton choose = new JButton("Change...");
        choose.addActionListener(e -> chooseOutputDir());
        device.add(choose);
        panel.add(device);

        JPanel format = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        format.setBorder(BorderFactory.createTitledBorder("Output format"));
        format.add(labelled("Format:", formatList, 2));
        format.add(labelled("Sample rate:", rateList, 4));
        format.add(labelled("MP3 bitrate:", bitrateList, 4));
        panel.add(format);

        JPanel meta = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        meta.setBorder(BorderFactory.createTitledBorder("Album metadata (from the CD database)"));
        meta.add(new JLabel("Artist:"));
        meta.add(artistField);
        meta.add(new JLabel("Album:"));
        meta.add(albumField);
        panel.add(meta);
        return panel;
    }

    private <T> JScrollPane labelled(String title, JList<T> list, int rows) {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(rows);
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createTitledBorder(title));
        sp.setPreferredSize(new Dimension(110, rows * 18 + 22));
        return sp;
    }

    private JPanel buildTracks() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Tracks"));
        trackModel.clear();
        trackList.setVisibleRowCount(8);
        panel.add(new JScrollPane(trackList), BorderLayout.CENTER);
        JPanel side = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 4));
        side.add(selectAllBtn);
        side.add(selectNoneBtn);
        panel.add(side, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildBottom() {
        JPanel panel = new JPanel(new BorderLayout(6, 4));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        left.add(readBtn);
        left.add(ripBtn);
        panel.add(left, BorderLayout.WEST);
        progress.setStringPainted(true);
        progress.setPreferredSize(new Dimension(200, 20));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        right.add(progress);
        panel.add(right, BorderLayout.EAST);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(panel, BorderLayout.NORTH);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        wrap.add(statusLabel, BorderLayout.SOUTH);
        return wrap;
    }

    private void wireListeners() {
        readBtn.addActionListener(e -> readDisc());
        ripBtn.addActionListener(e -> rip());
        selectAllBtn.addActionListener(e -> selectRange(0, trackModel.size()));
        selectNoneBtn.addActionListener(e -> trackList.clearSelection());
        formatList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                settings.setFormat(currentFormat());
                persistSettings();
            }
        });
        rateList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                settings.setSampleRate(nz(rateList.getSelectedValue(),
                        CdRipBackend.DEFAULT_SAMPLE_RATE));
                persistSettings();
            }
        });
        bitrateList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                settings.setMp3Bitrate(nz(bitrateList.getSelectedValue(),
                        CdRipBackend.DEFAULT_BITRATE));
                persistSettings();
            }
        });
    }

    private void applySettingsToWidgets() {
        deviceField.setText(settings.getDevice());
        outputField.setText(settings.getOutputDir());
        formatList.setSelectedValue(settings.getFormat() == CdRipBackend.Format.WAV
                ? "WAV" : "MP3", true);
        rateList.setSelectedValue(settings.getSampleRate(), true);
        bitrateList.setSelectedValue(settings.getMp3Bitrate(), true);
        if (deviceField.getText().isBlank()) {
            detectDevice();
        }
    }

    // ------------------------------------------------------------------
    // Device / directory
    // ------------------------------------------------------------------

    private void detectDevice() {
        Optional<String> dev = CdRipBackend.defaultDevice();
        deviceField.setText(dev.orElse(""));
        settings.setDevice(deviceField.getText());
        persistSettings();
        if (dev.isEmpty()) {
            setStatus("No CD device found at the usual nodes; type it manually.");
        }
    }

    private void chooseOutputDir() {
        JFileChooser chooser = new JFileChooser(settings.getOutputDir());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            settings.setOutputDir(chooser.getSelectedFile().getAbsolutePath());
            outputField.setText(settings.getOutputDir());
            persistSettings();
        }
    }

    // ------------------------------------------------------------------
    // Read disc (TOC + CDDB lookup + cover)
    // ------------------------------------------------------------------

    private void readDisc() {
        if (busy) {
            return;
        }
        Optional<String> ripper = CdRipBackend.resolveRipper(
                settings.getPreferredRipper(), CdRipPanel::onPath);
        if (ripper.isEmpty()) {
            setStatus("No CD reader found. Install cdparanoia (or cd-info) to read the disc.");
            return;
        }
        String device = deviceField.getText().trim();
        busy = true;
        readBtn.setEnabled(false);
        setStatus("Reading disc TOC via " + ripper.get() + "...");
        Thread worker = new Thread(() -> doReadDisc(ripper.get(), device), "cd-read-disc");
        worker.setDaemon(true);
        worker.start();
    }

    private void doReadDisc(String ripper, String device) {
        try {
            List<String> cmd = CdRipBackend.readTocCommand(ripper, device);
            CommandResult res = runCommand(cmd, 30);
            Toc toc = TocParser.parse(res.output);
            if (toc.isEmpty()) {
                SwingUtilities.invokeLater(() -> {
                    busy = false;
                    readBtn.setEnabled(true);
                    setStatus("Could not read an audio CD TOC (no disc, or a data-only disc).");
                });
                return;
            }
            currentToc = toc;
            String discid = MusicBrainzDiscId.compute(toc);
            SwingUtilities.invokeLater(() -> setStatus(
                    "Looking up disc " + discid + " in MusicBrainz..."));
            AudioCdDb.Release release = AudioCdDb.lookup(discid);

            // Fetch (and cache) the album cover when the release matched.
            String mbid = release.mbid;
            String cover = "";
            if (!mbid.isBlank()) {
                Path dest = store.coverFile(mbid);
                if (Files.isRegularFile(dest)
                        || AudioCdDb.fetchCover(mbid, dest)) {
                    cover = dest.toAbsolutePath().toString();
                }
            }
            currentMbid = mbid;
            coverPath = cover;

            final AudioCdDb.Release rel = release;
            final String coverFinal = cover;
            SwingUtilities.invokeLater(() -> populateTracks(toc, rel, coverFinal));
        } catch (RuntimeException ex) {
            SwingUtilities.invokeLater(() -> {
                busy = false;
                readBtn.setEnabled(true);
                setStatus("Read failed: " + ex.getMessage());
            });
        }
    }

    private void populateTracks(Toc toc, AudioCdDb.Release release, String cover) {
        trackModel.clear();
        trackNumbers.clear();
        artistField.setText(release.artist);
        albumField.setText(release.title);
        int n = toc.trackCount();
        for (int i = 0; i < n; i++) {
            int number = toc.getTracks().get(i).number;
            String title = releaseTitle(release, i, number);
            trackNumbers.add(number);
            trackModel.addElement(String.format("%2d. %s", number, title));
        }
        trackList.setSelectionInterval(0, Math.max(0, trackModel.size() - 1));
        coverPath = cover;
        currentMbid = release.mbid;
        progress.setValue(0);
        busy = false;
        readBtn.setEnabled(true);
        ripBtn.setEnabled(trackModel.size() > 0);
        setStatus(release.isEmpty()
                ? "Read " + n + " tracks (no database match - names are placeholders)."
                : "Matched \"" + release.title + "\" - " + n + " tracks ready to rip.");
    }

    private static String releaseTitle(AudioCdDb.Release release, int index, int number) {
        for (AudioCdDb.TrackInfo t : release.tracks) {
            if (t.number == number && !t.title.isBlank()) {
                return t.title;
            }
        }
        if (index < release.tracks.size() && !release.tracks.get(index).title.isBlank()) {
            return release.tracks.get(index).title;
        }
        return "Track " + number;
    }

    // ------------------------------------------------------------------
    // Rip
    // ------------------------------------------------------------------

    private void rip() {
        if (busy || currentToc == null || currentToc.isEmpty()) {
            return;
        }
        List<Integer> selected = trackList.getSelectedIndices().length == 0
                ? allTrackNumbers() : selectedTrackNumbers();
        if (selected.isEmpty()) {
            setStatus("Select at least one track to rip.");
            return;
        }
        Optional<String> ripper = CdRipBackend.resolveRipper(
                settings.getPreferredRipper(), CdRipPanel::onPath);
        if (ripper.isEmpty()) {
            setStatus("No CD reader found. Install cdparanoia to rip.");
            return;
        }
        CdRipBackend.Format format = currentFormat();
        int rate = nz(rateList.getSelectedValue(), CdRipBackend.DEFAULT_SAMPLE_RATE);
        boolean needsEncode = format == CdRipBackend.Format.MP3
                || rate != CdRipBackend.DEFAULT_SAMPLE_RATE;
        Optional<String> encoder = needsEncode
                ? CdRipBackend.resolveEncoder(settings.getPreferredEncoder(), CdRipPanel::onPath)
                : Optional.of("(none)");
        if (needsEncode && encoder.isEmpty()) {
            setStatus("Encoding needs ffmpeg or lame - install one to rip to "
                    + format + (rate != CdRipBackend.DEFAULT_SAMPLE_RATE ? " at " + rate + " Hz" : "") + ".");
            return;
        }
        Path outDir = Paths.get(settings.getOutputDir());
        if (store.ensureDir(outDir) == null) {
            setStatus("Cannot create the output folder " + outDir + ".");
            return;
        }

        String artist = artistField.getText().trim();
        String album = albumField.getText().trim();
        String mbid = currentMbid;
        String cover = coverPath;
        String device = deviceField.getText().trim();
        int bitrate = nz(bitrateList.getSelectedValue(), CdRipBackend.DEFAULT_BITRATE);
        busy = true;
        ripBtn.setEnabled(false);
        readBtn.setEnabled(false);
        progress.setMaximum(selected.size());
        progress.setValue(0);

        Thread worker = new Thread(() -> doRip(ripper.get(), encoder.orElse(null),
                selected, format, rate, bitrate, device, outDir,
                artist, album, mbid, cover), "cd-rip");
        worker.setDaemon(true);
        worker.start();
    }

    private void doRip(String ripper, String encoder, List<Integer> tracks,
                       CdRipBackend.Format format, int rate, int bitrate,
                       String device, Path outDir, String artist, String album,
                       String mbid, String cover) {
        List<MediaItem> ripped = new ArrayList<>();
        int done = 0;
        for (int number : tracks) {
            String title = titleFor(number);
            setStatus("Ripping track " + number + " - " + title);
            Path wav = outDir.resolve(String.format("track%02d.wav", number));
            CommandResult ripRes = runCommand(
                    CdRipBackend.ripWavCommand(ripper, device, number, wav.toString()), 600);
            if (!Files.isRegularFile(wav)) {
                SwingUtilities.invokeLater(() -> setStatus(
                        "Rip of track " + number + " failed: " + ripRes.tail()));
                continue;
            }
            Path out = outDir.resolve(fileName(artist, album, number, title, format));
            boolean ok;
            if (format == CdRipBackend.Format.MP3 || rate != CdRipBackend.DEFAULT_SAMPLE_RATE) {
                CommandResult enc = runCommand(CdRipBackend.encodeCommand(
                        encoder, wav.toString(), out.toString(), format, rate, bitrate), 600);
                ok = Files.isRegularFile(out) && out.toFile().length() > 0;
                if (!ok) {
                    SwingUtilities.invokeLater(() -> setStatus(
                            "Encode of track " + number + " failed: " + enc.tail()));
                }
                deleteQuietly(wav);
            } else {
                ok = move(wav, out);
            }
            if (ok) {
                ripped.add(MediaItem.file(out.toAbsolutePath().toString(), title,
                        artist, album, number, mbid, cover));
            }
            done++;
            final int pct = (int) (100L * done / tracks.size());
            final int idx = done;
            SwingUtilities.invokeLater(() -> {
                progress.setValue(idx);
                progress.setString(pct + "%");
            });
        }
        final List<MediaItem> result = ripped;
        SwingUtilities.invokeLater(() -> {
            busy = false;
            ripBtn.setEnabled(true);
            readBtn.setEnabled(true);
            if (result.isEmpty()) {
                setStatus("No tracks were ripped - see the messages above.");
            } else {
                setStatus("Ripped " + result.size() + " track(s) to " + outDir + ".");
                if (onRipped != null) {
                    onRipped.accept(result);
                }
            }
        });
    }

    private String titleFor(int number) {
        int idx = trackNumbers.indexOf(number);
        if (idx >= 0 && idx < trackModel.size()) {
            String label = trackModel.get(idx);
            int dot = label.indexOf(". ");
            return (dot >= 0) ? label.substring(dot + 2).trim() : label.trim();
        }
        return "Track " + number;
    }

    // ------------------------------------------------------------------
    // Process running (thin, guarded)
    // ------------------------------------------------------------------

    /** The captured output and exit code of a finished external command. */
    static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = (output == null) ? "" : output;
        }

        /** The last non-blank line, for a concise error message. */
        String tail() {
            String[] lines = output.split("\\R");
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].isBlank()) {
                    return lines[i].trim();
                }
            }
            return output.isBlank() ? "(no output)" : output.trim();
        }
    }

    private CommandResult runCommand(List<String> command, int timeoutSeconds) {
        if (command == null || command.isEmpty()) {
            return new CommandResult(-1, "no command");
        }
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            String display = System.getProperty("lg.lgserverdisplay", System.getenv("DISPLAY"));
            if (display != null) {
                pb.environment().put("DISPLAY", display);
            }
            proc = pb.start();
            currentProcess = proc;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return new CommandResult(-1, "timed out after " + timeoutSeconds + "s");
            }
            return new CommandResult(proc.exitValue(), sb.toString());
        } catch (IOException | RuntimeException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (proc != null) {
                proc.destroyForcibly();
            }
            return new CommandResult(-1, String.valueOf(ex.getMessage()));
        } finally {
            currentProcess = null;
        }
    }

    /** True when {@code exe} is found on the PATH. */
    static boolean onPath(String exe) {
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

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private CdRipBackend.Format currentFormat() {
        return "WAV".equals(formatList.getSelectedValue())
                ? CdRipBackend.Format.WAV : CdRipBackend.Format.MP3;
    }

    private List<Integer> allTrackNumbers() {
        return new ArrayList<>(trackNumbers);
    }

    private List<Integer> selectedTrackNumbers() {
        List<Integer> out = new ArrayList<>();
        for (int i : trackList.getSelectedIndices()) {
            if (i >= 0 && i < trackNumbers.size()) {
                out.add(trackNumbers.get(i));
            }
        }
        return out;
    }

    private void selectRange(int from, int to) {
        if (to > from) {
            trackList.setSelectionInterval(from, to - 1);
        }
    }

    private void persistSettings() {
        settings.setFormat(currentFormat());
        settings.setSampleRate(nz(rateList.getSelectedValue(), CdRipBackend.DEFAULT_SAMPLE_RATE));
        settings.setMp3Bitrate(nz(bitrateList.getSelectedValue(), CdRipBackend.DEFAULT_BITRATE));
        settings.setDevice(deviceField.getText().trim());
        store.saveRipSettings(settings);
    }

    private static Integer[] boxed(int[] values) {
        Integer[] out = new Integer[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i];
        }
        return out;
    }

    private static int nz(Integer v, int fallback) {
        return (v == null) ? fallback : v;
    }

    private static boolean move(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            return Files.isRegularFile(to);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }

    /** Builds a safe {@code Artist - Album/NN - Title.ext} file name. */
    static String fileName(String artist, String album, int track, String title,
                           CdRipBackend.Format format) {
        String base = sanitize(title) ;
        String prefix = (artist == null || artist.isBlank()) ? "" : sanitize(artist) + " - ";
        String name = String.format("%s%02d - %s.%s", prefix, track,
                base.isBlank() ? ("Track " + track) : base, format.extension());
        return name;
    }

    private static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void setStatus(String text) {
        final String message = text;
        if (SwingUtilities.isEventDispatchThread()) {
            statusLabel.setText(message);
        } else {
            SwingUtilities.invokeLater(() -> statusLabel.setText(message));
        }
    }

    // ------------------------------------------------------------------
    // Test hooks
    // ------------------------------------------------------------------

    /** The number of track rows currently listed. */
    int trackCount() {
        return trackModel.size();
    }

    /** The current status text. */
    String statusText() {
        return statusLabel.getText();
    }

    /** The parsed TOC from the last successful read, or null. */
    Toc currentToc() {
        return currentToc;
    }

    /** Cancels any in-flight external process (best effort). */
    void cancelProcess() {
        Process p = currentProcess;
        if (p != null) {
            p.destroyForcibly();
        }
    }
}
