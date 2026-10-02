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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * The audio player's user interface: a library / playlist on the left, a
 * now-playing display with transport controls in the centre, and a status strip
 * along the bottom. One panel serves both the 3D desktop (hosted on a SwingNode
 * inside a Frame3D by the {@code AudioPlayer} wrapper) and the 2D/Swing desktop
 * (opened as an MDI internal frame via {@code Desktop2DAppRegistry.PANEL_APPS}).
 *
 * <p>Playback follows the honest split described on {@link AudioBackend}: the
 * JDK-native formats (WAV / AU / AIFF) play in-process through
 * {@code javax.sound.sampled}, while MP3, other codecs and every network stream
 * (internet radio, podcasts) are handed to a real external player the backend
 * resolves and drives. No sound device is touched and no process is started
 * until the user presses play, and every audio path is guarded, so the panel
 * builds and is asserted on headless.</p>
 */
public class AudioPlayerPanel extends JPanel {

    /** Preferred width in pixels. */
    public static final int WIDTH_PX = 860;
    /** Preferred height in pixels. */
    public static final int HEIGHT_PX = 560;

    private final AudioPlayerStore store;
    private final Playlist playlist;
    private final PlayerSettings settings;

    private final DefaultListModel<MediaItem> listModel = new DefaultListModel<>();
    private final JList<MediaItem> libraryList = new JList<>(listModel);
    private final JLabel nowPlaying = new JLabel("Nothing playing", JLabel.CENTER);
    private final JLabel statusLabel = new JLabel("Ready");
    private final JSlider volume = new JSlider(0, 100, PlayerSettings.DEFAULT_VOLUME);
    private final JCheckBox repeatCheck = new JCheckBox("Repeat");
    private final JCheckBox shuffleCheck = new JCheckBox("Shuffle");

    private final JButton prevBtn = new JButton("|<");
    private final JButton playBtn = new JButton("Play");
    private final JButton stopBtn = new JButton("Stop");
    private final JButton nextBtn = new JButton(">|");

    /** The album-cover carousel (the 2D counterpart of the 3D CDViewer). */
    private final AlbumCoverFlow coverFlow = new AlbumCoverFlow();
    /** The Rip CD tab; built in {@link #buildCenterTabs()}. */
    private CdRipPanel ripPanel;

    private Runnable onClose;
    private volatile Process externalProcess;
    private volatile Clip nativeClip;

    /** Builds the panel with the default store. */
    public AudioPlayerPanel() {
        this(new AudioPlayerStore());
    }

    /**
     * Builds the panel over an explicit store (package-private for tests).
     *
     * @param store the library / settings store
     */
    AudioPlayerPanel(AudioPlayerStore store) {
        this.store = store;
        this.playlist = new Playlist();
        this.settings = store.loadSettings();

        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildLibraryPane(), BorderLayout.WEST);
        add(buildCenterTabs(), BorderLayout.CENTER);
        add(buildBottomPane(), BorderLayout.SOUTH);

        volume.setValue(settings.getVolume());
        repeatCheck.setSelected(settings.isRepeat());
        shuffleCheck.setSelected(settings.isShuffle());
        playlist.setRepeat(settings.isRepeat());
        playlist.setShuffle(settings.isShuffle());

