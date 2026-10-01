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
package org.jdesktop.lg3d.apps.videoconference;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.datatransfer.StringSelection;
import java.awt.Toolkit;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.ListCellRenderer;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;

/**
 * The Video Conference application's Swing face: a Jitsi Meet conference client.
 *
 * <p>It is the lobby, address book and launcher for audio/video meetings. The
 * user types (or picks) a room name, optionally sets a display name/e-mail and
 * mute-on-join preferences, and presses <b>Join</b>. The client builds the
 * correct Jitsi Meet deep link via {@link JitsiUrlBuilder} and hands it to the
 * system browser ({@link java.awt.Desktop#browse}) or an external meeting
 * command, where the real WebRTC audio/video session runs. Saved rooms,
 * contacts, recent calls and settings persist as JSON under
 * {@code ~/.lg3d/videoconference} via {@link VideoConferenceStore}.</p>
 *
 * <p>The panel is plain Swing and touches no Java&nbsp;3D, so the one class
 * serves both desktops: in 3D the {@link VideoConference} wrapper hosts it on a
 * {@code SwingNode} inside a {@code Frame3D} via {@code TitledSwingWindow}; in
 * the 2D/Swing desktop {@code Desktop2DAppRegistry.PANEL_APPS} opens the very
 * same panel as an MDI internal frame. The main surface uses only layout
 * managers, buttons, toggles, text fields and lists (no Synth combo boxes on the
 * rendered surface), so it paints correctly into the SwingNode offscreen
 * buffer; the modal dialogs use combo boxes freely because they are separate
 * top-level windows.</p>
 */
