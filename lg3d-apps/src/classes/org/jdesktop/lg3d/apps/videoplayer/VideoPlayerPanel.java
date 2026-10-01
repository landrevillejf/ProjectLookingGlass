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
package org.jdesktop.lg3d.apps.videoplayer;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * The video player's user interface, a VLC-style front-end: a library on the
 * left, a now-playing display with launch controls in the centre, and player /
 * volume / full-screen preferences along the bottom. One panel serves both the
 * 3D desktop (hosted on a SwingNode inside a Frame3D by the {@code VideoPlayer}
 * wrapper) and the 2D/Swing desktop (opened as an MDI internal frame via
 * {@code Desktop2DAppRegistry.PANEL_APPS}).
 *
 * <p>The JDK has no video decoder, so playback follows the honest split
 * described on {@link VideoBackend}: this panel is a launcher that resolves a
 * real external player (vlc / mpv / mplayer / totem / ffplay) and hands it the
 * selected file, stream or disc. No process is started until the user presses
 * play, and the launch path is guarded, so the panel builds and is asserted on
 * headless.</p>
 */
public class VideoPlayerPanel extends JPanel {

    /** Preferred width in pixels. */
    public static final int WIDTH_PX = 800;
    /** Preferred height in pixels. */
    public static final int HEIGHT_PX = 500;

    private final VideoPlayerStore store;
    private final List<VideoItem> library = new ArrayList<>();
    private final VideoSettings settings;

    private final DefaultListModel<VideoItem> listModel = new DefaultListModel<>();
    private final JList<VideoItem> libraryList = new JList<>(listModel);
    private final JLabel nowPlaying = new JLabel("Nothing selected", JLabel.CENTER);
    private final JLabel statusLabel = new JLabel("Ready");
    private final JSlider volume = new JSlider(0, 100, VideoSettings.DEFAULT_VOLUME);
    private final JCheckBox fullscreenCheck = new JCheckBox("Full screen");
    private final JComboBox<String> playerBox = new JComboBox<>();

    private final JButton playBtn = new JButton("Play");
    private final JButton stopBtn = new JButton("Stop");

    private Runnable onClose;
    private volatile Process externalProcess;

    /** Builds the panel with the default store. */
    public VideoPlayerPanel() {
        this(new VideoPlayerStore());
    }

    /**
     * Builds the panel over an explicit store (package-private for tests).
     *
     * @param store the library / settings store
     */
    VideoPlayerPanel(VideoPlayerStore store) {
        this.store = store;
        this.settings = store.loadSettings();

        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildLibraryPane(), BorderLayout.WEST);
        add(buildNowPlayingPane(), BorderLayout.CENTER);
        add(buildBottomPane(), BorderLayout.SOUTH);