        for (MediaItem item : store.loadLibrary()) {
            playlist.add(item);
        }
        refreshList();
        wireListeners();
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private Component buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(action("Add File...", this::chooseFiles));
        bar.add(action("Add URL...", this::addUrl));
        bar.add(action("Add Radio...", this::addRadio));
        bar.addSeparator();
        bar.add(action("Remove", this::removeSelected));
        bar.add(action("Clear", this::clearLibrary));
        return bar;
    }

    private Component buildLibraryPane() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Library"));
        panel.setPreferredSize(new Dimension(240, 100));
        libraryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        libraryList.setVisibleRowCount(16);
        panel.add(new JScrollPane(libraryList), BorderLayout.CENTER);
        JLabel hint = new JLabel("<html><i>Double-click to play</i></html>");
        hint.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        panel.add(hint, BorderLayout.SOUTH);
        return panel;
    }

    private Component buildCenterTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Now Playing", buildNowPlayingPane());
        tabs.addTab("Albums", buildAlbumsTab());
        ripPanel = new CdRipPanel(store, this::onTracksRipped);
        tabs.addTab("Rip CD", ripPanel);
        return tabs;
    }

    private Component buildAlbumsTab() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        coverFlow.setOnSelect(a -> setStatus("Selected: " + a.label()));
        coverFlow.setOnPlay(this::playAlbum);
        panel.add(coverFlow, BorderLayout.CENTER);

        JPanel nav = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        JButton prev = new JButton("<");
        prev.addActionListener(e -> coverFlow.revolve(-1));
        JButton next = new JButton(">");
        next.addActionListener(e -> coverFlow.revolve(1));
        JButton play = new JButton("Play Album");
        play.addActionListener(e -> playAlbum(coverFlow.selectedAlbum()));
        nav.add(prev);
        nav.add(play);
        nav.add(next);
        JLabel hint = new JLabel("<html><i>Wheel or arrows to browse, double-click a sleeve to play</i></html>");
        hint.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
        nav.add(hint);
        panel.add(nav, BorderLayout.SOUTH);
        return panel;
    }

    private Component buildNowPlayingPane() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createTitledBorder("Now Playing"));

        nowPlaying.setFont(nowPlaying.getFont().deriveFont(Font.BOLD, 16f));
        nowPlaying.setBorder(BorderFactory.createEmptyBorder(24, 8, 24, 8));
        panel.add(nowPlaying, BorderLayout.CENTER);

        JPanel transport = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
        transport.add(prevBtn);
        transport.add(playBtn);
        transport.add(stopBtn);
        transport.add(nextBtn);
        panel.add(transport, BorderLayout.SOUTH);
        return panel;
    }

    private Component buildBottomPane() {
        JPanel panel = new JPanel(new BorderLayout(6, 4));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        left.add(repeatCheck);
        left.add(shuffleCheck);
        left.add(new JLabel("Volume:"));
        volume.setPreferredSize(new Dimension(120, 24));
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

    private void wireListeners() {
        playBtn.addActionListener(e -> playSelected());
        stopBtn.addActionListener(e -> stopPlayback());
        nextBtn.addActionListener(e -> {
            if (playlist.next() != null) {
                playCurrent();
            }
        });
        prevBtn.addActionListener(e -> {
            if (playlist.previous() != null) {
                playCurrent();
            }
        });
        libraryList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && libraryList.getSelectedIndex() >= 0) {
                    playlist.selectIndex(libraryList.getSelectedIndex());
                    playCurrent();
                }
            }
        });
        libraryList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && libraryList.getSelectedIndex() >= 0) {
                playlist.selectIndex(libraryList.getSelectedIndex());
            }
        });
        volume.addChangeListener(e -> {
            settings.setVolume(volume.getValue());
            applyNativeVolume();
        });
        repeatCheck.addActionListener(e -> {
            settings.setRepeat(repeatCheck.isSelected());
            playlist.setRepeat(repeatCheck.isSelected());
            persist();
        });
        shuffleCheck.addActionListener(e -> {
            settings.setShuffle(shuffleCheck.isSelected());
            playlist.setShuffle(shuffleCheck.isSelected());
            persist();
        });
    }

    // ------------------------------------------------------------------
    // Library editing
    // ------------------------------------------------------------------

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Audio files", "mp3", "wav", "ogg", "flac", "m4a", "aac", "au", "aif", "aiff"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File f : chooser.getSelectedFiles()) {
                addFile(f.getAbsolutePath());
            }
            persist();
        }
    }

    private void addUrl() {
        String url = prompt("Add Stream URL", "Stream URL (http/https/rtsp):");
        if (url != null && !url.isBlank()) {
            playlist.add(MediaItem.stream(null, url.trim(), MediaItem.Kind.STREAM));
            refreshList();
            persist();
        }
    }

    private void addRadio() {
        String name = prompt("Add Radio Station", "Station name:");
        if (name == null || name.isBlank()) {
            return;
        }
        String url = prompt("Add Radio Station", "Stream URL:");
        if (url != null && !url.isBlank()) {
            playlist.add(MediaItem.stream(name.trim(), url.trim(), MediaItem.Kind.RADIO));
            refreshList();
            persist();
        }
    }

    private String prompt(String title, String message) {
        return (String) JOptionPane.showInputDialog(this, message, title,
                JOptionPane.PLAIN_MESSAGE);
    }

    private void removeSelected() {
        int i = libraryList.getSelectedIndex();
        if (i >= 0) {
            playlist.remove(i);
            refreshList();
            persist();
        }
    }

    private void clearLibrary() {
        playlist.clear();
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
            playlist.add(MediaItem.file(path.trim()));
            refreshList();
        }
    }

    private void refreshList() {
        listModel.clear();
        for (MediaItem item : playlist.items()) {
            listModel.addElement(item);
        }
        int idx = playlist.currentIndex();
        if (idx >= 0 && idx < listModel.size()) {
            libraryList.setSelectedIndex(idx);
        }
        refreshAlbums();
    }

    /** Rebuilds the album carousel from the current library. */
    private void refreshAlbums() {
        coverFlow.setAlbums(AlbumIndex.albums(playlist.items()));
    }

    /** Adds freshly ripped tracks to the library, persists and refreshes. */
    private void onTracksRipped(List<MediaItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (MediaItem item : items) {
            playlist.add(item);
        }
        refreshList();
        persist();
    }

    /** Queues and plays an album from the carousel by locating its first track. */
    private void playAlbum(AlbumIndex.Album album) {
        if (album == null || album.tracks.isEmpty()) {
            return;
        }
        List<MediaItem> items = playlist.items();
        int idx = items.indexOf(album.tracks.get(0));
        if (idx >= 0) {
            playlist.selectIndex(idx);
            if (idx < listModel.size()) {
                libraryList.setSelectedIndex(idx);
            }
            playCurrent();
        }
    }

    private void persist() {
        settings.setVolume(volume.getValue());
        store.saveLibrary(playlist.items());
        store.saveSettings(settings);
    }

    // ------------------------------------------------------------------
    // Playback
    // ------------------------------------------------------------------

    private void playSelected() {
        int i = libraryList.getSelectedIndex();
        if (i >= 0) {
            playlist.selectIndex(i);
        } else if (playlist.currentIndex() < 0 && !playlist.isEmpty()) {
            playlist.selectIndex(0);
        }
        playCurrent();
    }

    private void playCurrent() {
        MediaItem item = playlist.current();
        if (item == null) {
            setStatus("The library is empty - add a file, stream or radio station.");
            return;
        }
        stopPlayback();
        nowPlaying.setText(item.toString());
        refreshList();
        String location = item.getLocation();
        if (AudioBackend.isNativelyPlayable(location)) {
            playNative(new File(location));
        } else {
            playExternal(location);
        }
    }

    /** Plays a JDK-supported file in-process on a background thread. */
    private void playNative(File file) {
        Thread worker = new Thread(() -> {
            try (AudioInputStream in = AudioSystem.getAudioInputStream(file)) {
                Clip clip = AudioSystem.getClip();
                clip.open(in);
                nativeClip = clip;
                applyNativeVolume();
                clip.start();
                setStatus("Playing (native): " + file.getName());
                // Block this worker (not the EDT) until the clip finishes.
                clip.drain();
            } catch (Exception ex) {
                setStatus("Cannot play natively (" + ex.getMessage()
                        + ") - try an external player.");
            } finally {
                nativeClip = null;
            }
        }, "audio-native-playback");
        worker.setDaemon(true);
        worker.start();
    }

    /** Hands a codec / stream location to the resolved external player. */
    private void playExternal(String location) {
        Optional<String> player = AudioBackend.resolvePlayer(
                settings.getPreferredPlayer(), AudioPlayerPanel::onPath);
        if (player.isEmpty()) {
            setStatus("No audio player found. Install mpv, mpg123, ffplay or vlc "
                    + "to play " + describeFormat(location) + ".");
            return;
        }
        List<String> command = AudioBackend.playCommand(
                player.get(), location, volume.getValue());
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

    /** Stops any native clip or external player process. */
    void stopPlayback() {
        if (nativeClip != null) {
            try {
                nativeClip.stop();
                nativeClip.close();
            } catch (RuntimeException ignored) {
                // already stopped
            }
            nativeClip = null;
        }
        if (externalProcess != null) {
            externalProcess.destroy();
            externalProcess = null;
        }
        playBtn.setText("Play");
    }

    private void applyNativeVolume() {
        Clip clip = nativeClip;
        if (clip == null) {
            return;
        }
        try {
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain =
                        (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                float fraction = volume.getValue() / 100f;
                // Map 0..1 to the control's dB range; 0 -> minimum (mute-ish).
                float db = (fraction <= 0f)
                        ? gain.getMinimum()
                        : gain.getMinimum()
                            + fraction * (gain.getMaximum() - gain.getMinimum());
                gain.setValue(db);
            }
        } catch (RuntimeException ignored) {
            // no gain control on this mixer
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
        String ext = AudioBackend.extension(location);
        return ext.isEmpty() ? "this format" : ext.toUpperCase() + " audio";
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

    /** The backing playlist model. */
    Playlist playlist() {
        return playlist;
    }

    /** The number of library rows shown. */
    int librarySize() {
        return listModel.size();
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