public class VideoConferencePanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 900;
    public static final int HEIGHT_PX = 600;

    private static final Color BACKDROP = new Color(0x22, 0x29, 0x32);
    private static final Color CARD = new Color(0x2C, 0x35, 0x40);
    private static final Color TEXT = new Color(0xEC, 0xF1, 0xF7);
    private static final Color TEXT_DIM = new Color(0xA8, 0xB4, 0xC2);
    private static final Color ACCENT = new Color(0x4C, 0xAF, 0x50);

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final VideoConferenceStore store;
    private VideoConferenceSettings settings;

    private final List<ConferenceRoom> rooms = new ArrayList<>();
    private final List<Contact> contacts = new ArrayList<>();
    private final List<CallHistoryEntry> history = new ArrayList<>();

    private final DefaultListModel<ConferenceRoom> roomModel = new DefaultListModel<>();
    private final DefaultListModel<Contact> contactModel = new DefaultListModel<>();
    private final DefaultListModel<CallHistoryEntry> historyModel = new DefaultListModel<>();

    private final JList<ConferenceRoom> roomList = new JList<>(roomModel);
    private final JList<Contact> contactList = new JList<>(contactModel);
    private final JList<CallHistoryEntry> historyList = new JList<>(historyModel);

    private final JTextField roomField = new JTextField();
    private final JTextField domainField = new JTextField();
    private final JToggleButton audioToggle = new JToggleButton("Mic off");
    private final JToggleButton videoToggle = new JToggleButton("Camera off");
    private final JButton joinButton = new JButton("Join Meeting");
    private final JButton copyLinkButton = new JButton("Copy invite link");
    private final JLabel identityLabel = new JLabel(" ");

    private final CameraPreview preview = new CameraPreview();
    private final CameraCapture capture;

    private final JLabel statusLabel = new JLabel(" ");

    private Runnable onClose;
    private String lastJoinUrl;
    private CallHistoryEntry.Outcome lastOutcome;

    public VideoConferencePanel() {
        this(new VideoConferenceStore());
    }

    /**
     * Creates the panel backed by an explicit store (tests point this at a temp
     * directory).
     *
     * @param store the persistence backend
     */
    public VideoConferencePanel(VideoConferenceStore store) {
        super(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        setBackground(BACKDROP);
        setOpaque(true);

        this.store = store;
        this.settings = store.loadSettings();
        this.capture = CameraCapture.detect();

        rooms.addAll(store.loadRooms());
        contacts.addAll(store.loadContacts());
        history.addAll(store.loadHistory());

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildSidebar(), BorderLayout.WEST);
        add(buildLobby(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        audioToggle.setSelected(settings.isStartWithAudioMuted());
        videoToggle.setSelected(settings.isStartWithVideoMuted());
        refreshLists();
        updateIdentityLabel();

        preview.attach(capture, settings.getPreferredCamera());
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private JComponent buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

        JButton newRoom = new JButton("New Room");
        newRoom.addActionListener(e -> newRoom());
        JButton generate = new JButton("Random name");
        generate.addActionListener(e -> {
            roomField.setText(JitsiUrlBuilder.generateRoomName());
            roomField.selectAll();
        });
        JButton addContact = new JButton("Add Contact");
        addContact.addActionListener(e -> showContactDialog(null));
        JButton settingsBtn = new JButton("Settings");
        settingsBtn.addActionListener(e -> showSettingsDialog());

        bar.add(newRoom);
        bar.add(generate);
        bar.addSeparator();
        bar.add(addContact);
        bar.addSeparator();
        bar.add(settingsBtn);
        return bar;
    }

    private JComponent buildSidebar() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.setPreferredSize(new Dimension(210, 0));

        roomList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        roomList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                ConferenceRoom r = roomList.getSelectedValue();
                if (r != null) {
                    roomField.setText(r.getName());
                    domainField.setText(r.getDomain());
                }
            }
        });
        JPanel roomsPanel = listPanel(roomList, "Rooms", this::editSelectedRoom, this::removeSelectedRoom);

        contactList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel contactsPanel = listPanel(contactList, "Contacts",
                () -> showContactDialog(contactList.getSelectedValue()), this::removeSelectedContact);

        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyList.setCellRenderer(new HistoryCellRenderer());
        historyList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                CallHistoryEntry h = historyList.getSelectedValue();
                if (h != null) {
                    roomField.setText(h.getRoomName());
                }
            }
        });
        JButton rejoin = new JButton("Rejoin");
        rejoin.addActionListener(e -> join());
        JButton clearHistory = new JButton("Clear");
        clearHistory.addActionListener(e -> clearHistory());
        JPanel historyPanel = new JPanel(new BorderLayout());
        historyPanel.setOpaque(false);
        historyPanel.add(new JScrollPane(historyList), BorderLayout.CENTER);
        JPanel histButtons = new JPanel(new GridLayout(1, 2, 4, 0));
        histButtons.setOpaque(false);
        histButtons.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        histButtons.add(rejoin);
        histButtons.add(clearHistory);
        historyPanel.add(histButtons, BorderLayout.SOUTH);

        tabs.addTab("Rooms", roomsPanel);
        tabs.addTab("Contacts", contactsPanel);
        tabs.addTab("History", historyPanel);

        JPanel west = new JPanel(new BorderLayout());
        west.setOpaque(false);
        west.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));
        west.add(tabs, BorderLayout.CENTER);
        return west;
    }

    private JPanel listPanel(JList<?> list, String title, Runnable edit, Runnable remove) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(new JScrollPane(list), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
        buttons.setOpaque(false);
        buttons.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JButton editBtn = new JButton("Edit");
        editBtn.addActionListener(e -> edit.run());
        JButton removeBtn = new JButton("Remove");
        removeBtn.addActionListener(e -> remove.run());
        buttons.add(editBtn);
        buttons.add(removeBtn);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildLobby() {
        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setOpaque(false);
        center.setBorder(BorderFactory.createEmptyBorder(10, 6, 10, 12));

        JPanel previewWrap = new JPanel(new BorderLayout());
        previewWrap.setOpaque(false);
        previewWrap.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        previewWrap.setBackground(CARD);
        previewWrap.add(preview, BorderLayout.CENTER);
        center.add(previewWrap, BorderLayout.CENTER);

        center.add(buildJoinBar(), BorderLayout.SOUTH);
        return center;
    }

    private JComponent buildJoinBar() {
        JPanel bar = new JPanel(new GridBagLayout());
        bar.setBackground(CARD);
        bar.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        bar.add(label("Room"), c);
        c.gridx = 1; c.gridy = 0; c.weightx = 1.0;
        roomField.setText(JitsiUrlBuilder.generateRoomName());
        bar.add(roomField, c);
        c.gridx = 2; c.gridy = 0; c.weightx = 0;
        bar.add(label("Server"), c);
        c.gridx = 3; c.gridy = 0; c.weightx = 0.6;
        domainField.setText(settings.getDefaultDomain());
        bar.add(domainField, c);

        c.gridx = 0; c.gridy = 1; c.gridwidth = 4; c.weightx = 1.0;
        JPanel toggles = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        toggles.setOpaque(false);
        audioToggle.addActionListener(e -> {
            settings.setStartWithAudioMuted(audioToggle.isSelected());
            saveSettings();
        });
        videoToggle.addActionListener(e -> {
            settings.setStartWithVideoMuted(videoToggle.isSelected());
            preview.setVideoEnabled(!videoToggle.isSelected());
            saveSettings();
        });
        preview.setVideoEnabled(!videoToggle.isSelected());
        toggles.add(audioToggle);
        toggles.add(videoToggle);
        toggles.add(identityLabel);
        bar.add(toggles, c);

        c.gridx = 0; c.gridy = 2; c.gridwidth = 4; c.weightx = 1.0;
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        copyLinkButton.addActionListener(e -> copyInviteLink());
        joinButton.setBackground(ACCENT);
        joinButton.setForeground(Color.WHITE);
        joinButton.setFocusPainted(false);
        joinButton.addActionListener(e -> join());
        actions.add(copyLinkButton);
        actions.add(joinButton);
        bar.add(actions, c);

        roomField.addActionListener(e -> join());
        return bar;
    }

    private JComponent buildStatusBar() {
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 10, 6, 10));
        statusLabel.setForeground(TEXT_DIM);
        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(BACKDROP);
        south.add(statusLabel, BorderLayout.CENTER);
        return south;
    }

    private static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(TEXT);
        return l;
    }

    private void updateIdentityLabel() {
        String name = settings.getDisplayName();
        identityLabel.setForeground(TEXT_DIM);
        identityLabel.setHorizontalAlignment(SwingConstants.LEFT);
        identityLabel.setText((name == null || name.isBlank())
                ? "  (no display name set)" : "  Joining as " + name);
    }

    // ------------------------------------------------------------------
    // Lists
    // ------------------------------------------------------------------

    private void refreshLists() {
        roomModel.clear();
        for (ConferenceRoom r : rooms) {
            roomModel.addElement(r);
        }
        contactModel.clear();
        for (Contact ct : contacts) {
            contactModel.addElement(ct);
        }
        historyModel.clear();
        for (CallHistoryEntry h : history) {
            historyModel.addElement(h);
        }
    }

    // ------------------------------------------------------------------
    // Join flow
    // ------------------------------------------------------------------

    /** Builds the join URL for the current room/domain fields and launches it. */
    public void join() {
        ConferenceRoom room = currentRoomFromInput();
        String url = JitsiUrlBuilder.buildJoinUrl(room, settings);
        if (url == null) {
            setStatus("Enter a room name to join");
            return;
        }
        lastJoinUrl = url;
        CallHistoryEntry.Outcome outcome = launchUrl(url);
        lastOutcome = outcome;

        room.recordJoin();
        upsertSavedRoom(room);
        addHistory(room, url, outcome);

        switch (outcome) {
            case LAUNCHED -> setStatus("Joined " + room.getName() + " \u2014 meeting opened");
            case URL_SHOWN -> setStatus("Copied meeting link \u2014 no browser available");
            case LAUNCH_FAILED -> setStatus("Could not launch the meeting client");
            case CANCELLED -> setStatus("Join cancelled");
            default -> setStatus("Join complete");
        }
    }

    /** @return the room described by the room + server fields. */
    ConferenceRoom currentRoomFromInput() {
        String name = roomField.getText().trim();
        String domain = JitsiUrlBuilder.normalizeDomain(domainField.getText());
        ConferenceRoom selected = roomList.getSelectedValue();
        ConferenceRoom room;
        if (selected != null && selected.getName().equals(name)) {
            room = selected;
            if (!domain.isEmpty()) {
                room.setDomain(domain);
            }
        } else {
            room = new ConferenceRoom(name, domain);
        }
        return room;
    }

    private CallHistoryEntry.Outcome launchUrl(String url) {
        if (GraphicsEnvironment.isHeadless()) {
            // Headless (tests / no display): never open a browser or a modal.
            return CallHistoryEntry.Outcome.URL_SHOWN;
        }
        if (settings.getLaunchMode() == VideoConferenceSettings.LaunchMode.EXTERNAL_COMMAND
                && !settings.getExternalCommand().isBlank()) {
            String cmd = JitsiUrlBuilder.buildExternalCommand(settings.getExternalCommand(), url);
            try {
                new ProcessBuilder(splitCommand(cmd)).start();
                return CallHistoryEntry.Outcome.LAUNCHED;
            } catch (Exception ex) {
                showUrlFallback(url, "Could not run the external meeting command.");
                return CallHistoryEntry.Outcome.LAUNCH_FAILED;
            }
        }
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return CallHistoryEntry.Outcome.LAUNCHED;
            }
        } catch (Exception ex) {
            // fall through to the manual-copy fallback
        }
        showUrlFallback(url, "No web browser is available to open the meeting.");
        return CallHistoryEntry.Outcome.URL_SHOWN;
    }

    private void showUrlFallback(String url, String reason) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        JTextField field = new JTextField(url);
        field.selectAll();
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(new JLabel(reason), BorderLayout.NORTH);
        p.add(field, BorderLayout.CENTER);
        JButton copy = new JButton("Copy");
        copy.addActionListener(e -> copyToClipboard(url));
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(copy);
        p.add(south, BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(this, p, "Meeting link", JOptionPane.INFORMATION_MESSAGE);
    }

    private void copyInviteLink() {
        ConferenceRoom room = currentRoomFromInput();
        String url = JitsiUrlBuilder.buildShareUrl(room, settings);
        if (url == null) {
            setStatus("Enter a room name first");
            return;
        }
        copyToClipboard(url);
        setStatus("Invite link copied: " + url);
    }

    private void copyToClipboard(String text) {
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(text), null);
            }
        } catch (RuntimeException ex) {
            // Clipboard unavailable; the status line still shows the link.
        }
    }

    private void addHistory(ConferenceRoom room, String url, CallHistoryEntry.Outcome outcome) {
        CallHistoryEntry entry =
                new CallHistoryEntry(room.getId(), room.getName(), url, outcome);
        history.add(0, entry);
        int limit = settings.getHistoryLimit();
        while (limit > 0 && history.size() > limit) {
            history.remove(history.size() - 1);
        }
        if (limit <= 0) {
            history.clear();
        }
        saveHistory();
        refreshLists();
    }

    private void upsertSavedRoom(ConferenceRoom room) {
        if (room.getName().isEmpty()) {
            return;
        }
        for (int i = 0; i < rooms.size(); i++) {
            ConferenceRoom r = rooms.get(i);
            if (r.getName().equals(room.getName())
                    && JitsiUrlBuilder.resolveDomain(r, settings)
                            .equals(JitsiUrlBuilder.resolveDomain(room, settings))) {
                r.setJoinCount(room.getJoinCount());
                r.setLastJoinedAtEpochMs(room.getLastJoinedAtEpochMs());
                saveRooms();
                refreshLists();
                return;
            }
        }
        rooms.add(0, room);
        saveRooms();
        refreshLists();
    }

    // ------------------------------------------------------------------
    // Room / contact management
    // ------------------------------------------------------------------

    private void newRoom() {
        ConferenceRoom r = new ConferenceRoom(JitsiUrlBuilder.generateRoomName(),
                JitsiUrlBuilder.normalizeDomain(domainField.getText()));
        rooms.add(0, r);
        saveRooms();
        refreshLists();
        roomList.setSelectedIndex(0);
        roomField.setText(r.getName());
        setStatus("Created room " + r.getName());
    }

    private void editSelectedRoom() {
        ConferenceRoom r = roomList.getSelectedValue();
        if (r == null) {
            setStatus("Select a room to edit");
            return;
        }
        showRoomDialog(r);
    }

    private void showRoomDialog(ConferenceRoom room) {
        JTextField name = new JTextField(room.getName());
        JTextField domain = new JTextField(room.getDomain());
        JTextField notes = new JTextField(room.getNotes());
        JCheckBox moderator = new JCheckBox("Join as moderator", room.isModerator());
        JCheckBox locked = new JCheckBox("Locked room (password)", room.isLocked());
        JComboBox<String> audio = inheritCombo(room.getStartAudioMuted());
        JComboBox<String> video = inheritCombo(room.getStartVideoMuted());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        addFormRow(form, c, row++, "Room name", name);
        addFormRow(form, c, row++, "Server", domain);
        addFormRow(form, c, row++, "Audio on join", audio);
        addFormRow(form, c, row++, "Video on join", video);
        addFormRow(form, c, row++, "Notes", notes);
        c.gridx = 1; c.gridy = row++;
        form.add(moderator, c);
        c.gridx = 1; c.gridy = row;
        form.add(locked, c);

        int opt = JOptionPane.showConfirmDialog(this, form, "Room",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (opt != JOptionPane.OK_OPTION) {
            return;
        }
        room.setName(name.getText().trim());
        room.setDomain(JitsiUrlBuilder.normalizeDomain(domain.getText()));
        room.setNotes(notes.getText());
        room.setModerator(moderator.isSelected());
        room.setLocked(locked.isSelected());
        room.setStartAudioMuted(comboToTri(audio.getSelectedIndex()));
        room.setStartVideoMuted(comboToTri(video.getSelectedIndex()));
        saveRooms();
        refreshLists();
        setStatus("Saved room " + room.getName());
    }

    private void removeSelectedRoom() {
        ConferenceRoom r = roomList.getSelectedValue();
        if (r == null) {
            return;
        }
        rooms.remove(r);
        saveRooms();
        refreshLists();
        setStatus("Removed room " + r.getName());
    }

    private void showContactDialog(Contact existing) {
        boolean isNew = (existing == null);
        Contact c = isNew ? new Contact() : existing;
        JTextField name = new JTextField(c.getName());
        JTextField email = new JTextField(c.getEmail());
        JCheckBox fav = new JCheckBox("Favorite", c.isFavorite());
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 4, 3, 4);
        g.fill = GridBagConstraints.HORIZONTAL;
        addFormRow(form, g, 0, "Name", name);
        addFormRow(form, g, 1, "E-mail", email);
        g.gridx = 1; g.gridy = 2;
        form.add(fav, g);

        int opt = JOptionPane.showConfirmDialog(this, form,
                isNew ? "Add Contact" : "Edit Contact",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (opt != JOptionPane.OK_OPTION) {
            return;
        }
        c.setName(name.getText().trim());
        c.setEmail(email.getText().trim());
        c.setFavorite(fav.isSelected());
        if (isNew) {
            contacts.add(c);
        }
        saveContacts();
        refreshLists();
        setStatus((isNew ? "Added " : "Updated ") + c.getName());
    }

    private void removeSelectedContact() {
        Contact c = contactList.getSelectedValue();
        if (c == null) {
            return;
        }
        contacts.remove(c);
        saveContacts();
        refreshLists();
        setStatus("Removed " + c.getName());
    }

    private void clearHistory() {
        history.clear();
        saveHistory();
        refreshLists();
        setStatus("Cleared recent calls");
    }

    private static void addFormRow(JPanel form, GridBagConstraints c, int row,
                                   String labelText, JComponent field) {
        c.gridx = 0; c.gridy = row; c.weightx = 0;
        form.add(new JLabel(labelText), c);
        c.gridx = 1; c.gridy = row; c.weightx = 1.0;
        form.add(field, c);
    }

    private static JComboBox<String> inheritCombo(int tri) {
        JComboBox<String> box = new JComboBox<>(
                new String[]{"Inherit from settings", "On", "Off"});
        box.setSelectedIndex(tri == ConferenceRoom.INHERIT ? 0 : (tri == 1 ? 1 : 2));
        return box;
    }

    private static int comboToTri(int index) {
        return switch (index) {
            case 1 -> 1;
            case 2 -> 0;
            default -> ConferenceRoom.INHERIT;
        };
    }

    // ------------------------------------------------------------------
    // Settings dialog
    // ------------------------------------------------------------------

    private void showSettingsDialog() {
        JTextField domain = new JTextField(settings.getDefaultDomain());
        JTextField displayName = new JTextField(settings.getDisplayName());
        JTextField email = new JTextField(settings.getEmail());
        JComboBox<VideoConferenceSettings.LaunchMode> launch =
                new JComboBox<>(VideoConferenceSettings.LaunchMode.values());
        launch.setSelectedItem(settings.getLaunchMode());
        JTextField external = new JTextField(settings.getExternalCommand());
        JCheckBox audioMuted = new JCheckBox("Join with microphone muted",
                settings.isStartWithAudioMuted());
        JCheckBox videoMuted = new JCheckBox("Join with camera off",
                settings.isStartWithVideoMuted());
        JCheckBox prejoin = new JCheckBox("Skip the pre-join device screen",
                settings.isDisablePrejoinPage());
        JTextField histLimit = new JTextField(String.valueOf(settings.getHistoryLimit()));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        addFormRow(form, c, row++, "Default server", domain);
        addFormRow(form, c, row++, "Display name", displayName);
        addFormRow(form, c, row++, "E-mail", email);
        addFormRow(form, c, row++, "Launch meeting in", launch);
        addFormRow(form, c, row++, "External command", external);
        c.gridx = 1; c.gridy = row++; form.add(audioMuted, c);
        c.gridx = 1; c.gridy = row++; form.add(videoMuted, c);
        c.gridx = 1; c.gridy = row++; form.add(prejoin, c);
        addFormRow(form, c, row, "History entries", histLimit);

        int opt = JOptionPane.showConfirmDialog(this, form, "Video Conference Settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (opt != JOptionPane.OK_OPTION) {
            return;
        }
        settings.setDefaultDomain(JitsiUrlBuilder.normalizeDomain(domain.getText()));
        settings.setDisplayName(displayName.getText());
        settings.setEmail(email.getText());
        settings.setLaunchMode(
                (VideoConferenceSettings.LaunchMode) launch.getSelectedItem());
        settings.setExternalCommand(external.getText());
        settings.setStartWithAudioMuted(audioMuted.isSelected());
        settings.setStartWithVideoMuted(videoMuted.isSelected());
        settings.setDisablePrejoinPage(prejoin.isSelected());
        try {
            settings.setHistoryLimit(Integer.parseInt(histLimit.getText().trim()));
        } catch (NumberFormatException ex) {
            // keep the previous limit
        }
        saveSettings();

        domainField.setText(settings.getDefaultDomain());
        audioToggle.setSelected(settings.isStartWithAudioMuted());
        videoToggle.setSelected(settings.isStartWithVideoMuted());
        preview.setVideoEnabled(!settings.isStartWithVideoMuted());
        updateIdentityLabel();
        setStatus("Settings saved");
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void saveRooms() { store.saveRooms(rooms); }
    private void saveContacts() { store.saveContacts(contacts); }
    private void saveHistory() { store.saveHistory(history, settings.getHistoryLimit()); }
    private void saveSettings() { store.saveSettings(settings); }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    /**
     * Splits a command line on whitespace, honouring double quotes so paths with
     * spaces stay in one argument. Quotes are removed from the result.
     *
     * @param command the command string
     * @return the argument tokens
     */
    static List<String> splitCommand(String command) {
        List<String> out = new ArrayList<>();
        if (command == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(ch) && !inQuotes) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Sets the callback invoked when the user closes the window (Frame3D host). */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /** Stops the camera preview, persists state and runs the close hook. */
    public void shutdown() {
        preview.dispose();
        saveSettings();
        if (onClose != null) {
            onClose.run();
        }
    }

    // ------------------------------------------------------------------
    // Test hooks (package-private)
    // ------------------------------------------------------------------

    JLabel statusLbl() { return statusLabel; }
    JTextField roomField() { return roomField; }
    JTextField domainField() { return domainField; }
    CameraPreview preview() { return preview; }
    List<ConferenceRoom> rooms() { return rooms; }
    List<Contact> contacts() { return contacts; }
    List<CallHistoryEntry> history() { return history; }
    VideoConferenceSettings settings() { return settings; }
    DefaultListModel<ConferenceRoom> roomModel() { return roomModel; }
    String lastJoinUrl() { return lastJoinUrl; }
    CallHistoryEntry.Outcome lastOutcome() { return lastOutcome; }

    /** Package-private accessor for the timestamp formatter used by list cells. */
    static String formatTimestamp(long epochMs) {
        return (epochMs <= 0) ? "" : TS.format(new Date(epochMs));
    }

    /** Renders a recent-call entry as "room name  \u00b7  timestamp  \u00b7  outcome". */
    private static final class HistoryCellRenderer extends JLabel
            implements ListCellRenderer<CallHistoryEntry> {
        @Override
        public java.awt.Component getListCellRendererComponent(
                JList<? extends CallHistoryEntry> list, CallHistoryEntry value,
                int index, boolean isSelected, boolean cellHasFocus) {
            if (value == null) {
                setText("");
                return this;
            }
            String ts = formatTimestamp(value.getJoinedAtEpochMs());
            String name = (value.getRoomName() == null || value.getRoomName().isEmpty())
                    ? value.getUrl() : value.getRoomName();
            setText(name + (ts.isEmpty() ? "" : "  \u00b7  " + ts));
            setOpaque(true);
            setBackground(isSelected ? ACCENT : list.getBackground());
            setForeground(isSelected ? Color.WHITE : TEXT);
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            return this;
        }
    }
}
