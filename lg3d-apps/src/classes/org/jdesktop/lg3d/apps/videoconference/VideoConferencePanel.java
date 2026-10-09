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
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.datatransfer.StringSelection;
import java.awt.Toolkit;
import java.net.URI;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.ListCellRenderer;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.jdesktop.lg3d.apps.p2p.FileTransfer;
import org.jdesktop.lg3d.apps.p2p.IdentityStore;
import org.jdesktop.lg3d.apps.p2p.LanDiscovery;
import org.jdesktop.lg3d.apps.p2p.P2pNode;
import org.jdesktop.lg3d.contacts.Contact;
import org.jdesktop.lg3d.contacts.ContactStore;

/**
 * The Video Conference application's Swing face: a Jitsi Meet conference client.
 *
 * <p>It is the lobby, address book and launcher for audio/video meetings. The
 * user types (or picks) a room name, optionally sets a display name/e-mail and
 * mute-on-join preferences, and presses <b>Join</b>. The client builds the
 * correct Jitsi Meet deep link via {@link JitsiUrlBuilder} and hands it to the
 * system browser ({@link java.awt.Desktop#browse}) or an external meeting
 * command, where the real WebRTC audio/video session runs. Saved rooms, recent
 * calls and settings persist as JSON under {@code ~/.lg3d/videoconference} via
 * {@link VideoConferenceStore}; the Contacts tab is a live view of the
 * desktop-wide address book ({@code ~/.lg3d/contacts} via
 * {@code org.jdesktop.lg3d.contacts.ContactStore}) that the Contacts app edits,
 * so one address book is shared across the whole desktop.</p>
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
    private final ContactStore addressBook;
    private VideoConferenceSettings settings;

    private final List<ConferenceRoom> rooms = new ArrayList<>();
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

    // P2P side-channel: encrypted signaling/chat/files; the A/V stays in Jitsi.
    private final JTabbedPane sidebarTabs = new JTabbedPane();
    private final DefaultListModel<String> p2pPeerModel = new DefaultListModel<>();
    private final JList<String> p2pPeerList = new JList<>(p2pPeerModel);
    /** Index-aligned with {@link #p2pPeerModel}: a {@code P2pNode.Peer} or a
     *  {@code LanDiscovery.DiscoveredPeer}. */
    private final List<Object> p2pPeerEntries = new ArrayList<>();
    private final JLabel p2pIdentityLabel = new JLabel(" ");
    private final JButton p2pStartButton = new JButton("Start P2P");
    private final JTextArea p2pChatLog = new JTextArea(6, 18);
    private final JTextField p2pChatInput = new JTextField();
    private volatile P2pSideChannel p2p;

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
        this(store, new ContactStore());
    }

    /**
     * Creates the panel with an explicit store and address book (tests point
     * both at temp directories).
     *
     * @param store       the persistence backend
     * @param addressBook the shared desktop-wide contact store
     */
    public VideoConferencePanel(VideoConferenceStore store, ContactStore addressBook) {
        super(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        setBackground(BACKDROP);
        setOpaque(true);

        this.store = store;
        this.addressBook = addressBook;
        this.settings = store.loadSettings();
        this.capture = CameraCapture.detect();

        rooms.addAll(store.loadRooms());
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
        JTabbedPane tabs = sidebarTabs;
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
        tabs.addTab("Direct (P2P)", buildP2pPanel());

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
        for (Contact ct : addressBook.all()) {
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
        JTextField name = new JTextField(isNew ? "" : editableName(existing));
        JTextField email = new JTextField(isNew ? "" : existing.primaryEmail());
        JCheckBox fav = new JCheckBox("Favorite", !isNew && existing.isFavorite());
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
        Contact c = isNew ? new Contact() : existing;
        applyName(c, name.getText().trim());
        String emailText = email.getText().trim();
        c.setEmails(emailText.isEmpty() ? new ArrayList<>() : List.of(emailText));
        c.setFavorite(fav.isSelected());
        if (isNew) {
            addressBook.add(c);
        } else {
            addressBook.update(c);
        }
        refreshLists();
        setStatus((isNew ? "Added " : "Updated ") + c.displayName());
    }

    /** The contact's name as one editable string (nickname/e-mail fallback). */
    private static String editableName(Contact c) {
        String full = (c.getFirstName() + " " + c.getLastName()).trim();
        return full.isEmpty() ? c.displayName() : full;
    }

    /** Splits one name field into the store's first/last name pair. */
    private static void applyName(Contact c, String full) {
        int sp = full.indexOf(' ');
        if (sp < 0) {
            c.setFirstName(full);
            c.setLastName("");
        } else {
            c.setFirstName(full.substring(0, sp));
            c.setLastName(full.substring(sp + 1).trim());
        }
    }

    private void removeSelectedContact() {
        Contact c = contactList.getSelectedValue();
        if (c == null) {
            return;
        }
        addressBook.delete(c.getId());
        refreshLists();
        setStatus("Removed " + c.displayName());
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
        JTextField p2pPort = new JTextField(String.valueOf(settings.getP2pListenPort()));
        p2pPort.setToolTipText("TCP port the encrypted P2P side-channel listens on (0 = ephemeral)."
                + " Set a fixed, port-forwarded port to be reachable from the internet.");
        JCheckBox p2pDiscovery = new JCheckBox("Discover P2P peers on the LAN",
                settings.isP2pDiscoveryEnabled());

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
        addFormRow(form, c, row++, "History entries", histLimit);
        addFormRow(form, c, row++, "P2P listen port", p2pPort);
        c.gridx = 1; c.gridy = row; form.add(p2pDiscovery, c);

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
        try {
            settings.setP2pListenPort(Integer.parseInt(p2pPort.getText().trim()));
        } catch (NumberFormatException ex) {
            // keep the previous port
        }
        settings.setP2pDiscoveryEnabled(p2pDiscovery.isSelected());
        saveSettings();

        domainField.setText(settings.getDefaultDomain());
        audioToggle.setSelected(settings.isStartWithAudioMuted());
        videoToggle.setSelected(settings.isStartWithVideoMuted());
        preview.setVideoEnabled(!settings.isStartWithVideoMuted());
        updateIdentityLabel();
        setStatus("Settings saved");
    }

    // ------------------------------------------------------------------
    // Direct (P2P) side-channel: encrypted signaling, chat and files
    // ------------------------------------------------------------------

    /**
     * Builds the "Direct (P2P)" tab: our identity, the peer list (connected and
     * LAN-discovered) with connect/invite/file actions, and a small encrypted
     * chat log. The audio/video path is unchanged &mdash; this is only the secure
     * signaling/chat/file layer, as the note at the foot of the tab states.
     */
    private JComponent buildP2pPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel north = new JPanel(new BorderLayout(0, 4));
        north.setOpaque(false);
        p2pIdentityLabel.setForeground(TEXT_DIM);
        p2pIdentityLabel.setText("P2P off \u2014 press Start to listen for peers");
        north.add(p2pIdentityLabel, BorderLayout.CENTER);
        JPanel northBtns = new JPanel(new GridLayout(1, 2, 4, 0));
        northBtns.setOpaque(false);
        p2pStartButton.addActionListener(e -> toggleP2p());
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refreshP2pPeers());
        northBtns.add(p2pStartButton);
        northBtns.add(refresh);
        north.add(northBtns, BorderLayout.SOUTH);
        panel.add(north, BorderLayout.NORTH);

        p2pPeerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel peerWrap = new JPanel(new BorderLayout());
        peerWrap.setOpaque(false);
        peerWrap.setBorder(BorderFactory.createTitledBorder("Peers"));
        peerWrap.add(new JScrollPane(p2pPeerList), BorderLayout.CENTER);
        JPanel peerBtns = new JPanel(new GridLayout(1, 3, 4, 0));
        peerBtns.setOpaque(false);
        peerBtns.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JButton connect = new JButton("Connect");
        connect.setToolTipText("Connect to the selected LAN-discovered peer");
        connect.addActionListener(e -> connectSelectedPeer());
        JButton invite = new JButton("Invite");
        invite.setToolTipText("Send a meeting invite to the selected peer (or all connected)");
        invite.addActionListener(e -> sendInviteToSelectedPeer());
        JButton file = new JButton("File");
        file.setToolTipText("Send a file to the selected connected peer");
        file.addActionListener(e -> sendFileToSelectedPeer());
        peerBtns.add(connect);
        peerBtns.add(invite);
        peerBtns.add(file);
        peerWrap.add(peerBtns, BorderLayout.SOUTH);

        p2pChatLog.setEditable(false);
        p2pChatLog.setLineWrap(true);
        p2pChatLog.setWrapStyleWord(true);
        p2pChatLog.setBackground(CARD);
        p2pChatLog.setForeground(TEXT);
        p2pChatLog.setCaretColor(TEXT);
        JPanel chatWrap = new JPanel(new BorderLayout(0, 4));
        chatWrap.setOpaque(false);
        chatWrap.setBorder(BorderFactory.createTitledBorder("Secure chat"));
        chatWrap.add(new JScrollPane(p2pChatLog), BorderLayout.CENTER);
        JPanel chatInput = new JPanel(new BorderLayout(4, 0));
        chatInput.setOpaque(false);
        JButton send = new JButton("Send");
        send.addActionListener(e -> sendP2pChat());
        p2pChatInput.addActionListener(e -> sendP2pChat());
        chatInput.add(p2pChatInput, BorderLayout.CENTER);
        chatInput.add(send, BorderLayout.EAST);
        chatWrap.add(chatInput, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, peerWrap, chatWrap);
        split.setResizeWeight(0.5);
        split.setContinuousLayout(true);
        split.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        panel.add(split, BorderLayout.CENTER);

        JLabel hint = new JLabel("<html><i>Encrypted signaling, chat and files \u2014 "
                + "audio/video still runs through Jitsi in your browser.</i></html>");
        hint.setForeground(TEXT_DIM);
        panel.add(hint, BorderLayout.SOUTH);
        return panel;
    }

    /** Creates the side-channel on demand; construction is inert (no socket). */
    private synchronized P2pSideChannel ensureP2p() {
        if (p2p == null) {
            Path dir = store.getConfigDir().resolve("p2p-files");
            p2p = new P2pSideChannel(new IdentityStore(), dir, new PanelP2pListener());
        }
        return p2p;
    }

    /** Starts the side-channel if stopped, stops it if running. */
    void toggleP2p() {
        P2pSideChannel ch = p2p;
        if (ch != null && ch.isRunning()) {
            stopP2p();
        } else {
            startP2p();
        }
    }

    /** Binds the listen socket and begins LAN discovery (never in headless). */
    void startP2p() {
        if (GraphicsEnvironment.isHeadless()) {
            setStatus("The P2P side-channel is unavailable in headless mode");
            return;
        }
        P2pSideChannel ch = ensureP2p();
        ch.setDiscoveryEnabled(settings.isP2pDiscoveryEnabled());
        if (ch.start(settings.getDisplayName(), settings.getP2pListenPort())) {
            p2pStartButton.setText("Stop P2P");
            updateP2pIdentity();
            refreshP2pPeers();
        }
    }

    /** Closes the side-channel and releases its socket and threads. */
    void stopP2p() {
        P2pSideChannel ch = p2p;
        if (ch != null) {
            ch.close();
        }
        p2p = null;
        p2pStartButton.setText("Start P2P");
        updateP2pIdentity();
        refreshP2pPeers();
        setStatus("P2P side-channel stopped");
    }

    private void updateP2pIdentity() {
        P2pSideChannel ch = p2p;
        if (ch == null || !ch.isRunning()) {
            p2pIdentityLabel.setText("P2P off \u2014 press Start to listen for peers");
            return;
        }
        p2pIdentityLabel.setText("<html>Our fingerprint: " + ch.getOurFingerprint()
                + "<br>Listening on port " + ch.getPort() + "</html>");
    }

    /** Rebuilds the peer list from the connected peers then the discovered ones. */
    void refreshP2pPeers() {
        int selected = p2pPeerList.getSelectedIndex();
        p2pPeerModel.clear();
        p2pPeerEntries.clear();
        P2pSideChannel ch = p2p;
        if (ch != null) {
            for (P2pNode.Peer peer : ch.getPeers()) {
                p2pPeerEntries.add(peer);
                p2pPeerModel.addElement("\u25cf " + P2pSideChannel.displayName(peer)
                        + "  [" + shortFp(peer.getFingerprint()) + "]");
            }
            for (LanDiscovery.DiscoveredPeer dp : ch.getDiscoveredPeers()) {
                p2pPeerEntries.add(dp);
                p2pPeerModel.addElement("\u25cb " + describeDiscovered(dp));
            }
        }
        if (selected >= 0 && selected < p2pPeerModel.getSize()) {
            p2pPeerList.setSelectedIndex(selected);
        }
    }

    /** Dials the selected LAN-discovered peer. */
    void connectSelectedPeer() {
        P2pSideChannel ch = p2p;
        if (ch == null || !ch.isRunning()) {
            setStatus("Start the P2P side-channel first");
            return;
        }
        int idx = p2pPeerList.getSelectedIndex();
        if (idx < 0 || !(p2pPeerEntries.get(idx) instanceof LanDiscovery.DiscoveredPeer dp)) {
            setStatus("Select a discovered peer to connect");
            return;
        }
        ch.connectToPeer(dp.getHostAddress(), dp.getPort());
        setStatus("Connecting to " + dp.getHostAddress() + ":" + dp.getPort());
    }

    /** @return the Jitsi share URL for the current room/domain fields, or null. */
    String buildInviteUrl() {
        return JitsiUrlBuilder.buildShareUrl(currentRoomFromInput(), settings);
    }

    /**
     * Invites the selected connected peer (or, with no selection, every connected
     * peer) to the current room over the encrypted side-channel. A discovered but
     * not-yet-connected peer is dialled first.
     */
    void sendInviteToSelectedPeer() {
        P2pSideChannel ch = p2p;
        if (ch == null || !ch.isRunning()) {
            setStatus("Start the P2P side-channel first");
            return;
        }
        String url = buildInviteUrl();
        if (url == null) {
            setStatus("Enter a room name to invite");
            return;
        }
        ConferenceRoom room = currentRoomFromInput();
        int idx = p2pPeerList.getSelectedIndex();
        if (idx < 0) {
            int n = ch.broadcastInvite(room.getName(), url);
            setStatus(n > 0
                    ? "Invited " + n + " peer(s) to " + room.getName()
                    : "No connected peers to invite");
            return;
        }
        Object entry = p2pPeerEntries.get(idx);
        if (entry instanceof P2pNode.Peer peer) {
            boolean ok = ch.sendInvite(peer.getFingerprint(), room.getName(), url);
            setStatus(ok
                    ? "Invite sent to " + P2pSideChannel.displayName(peer)
                    : "Could not send the invite");
        } else if (entry instanceof LanDiscovery.DiscoveredPeer dp) {
            ch.connectToPeer(dp.getHostAddress(), dp.getPort());
            setStatus("Connecting to " + describeDiscovered(dp) + " before inviting");
        }
    }

    /** Offers a file to the selected connected peer (headless: status only). */
    void sendFileToSelectedPeer() {
        P2pSideChannel ch = p2p;
        if (ch == null || !ch.isRunning()) {
            setStatus("Start the P2P side-channel first");
            return;
        }
        int idx = p2pPeerList.getSelectedIndex();
        if (idx < 0 || !(p2pPeerEntries.get(idx) instanceof P2pNode.Peer peer)) {
            setStatus("Select a connected peer to send a file");
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            setStatus("The file chooser is unavailable in headless mode");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Send file to " + P2pSideChannel.displayName(peer));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        boolean ok = ch.offerFile(peer.getFingerprint(), file);
        setStatus(ok
                ? "Offering " + chooser.getSelectedFile().getName()
                        + " to " + P2pSideChannel.displayName(peer)
                : "Could not offer the file");
    }

    /** Sends the chat input to the selected peer, or broadcasts to all. */
    void sendP2pChat() {
        String text = p2pChatInput.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        P2pSideChannel ch = p2p;
        if (ch == null || !ch.isRunning()) {
            setStatus("Start the P2P side-channel first");
            return;
        }
        int idx = p2pPeerList.getSelectedIndex();
        if (idx >= 0 && p2pPeerEntries.get(idx) instanceof P2pNode.Peer peer) {
            ch.sendChat(peer.getFingerprint(), text);
            appendP2pChat("you \u2192 " + P2pSideChannel.displayName(peer) + ": " + text);
        } else {
            int n = ch.broadcastChat(text);
            appendP2pChat("you \u2192 " + n + " peer(s): " + text);
        }
        p2pChatInput.setText("");
    }

    /**
     * Applies a received invite: fills the room (and the domain, when the share
     * URL carries one) and runs the existing {@link #join()} flow.
     *
     * @param room the invited room name
     * @param url  the peer's Jitsi share URL (may be null)
     */
    void applyInvite(String room, String url) {
        if (room != null && !room.isBlank()) {
            roomField.setText(room.trim());
        }
        if (url != null && !url.isBlank()) {
            try {
                URI u = URI.create(url);
                if (u.getHost() != null && !u.getHost().isBlank()) {
                    domainField.setText(u.getHost());
                }
            } catch (RuntimeException ignored) {
                // keep the current domain if the URL cannot be parsed
            }
        }
        join();
    }

    private void promptJoinInvite(String peer, String room, String url) {
        appendP2pChat(peer + " invites you to \"" + room + "\"");
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        int opt = JOptionPane.showConfirmDialog(this,
                peer + " invites you to join \"" + room + "\".",
                "P2P meeting invite", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (opt == JOptionPane.YES_OPTION) {
            applyInvite(room, url);
        }
    }

    private void promptAcceptP2pFile(String peer, FileTransfer transfer) {
        appendP2pChat(peer + " offers \"" + transfer.getFileName() + "\"");
        P2pSideChannel ch = p2p;
        if (ch == null) {
            return;
        }
        boolean accept;
        if (GraphicsEnvironment.isHeadless()) {
            accept = false;
        } else {
            accept = JOptionPane.showConfirmDialog(this,
                    peer + " offers \"" + transfer.getFileName() + "\". Accept?",
                    "Incoming file", JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
        }
        if (accept) {
            ch.acceptFile(transfer.getId());
        } else {
            ch.rejectFile(transfer.getId(), "Declined");
        }
    }

    private void appendP2pChat(String line) {
        p2pChatLog.append(line + "\n");
        p2pChatLog.setCaretPosition(p2pChatLog.getDocument().getLength());
    }

    /** A one-line description of a discovered LAN peer. */
    static String describeDiscovered(LanDiscovery.DiscoveredPeer dp) {
        if (dp == null) {
            return "";
        }
        String nick = (dp.getNickname() == null || dp.getNickname().isBlank())
                ? "peer" : dp.getNickname();
        return nick + "  " + dp.getHostAddress() + ":" + dp.getPort();
    }

    /** The first eight hex digits of a fingerprint, for a compact list label. */
    static String shortFp(String fingerprint) {
        if (fingerprint == null) {
            return "";
        }
        String tag = fingerprint.replace(":", "");
        return tag.substring(0, Math.min(8, tag.length()));
    }

    /** Runs {@code r} on the EDT (immediately if already there). */
    private static void edt(Runnable r) {
        if (EventQueue.isDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    /** Marshals side-channel events onto the EDT before touching a widget. */
    private final class PanelP2pListener implements P2pSideChannel.Listener {
        @Override
        public void onPeersChanged() {
            edt(() -> refreshP2pPeers());
        }

        @Override
        public void onChat(String peerName, String text, boolean action) {
            edt(() -> appendP2pChat(action ? "* " + peerName + " " + text : peerName + ": " + text));
        }

        @Override
        public void onInvite(String peerName, String room, String url) {
            edt(() -> promptJoinInvite(peerName, room, url));
        }

        @Override
        public void onFileOffer(String peerName, FileTransfer transfer) {
            edt(() -> promptAcceptP2pFile(peerName, transfer));
        }

        @Override
        public void onFileProgress(String peerName, FileTransfer transfer) {
            edt(() -> setStatus(String.format(Locale.ROOT, "P2P %s %s \u2014 %d%%",
                    transfer.getDirection() == FileTransfer.Direction.SEND
                            ? "sending" : "receiving",
                    transfer.getFileName(), Math.round(transfer.getProgress() * 100))));
        }

        @Override
        public void onFileComplete(String peerName, FileTransfer transfer) {
            edt(() -> appendP2pChat("File " + transfer.getFileName() + " completed"
                    + (transfer.isVerified() ? " (verified)" : "")));
        }

        @Override
        public void onFileClosed(String peerName, FileTransfer transfer) {
            edt(() -> appendP2pChat("File " + transfer.getFileName() + " " + transfer.getState()));
        }

        @Override
        public void onStatus(String status) {
            edt(() -> setStatus(status));
        }

        @Override
        public void onError(String error) {
            edt(() -> setStatus("P2P: " + error));
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void saveRooms() { store.saveRooms(rooms); }
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

    /** Stops the camera preview, closes the P2P side-channel, persists state and runs the close hook. */
    public void shutdown() {
        P2pSideChannel ch = p2p;
        if (ch != null) {
            ch.close();
            p2p = null;
        }
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
    List<Contact> contacts() { return addressBook.all(); }
    ContactStore addressBook() { return addressBook; }
    List<CallHistoryEntry> history() { return history; }
    VideoConferenceSettings settings() { return settings; }
    DefaultListModel<ConferenceRoom> roomModel() { return roomModel; }
    String lastJoinUrl() { return lastJoinUrl; }
    CallHistoryEntry.Outcome lastOutcome() { return lastOutcome; }

    JTabbedPane sidebarTabs() { return sidebarTabs; }
    P2pSideChannel p2p() { return p2p; }
    DefaultListModel<String> p2pPeerModel() { return p2pPeerModel; }
    JList<String> p2pPeerList() { return p2pPeerList; }
    JTextField p2pChatInput() { return p2pChatInput; }
    String p2pChatText() { return p2pChatLog.getText(); }

    /** Injects a side-channel (used by tests; bypasses the lazy create). */
    void setP2pForTest(P2pSideChannel channel) { this.p2p = channel; }

    /** Appends a line to the P2P chat log exactly as an inbound event would. */
    void appendP2pChatForTest(String line) { appendP2pChat(line); }

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