        volume.setValue(settings.getVolume());
        fullscreenCheck.setSelected(settings.isFullscreen());
        library.addAll(store.loadLibrary());
        refreshList();
        wireListeners();
        refreshPlayerBox();
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private Component buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(action("Open File...", this::chooseFile));
        bar.add(action("Open URL...", this::addUrl));
        bar.add(action("Open Disc...", this::addDisc));
        bar.addSeparator();
        bar.add(action("Remove", this::removeSelected));
        bar.add(action("Clear", this::clearLibrary));
        return bar;
    }

    private Component buildLibraryPane() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Library"));
        panel.setPreferredSize(new Dimension(250, 100));
        libraryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        libraryList.setVisibleRowCount(16);
        panel.add(new JScrollPane(libraryList), BorderLayout.CENTER);
        JLabel hint = new JLabel("<html><i>Double-click to play</i></html>");
        hint.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        panel.add(hint, BorderLayout.SOUTH);
        return panel;
    }

    private Component buildNowPlayingPane() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createTitledBorder("Now Playing"));

        nowPlaying.setFont(nowPlaying.getFont().deriveFont(Font.BOLD, 16f));
        nowPlaying.setBorder(BorderFactory.createEmptyBorder(24, 8, 24, 8));
        panel.add(nowPlaying, BorderLayout.CENTER);

        JPanel transport = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
        transport.add(playBtn);
        transport.add(stopBtn);
        panel.add(transport, BorderLayout.SOUTH);
        return panel;
    }

    private Component buildBottomPane() {
        JPanel panel = new JPanel(new BorderLayout(6, 4));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        left.add(new JLabel("Player:"));
        playerBox.setPreferredSize(new Dimension(140, 26));
        left.add(playerBox);
        left.add(fullscreenCheck);
        left.add(new JLabel("Volume:"));
        volume.setPreferredSize(new Dimension(110, 24));
        left.add(volume);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        JButton close = new JButton("Close");
        close.addActionListener(e -> {
            stopPlayback();
            if (onClose != null) {
                onClose.run();
            }
        });
        right.add(close);

        JPanel row = new JPanel(new BorderLayout());
        row.add(left, BorderLayout.WEST);
        row.add(right, BorderLayout.EAST);
        panel.add(row, BorderLayout.NORTH);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        panel.add(statusLabel, BorderLayout.SOUTH);
        return panel;
    }

    private JButton action(String label, Runnable handler) {
        JButton button = new JButton(label);
        button.addActionListener(e -> handler.run());
        return button;
    }

    private void refreshPlayerBox() {
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        model.addElement("Auto-detect");
        for (String player : VideoBackend.KNOWN_PLAYERS) {
            model.addElement(player);
        }
        playerBox.setModel(model);
        String preferred = settings.getPreferredPlayer();
        playerBox.setSelectedItem(
                (preferred == null || preferred.isBlank()) ? "Auto-detect" : preferred);
    }

    private void wireListeners() {
        playBtn.addActionListener(e -> playSelected());
        stopBtn.addActionListener(e -> stopPlayback());
        libraryList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && libraryList.getSelectedIndex() >= 0) {
                    playSelected();
                }
            }
        });
        libraryList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                VideoItem item = libraryList.getSelectedValue();
                if (item != null) {
                    nowPlaying.setText(item.toString());
                }
            }
        });
        volume.addChangeListener(e -> settings.setVolume(volume.getValue()));
        fullscreenCheck.addActionListener(e -> {
            settings.setFullscreen(fullscreenCheck.isSelected());
            persist();
        });
        playerBox.addActionListener(e -> {
            Object sel = playerBox.getSelectedItem();
            settings.setPreferredPlayer(
                    ("Auto-detect".equals(sel) || sel == null) ? "" : sel.toString());
            persist();
        });
    }

    // ------------------------------------------------------------------
    // Library editing
    // ------------------------------------------------------------------

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Video files", "mp4", "mkv", "avi", "mov", "webm", "mpg", "mpeg",
                "wmv", "flv", "m4v", "ogv", "ts"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File f : chooser.getSelectedFiles()) {
                addFile(f.getAbsolutePath());
            }
            persist();
        }
    }

    private void addUrl() {
        String url = prompt("Open Stream URL", "Stream URL (http/rtsp/rtmp):");
        if (url != null && !url.isBlank()) {
            addItem(VideoItem.of(null, url.trim(), VideoItem.Kind.STREAM));
        }
    }

    private void addDisc() {
        String device = prompt("Open Disc", "Disc device (e.g. /dev/sr0):");
        if (device != null && !device.isBlank()) {
            String url = VideoBackend.discUrl("dvd", device.trim());
            addItem(VideoItem.of("Disc " + device.trim(), url, VideoItem.Kind.DISC));
        }
    }

    private String prompt(String title, String message) {
        return (String) JOptionPane.showInputDialog(this, message, title,
                JOptionPane.PLAIN_MESSAGE);
    }

    private void removeSelected() {
        int i = libraryList.getSelectedIndex();
        if (i >= 0 && i < library.size()) {
            library.remove(i);
            refreshList();
            persist();
        }
    }

    private void clearLibrary() {
        library.clear();
        refreshList();
        persist();
    }

    /**
     * Adds a local file to the library (no playback). Package-visible so a test
     * can grow the model without a file chooser.
     *
     * @param path the absolute file path
     */
    void addFile(String path) {
        if (path != null && !path.isBlank()) {
            addItem(VideoItem.file(path.trim()));
        }
    }

    private void addItem(VideoItem item) {
        if (item != null) {
            library.add(item);
            refreshList();
            persist();
        }
    }

    private void refreshList() {
        listModel.clear();
        for (VideoItem item : library) {
            listModel.addElement(item);
        }
    }

    private void persist() {
        settings.setVolume(volume.getValue());
        store.saveLibrary(new ArrayList<>(library));
        store.saveSettings(settings);
    }

    // ------------------------------------------------------------------
    // Playback (external launch)
    // ------------------------------------------------------------------

    private void playSelected() {
        VideoItem item = libraryList.getSelectedValue();
        if (item == null) {
            setStatus("The library is empty - open a file, stream or disc.");
            return;
        }
        nowPlaying.setText(item.toString());
        playExternal(item.getLocation());
    }

    /** Hands a file / stream / disc location to the resolved external player. */
    private void playExternal(String location) {
        Optional<String> player = VideoBackend.resolvePlayer(
                settings.getPreferredPlayer(), VideoPlayerPanel::onPath);
        if (player.isEmpty()) {
            setStatus("No video player found. Install vlc, mpv, mplayer or "
                    + "totem to play " + describeFormat(location) + ".");
            return;
        }
        List<String> command = VideoBackend.playCommand(
                player.get(), location, settings.isFullscreen(), volume.getValue());
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            String display = System.getProperty("lg.lgserverdisplay",
                    System.getenv("DISPLAY"));
            if (display != null) {
                pb.environment().put("DISPLAY", display);
            }
            externalProcess = pb.start();
            setStatus("Playing via " + player.get() + ": " + location);
        } catch (IOException | RuntimeException ex) {
            externalProcess = null;
            setStatus("Could not start " + player.get() + " (" + ex.getMessage() + ").");
        }
    }

    /** Stops any running external player process. */
    void stopPlayback() {
        if (externalProcess != null) {
            externalProcess.destroy();
            externalProcess = null;
        }
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

    private static String describeFormat(String location) {
        if (VideoBackend.isDiscUrl(location)) {
            return "this disc";
        }
        String ext = VideoBackend.extension(location);
        return ext.isEmpty() ? "this format" : ext.toUpperCase() + " video";
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

    /** The number of library rows shown. */
    int librarySize() {
        return listModel.size();
    }

    /** An unmodifiable view of the library. */
    List<VideoItem> library() {
        return List.copyOf(library);
    }

    /** The current status text. */
    String statusText() {
        return statusLabel.getText();
    }

    /** The current now-playing text. */
    String nowPlayingText() {
        return nowPlaying.getText();
    }
}
