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
package org.jdesktop.lg3d.apps.ssh;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

/**
 * Production SSH client panel: tabbed multi-session terminal with connection
 * profiles, ANSI terminal emulation, keepalive, port forwarding, and full
 * host-key verification.
 *
 * <p>Designed to run both inside the lg3d 3D desktop (hosted on a SwingNode via
 * {@link Ssh}) and in the 2D/Swing desktop (as an MDI internal frame via
 * {@code Desktop2DAppRegistry}). Must not call {@code System.exit}.</p>
 *
 * <h3>Features</h3>
 * <ul>
 *   <li>Multiple concurrent SSH sessions in tabs</li>
 *   <li>Full ANSI/VT100 terminal emulation with 256-color and true-color support</li>
 *   <li>SSH key-based authentication (RSA, Ed25519, ECDSA)</li>
 *   <li>Trust-on-first-use host key verification (known_hosts)</li>
 *   <li>Connection profiles with JSON persistence</li>
 *   <li>Keepalive with configurable interval</li>
 *   <li>Local, remote, and dynamic (SOCKS5) port forwarding</li>
 *   <li>Command history (per session)</li>
 *   <li>Font zoom (Ctrl+scroll / Ctrl+/-)</li>
 *   <li>Configurable scrollback buffer</li>
 *   <li>Compression support (zlib)</li>
 *   <li>X11 and agent forwarding</li>
 *   <li>Modern cipher/KEX/MAC preference (ChaCha20, AES-GCM, Curve25519)</li>
 * </ul>
 */
public class SshPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    // ------------------------------------------------------------------
    // UI components
    // ------------------------------------------------------------------

    private final JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);
    private final JLabel statusLabel = new JLabel("Ready");
    private final JLabel connectionInfoLabel = new JLabel(" ");
    private final JToolBar toolBar = new JToolBar();

    // Toolbar actions
    private final JButton newConnectionBtn = new JButton("New Connection");
    private final JButton disconnectBtn = new JButton("Disconnect");
    private final JButton reconnectBtn = new JButton("Reconnect");
    private final JButton profilesBtn = new JButton("Profiles");
    private final JButton settingsBtn = new JButton("Settings");
    private final JButton clearBtn = new JButton("Clear");
    private final JButton zoomInBtn = new JButton("A+");
    private final JButton zoomOutBtn = new JButton("A-");

    // Profile sidebar
    private final DefaultListModel<SshProfile> profileListModel = new DefaultListModel<>();
    private final JList<SshProfile> profileList = new JList<>(profileListModel);
    private final JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

    // State
    private final SshProfileStore store = new SshProfileStore();
    private final List<SshProfile> profiles = new ArrayList<>();
    private SshProfileStore.SshSettings settings;
    private Runnable onClose;

    // Status timer (updates connection duration / bytes)
    private final Timer statusTimer;

    /** Creates the SSH client panel with no-arg constructor (required by Desktop2DAppRegistry). */
    public SshPanel() {
        settings = store.loadSettings();
        profiles.addAll(store.loadProfiles());
        profileListModel.clear();
        for (SshProfile p : profiles) profileListModel.addElement(p);

        setupUI();
        setupToolbar();
        setupKeyBindings();
        setupProfileList();

        statusTimer = new Timer(1000, e -> updateStatusBar());
        statusTimer.start();

        // If we have saved profiles, show the sidebar; otherwise show connect dialog
        if (profiles.isEmpty()) {
            splitPane.setDividerLocation(0);
        } else {
            splitPane.setDividerLocation(180);
        }
    }

    // ------------------------------------------------------------------
    // UI setup
    // ------------------------------------------------------------------

    private void setupUI() {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(900, 600));

        // Tabbed pane for sessions
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabbedPane.addChangeListener(e -> {
            updateConnectionInfo();
            SessionTab tab = getCurrentTab();
            if (tab != null) tab.terminal.requestFocusInWindow();
        });

        // Split pane: profile sidebar | terminal tabs
        splitPane.setLeftComponent(buildProfileSidebar());
        splitPane.setRightComponent(tabbedPane);
        splitPane.setDividerSize(4);
        splitPane.setResizeWeight(0.0);
        splitPane.setBorder(null);

        add(toolBar, BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
    }

    private JComponent buildProfileSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEmptyBorder(2, 2, 2, 2),
                "Saved Profiles", TitledBorder.LEFT, TitledBorder.TOP));
        sidebar.setMinimumSize(new Dimension(0, 0));
        sidebar.setPreferredSize(new Dimension(180, 0));

        profileList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        profileList.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        JScrollPane listScroll = new JScrollPane(profileList);
        sidebar.add(listScroll, BorderLayout.CENTER);

        // Quick-connect button at bottom
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 4));
        JButton connectBtn = new JButton("Connect");
        connectBtn.addActionListener(e -> connectSelectedProfile());
        JButton editBtn = new JButton("Edit");
        editBtn.addActionListener(e -> editSelectedProfile());
        JButton deleteBtn = new JButton("Del");
        deleteBtn.addActionListener(e -> deleteSelectedProfile());
        btnPanel.add(connectBtn);
        btnPanel.add(editBtn);
        btnPanel.add(deleteBtn);
        sidebar.add(btnPanel, BorderLayout.SOUTH);

        // Double-click to connect
        profileList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) connectSelectedProfile();
            }
        });

        return sidebar;
    }

    private JComponent buildStatusBar() {
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY),
                new EmptyBorder(2, 6, 2, 6)));
        statusBar.setPreferredSize(new Dimension(0, 24));

        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        connectionInfoLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        connectionInfoLabel.setHorizontalAlignment(JLabel.RIGHT);

        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(connectionInfoLabel, BorderLayout.EAST);
        return statusBar;
    }

    private void setupToolbar() {
        toolBar.setFloatable(false);
        toolBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));

        newConnectionBtn.setToolTipText("Open a new SSH connection (Ctrl+T)");
        newConnectionBtn.addActionListener(e -> showConnectionDialog(null));

        disconnectBtn.setToolTipText("Disconnect the current session");
        disconnectBtn.setEnabled(false);
        disconnectBtn.addActionListener(e -> disconnectCurrentSession());

        reconnectBtn.setToolTipText("Reconnect the current session");
        reconnectBtn.setEnabled(false);
        reconnectBtn.addActionListener(e -> reconnectCurrentSession());

        profilesBtn.setToolTipText("Toggle profile sidebar");
        profilesBtn.addActionListener(e -> toggleSidebar());

        settingsBtn.setToolTipText("Client settings");
        settingsBtn.addActionListener(e -> showSettingsDialog());

        clearBtn.setToolTipText("Clear terminal scrollback");
        clearBtn.addActionListener(e -> {
            SessionTab tab = getCurrentTab();
            if (tab != null) tab.terminal.clear();
        });

        zoomInBtn.setToolTipText("Increase font size (Ctrl+Plus)");
        zoomInBtn.addActionListener(e -> changeFontSize(1));

        zoomOutBtn.setToolTipText("Decrease font size (Ctrl+Minus)");
        zoomOutBtn.addActionListener(e -> changeFontSize(-1));

        toolBar.add(newConnectionBtn);
        toolBar.add(disconnectBtn);
        toolBar.add(reconnectBtn);
        toolBar.addSeparator();
        toolBar.add(profilesBtn);
        toolBar.add(settingsBtn);
        toolBar.addSeparator();
        toolBar.add(clearBtn);
        toolBar.addSeparator();
        toolBar.add(zoomInBtn);
        toolBar.add(zoomOutBtn);
        toolBar.add(Box.createHorizontalGlue());
    }

    private void setupKeyBindings() {
        // Ctrl+T = new tab/connection
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK), "newConnection");
        getActionMap().put("newConnection", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { showConnectionDialog(null); }
        });

        // Ctrl+W = close tab
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK), "closeTab");
        getActionMap().put("closeTab", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { disconnectCurrentSession(); }
        });

        // Ctrl+Plus / Ctrl+Minus = zoom
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, InputEvent.CTRL_DOWN_MASK), "zoomIn");
        getActionMap().put("zoomIn", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { changeFontSize(1); }
        });
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK), "zoomOut");
        getActionMap().put("zoomOut", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { changeFontSize(-1); }
        });

        // Ctrl+Tab = next tab
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.CTRL_DOWN_MASK), "nextTab");
        getActionMap().put("nextTab", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                int idx = tabbedPane.getSelectedIndex();
                if (idx < tabbedPane.getTabCount() - 1) tabbedPane.setSelectedIndex(idx + 1);
                else if (tabbedPane.getTabCount() > 0) tabbedPane.setSelectedIndex(0);
            }
        });
    }

    private void setupProfileList() {
        profileList.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel label = new JLabel(value.getDisplayLabel());
            label.setOpaque(true);
            label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            label.setBorder(new EmptyBorder(4, 6, 4, 6));
            if (isSelected) {
                label.setBackground(list.getSelectionBackground());
                label.setForeground(list.getSelectionForeground());
            } else {
                label.setBackground(list.getBackground());
                label.setForeground(list.getForeground());
            }
            return label;
        });
    }

    // ------------------------------------------------------------------
    // Session tabs
    // ------------------------------------------------------------------

    /** Internal holder for a session tab's components. */
    private static final class SessionTab {
        final SshSession session;
        final SshTerminalPanel terminal;
        final JPanel panel;
        final List<String> commandHistory = new ArrayList<>();
        int historyIndex = -1;

        SessionTab(SshSession session, SshTerminalPanel terminal, JPanel panel) {
            this.session = session;
            this.terminal = terminal;
            this.panel = panel;
        }
    }

    private SessionTab getCurrentTab() {
        Component c = tabbedPane.getSelectedComponent();
        if (c == null) return null;
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getComponentAt(i) == c) {
                Object prop = ((JComponent) c).getClientProperty("sshTab");
                return (prop instanceof SessionTab) ? (SessionTab) prop : null;
            }
        }
        return null;
    }

    private void createSessionTab(SshProfile profile) {
        if (tabbedPane.getTabCount() >= settings.getTabLimit()) {
            statusLabel.setText("Tab limit reached (" + settings.getTabLimit() + ")");
            return;
        }

        SshSession session = new SshSession(profile);
        SshTerminalPanel terminal = new SshTerminalPanel();
        terminal.setFontSize(settings.getFontSize());
        terminal.setScrollbackLimit(settings.getScrollbackLines());
        terminal.setAntiAliasing(settings.isAntiAliasing());

        JPanel tabPanel = new JPanel(new BorderLayout());
        tabPanel.add(terminal.getScrollPane(), BorderLayout.CENTER);
        tabPanel.putClientProperty("sshTab", null); // set below

        SessionTab tab = new SessionTab(session, terminal, tabPanel);
        tabPanel.putClientProperty("sshTab", tab);

        // Wire terminal input -> session
        terminal.setInputListener(data -> session.sendBytes(data));

        // Wire session output -> terminal
        session.addListener(new SshSession.Listener() {
            @Override
            public void onOutput(byte[] data, int offset, int length) {
                terminal.feed(data, offset, length);
            }

            @Override
            public void onStateChange(SshSession.State newState, SshSession.State oldState) {
                SwingUtilities.invokeLater(() -> {
                    updateTabTitle(tab, profile);
                    updateToolbarState();
                    updateStatusBar();
                    if (newState == SshSession.State.CONNECTED) {
                        statusLabel.setText("Connected to " + profile.getDisplayLabel());
                        terminal.requestFocusInWindow();
                    }
                });
            }

            @Override
            public void onError(String message, Throwable cause) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Error: " + message);
                    terminal.feed(("\r\n\033[1;31m[ERROR] " + message + "\033[0m\r\n")
                            .getBytes(StandardCharsets.UTF_8), 0,
                            ("\r\n\033[1;31m[ERROR] " + message + "\033[0m\r\n").length());
                });
            }

            @Override
            public void onDisconnected(String reason) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Disconnected: " + reason);
                    updateTabTitle(tab, profile);
                    updateToolbarState();
                    terminal.feed(("\r\n\033[1;33m[Session ended: " + reason + "]\033[0m\r\n")
                            .getBytes(StandardCharsets.UTF_8), 0,
                            ("\r\n\033[1;33m[Session ended: " + reason + "]\033[0m\r\n").length());
                });
            }
        });

        // Handle terminal resize -> PTY resize
        terminal.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                terminal.autoResize();
                session.resizePty(terminal.getColumns(), terminal.getRows());
            }
        });

        tabbedPane.addTab(profile.getDisplayLabel(), tabPanel);
        tabbedPane.setSelectedComponent(tabPanel);

        // Close button on tab
        int tabIndex = tabbedPane.indexOfComponent(tabPanel);
        JLabel closeLabel = new JLabel(" \u2715 ");
        closeLabel.setToolTipText("Close this session");
        closeLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                closeSessionTab(tab);
            }
        });
        tabbedPane.setTabComponentAt(tabIndex, buildTabComponent(profile.getDisplayLabel(), closeLabel, tab));

        // Initiate connection
        session.connect();
    }

    private JPanel buildTabComponent(String title, JLabel closeLabel, SessionTab tab) {
        JPanel comp = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        comp.setOpaque(false);
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        comp.add(titleLabel);
        comp.add(closeLabel);
        comp.putClientProperty("tabTitleLabel", titleLabel);
        return comp;
    }

    private void updateTabTitle(SessionTab tab, SshProfile profile) {
        int idx = tabbedPane.indexOfComponent(tab.panel);
        if (idx < 0) return;
        Component tc = tabbedPane.getTabComponentAt(idx);
        if (tc instanceof JPanel jp) {
            Object lbl = jp.getClientProperty("tabTitleLabel");
            if (lbl instanceof JLabel titleLabel) {
                String prefix = switch (tab.session.getState()) {
                    case CONNECTED -> "\u25CF ";  // green circle
                    case CONNECTING -> "\u25D0 "; // half circle
                    case DISCONNECTING -> "\u25D1 ";
                    case DISCONNECTED -> "\u25CB "; // empty circle
                };
                titleLabel.setText(prefix + profile.getDisplayLabel());
            }
        }
    }

    private void closeSessionTab(SessionTab tab) {
        if (tab.session.isConnected()) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "Disconnect from " + tab.session.getProfile().getDisplayLabel() + "?",
                    "Confirm", JOptionPane.YES_NO_OPTION);
            if (answer != JOptionPane.YES_OPTION) return;
        }
        tab.session.close();
        tabbedPane.remove(tab.panel);
        updateToolbarState();
    }

    // ------------------------------------------------------------------
    // Connection dialog
    // ------------------------------------------------------------------

    private void showConnectionDialog(SshProfile existing) {
        SshProfile profile = (existing != null) ? existing.copy() : new SshProfile();
        if (profile.getUsername().isEmpty()) {
            profile.setUsername(System.getProperty("user.name", ""));
        }

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JTextField nameField = new JTextField(profile.getName(), 20);
        JTextField hostField = new JTextField(profile.getHost(), 20);
        JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(profile.getPort(), 1, 65535, 1));
        JTextField userField = new JTextField(profile.getUsername(), 15);
        JPasswordField passField = new JPasswordField(profile.getPassword(), 15);
        JComboBox<SshProfile.AuthMethod> authCombo = new JComboBox<>(SshProfile.AuthMethod.values());
        authCombo.setSelectedItem(profile.getAuthMethod());
        JTextField keyPathField = new JTextField(profile.getPrivateKeyPath(), 25);
        JPasswordField passFieldKey = new JPasswordField(profile.getPassphrase(), 15);
        JButton browseKeyBtn = new JButton("Browse...");
        JComboBox<String> termCombo = new JComboBox<>(new String[]{
                "xterm-256color", "xterm", "vt100", "linux", "screen-256color", "tmux-256color"
        });
        termCombo.setSelectedItem(profile.getTerminalType());
        JSpinner keepAliveSpinner = new JSpinner(new SpinnerNumberModel(profile.getKeepAliveSeconds(), 0, 300, 5));
        JCheckBox compressionCheck = new JCheckBox("Compression", profile.isCompression());
        JCheckBox agentFwdCheck = new JCheckBox("Agent Forwarding", profile.isAgentForwarding());
        JCheckBox x11FwdCheck = new JCheckBox("X11 Forwarding", profile.isX11Forwarding());
        JTextField localFwdField = new JTextField(profile.getLocalForwards(), 25);
        JTextField remoteFwdField = new JTextField(profile.getRemoteForwards(), 25);
        JTextField dynamicFwdField = new JTextField(profile.getDynamicForward(), 5);
        JTextField startupCmdField = new JTextField(profile.getStartupCommand(), 25);
        JCheckBox saveProfileCheck = new JCheckBox("Save as profile", existing == null);
        JCheckBox savePasswordCheck = new JCheckBox("Remember password (obfuscated, NOT encrypted)", profile.isSavePassword());

        // Enable/disable key fields based on auth method
        Runnable updateAuthFields = () -> {
            boolean keyAuth = authCombo.getSelectedItem() == SshProfile.AuthMethod.PUBLIC_KEY;
            keyPathField.setEnabled(keyAuth);
            browseKeyBtn.setEnabled(keyAuth);
            passFieldKey.setEnabled(keyAuth);
            passField.setEnabled(!keyAuth);
        };
        authCombo.addActionListener(e -> updateAuthFields.run());
        updateAuthFields.run();

        browseKeyBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(System.getProperty("user.home") + "/.ssh");
            fc.setDialogTitle("Select Private Key");
            if (fc.showOpenDialog(form) == JFileChooser.APPROVE_OPTION) {
                keyPathField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        int row = 0;
        addFormRow(form, gbc, row++, "Profile Name:", nameField);
        addFormRow(form, gbc, row++, "Host:", hostField);
        addFormRow(form, gbc, row++, "Port:", portSpinner);
        addFormRow(form, gbc, row++, "Username:", userField);
        addFormRow(form, gbc, row++, "Auth Method:", authCombo);
        addFormRow(form, gbc, row++, "Password:", passField);
        addFormRow(form, gbc, row++, "Private Key:", keyPathField, browseKeyBtn);
        addFormRow(form, gbc, row++, "Key Passphrase:", passFieldKey);
        addFormRow(form, gbc, row++, "Terminal Type:", termCombo);
        addFormRow(form, gbc, row++, "Keepalive (sec):", keepAliveSpinner);
        gbc.gridx = 1; gbc.gridy = row++;
        form.add(compressionCheck, gbc);
        gbc.gridy = row++;
        form.add(agentFwdCheck, gbc);
        gbc.gridy = row++;
        form.add(x11FwdCheck, gbc);
        addFormRow(form, gbc, row++, "Local Forward:", localFwdField);
        addFormRow(form, gbc, row++, "Remote Forward:", remoteFwdField);
        addFormRow(form, gbc, row++, "SOCKS5 Port:", dynamicFwdField);
        addFormRow(form, gbc, row++, "Startup Command:", startupCmdField);
        gbc.gridx = 1; gbc.gridy = row++;
        form.add(saveProfileCheck, gbc);
        gbc.gridy = row++;
        form.add(savePasswordCheck, gbc);

        JScrollPane formScroll = new JScrollPane(form);
        formScroll.setPreferredSize(new Dimension(500, 420));
        formScroll.setBorder(null);

        int result = JOptionPane.showConfirmDialog(this, formScroll,
                "SSH Connection", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        // Read back values
        profile.setName(nameField.getText().trim());
        profile.setHost(hostField.getText().trim());
        profile.setPort((Integer) portSpinner.getValue());
        profile.setUsername(userField.getText().trim());
        profile.setAuthMethod((SshProfile.AuthMethod) authCombo.getSelectedItem());
        profile.setPassword(new String(passField.getPassword()));
        profile.setPrivateKeyPath(keyPathField.getText().trim());
        profile.setPassphrase(new String(passFieldKey.getPassword()));
        profile.setTerminalType((String) termCombo.getSelectedItem());
        profile.setKeepAliveSeconds((Integer) keepAliveSpinner.getValue());
        profile.setCompression(compressionCheck.isSelected());
        profile.setAgentForwarding(agentFwdCheck.isSelected());
        profile.setX11Forwarding(x11FwdCheck.isSelected());
        profile.setLocalForwards(localFwdField.getText().trim());
        profile.setRemoteForwards(remoteFwdField.getText().trim());
        profile.setDynamicForward(dynamicFwdField.getText().trim());
        profile.setStartupCommand(startupCmdField.getText().trim());
        profile.setSavePassword(savePasswordCheck.isSelected());

        // Validate
        if (profile.getHost().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Host is required.", "Validation Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (profile.getUsername().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Username is required.", "Validation Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Save profile if requested
        if (saveProfileCheck.isSelected()) {
            if (existing != null) {
                // Update existing
                int idx = profiles.indexOf(existing);
                if (idx >= 0) profiles.set(idx, profile);
            } else {
                profiles.add(profile);
            }
            profileListModel.clear();
            for (SshProfile p : profiles) profileListModel.addElement(p);
            store.saveProfiles(profiles);
        }

        // Create and connect
        createSessionTab(profile);
    }

    private void addFormRow(JPanel panel, GridBagConstraints gbc, int row,
                            String label, JComponent field, JComponent... extra) {
        gbc.gridx = 0; gbc.gridy = row;
        gbc.weightx = 0; gbc.gridwidth = 1;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        if (extra.length > 0) {
            JPanel wrapper = new JPanel(new BorderLayout(4, 0));
            wrapper.add(field, BorderLayout.CENTER);
            wrapper.add(extra[0], BorderLayout.EAST);
            panel.add(wrapper, gbc);
        } else {
            panel.add(field, gbc);
        }
    }

    // ------------------------------------------------------------------
    // Settings dialog
    // ------------------------------------------------------------------

    private void showSettingsDialog() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JSpinner fontSpinner = new JSpinner(new SpinnerNumberModel(settings.getFontSize(), 8, 48, 1));
        JSpinner scrollbackSpinner = new JSpinner(new SpinnerNumberModel(settings.getScrollbackLines(), 100, 1000000, 1000));
        JSpinner tabLimitSpinner = new JSpinner(new SpinnerNumberModel(settings.getTabLimit(), 1, 50, 1));
        JCheckBox aaCheck = new JCheckBox("Anti-aliased text", settings.isAntiAliasing());
        JCheckBox strictHostCheck = new JCheckBox("Strict host key checking", settings.isStrictHostKeyChecking());
        JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(settings.getConnectTimeoutSeconds(), 5, 120, 5));

        int row = 0;
        addFormRow(form, gbc, row++, "Font Size:", fontSpinner);
        addFormRow(form, gbc, row++, "Scrollback Lines:", scrollbackSpinner);
        addFormRow(form, gbc, row++, "Tab Limit:", tabLimitSpinner);
        addFormRow(form, gbc, row++, "Connect Timeout (s):", timeoutSpinner);
        gbc.gridx = 1; gbc.gridy = row++;
        form.add(aaCheck, gbc);
        gbc.gridy = row++;
        form.add(strictHostCheck, gbc);

        int result = JOptionPane.showConfirmDialog(this, form,
                "SSH Client Settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            settings.setFontSize((Integer) fontSpinner.getValue());
            settings.setScrollbackLines((Integer) scrollbackSpinner.getValue());
            settings.setTabLimit((Integer) tabLimitSpinner.getValue());
            settings.setConnectTimeoutSeconds((Integer) timeoutSpinner.getValue());
            settings.setAntiAliasing(aaCheck.isSelected());
            settings.setStrictHostKeyChecking(strictHostCheck.isSelected());
            store.saveSettings(settings);

            // Apply font size to all open terminals
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                Component c = tabbedPane.getComponentAt(i);
                if (c instanceof JPanel jp) {
                    Object prop = jp.getClientProperty("sshTab");
                    if (prop instanceof SessionTab tab) {
                        tab.terminal.setFontSize(settings.getFontSize());
                        tab.terminal.setScrollbackLimit(settings.getScrollbackLines());
                        tab.terminal.setAntiAliasing(settings.isAntiAliasing());
                    }
                }
            }
            statusLabel.setText("Settings saved");
        }
    }

    // ------------------------------------------------------------------
    // Profile management
    // ------------------------------------------------------------------

    private void connectSelectedProfile() {
        SshProfile selected = profileList.getSelectedValue();
        if (selected == null) {
            showConnectionDialog(null);
            return;
        }
        // If password auth and no saved password, prompt
        if (selected.getAuthMethod() == SshProfile.AuthMethod.PASSWORD
                && (selected.getPassword() == null || selected.getPassword().isEmpty())) {
            JPasswordField pf = new JPasswordField();
            int result = JOptionPane.showConfirmDialog(this, pf,
                    "Password for " + selected.getDisplayLabel(),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) return;
            selected.setPassword(new String(pf.getPassword()));
        }
        createSessionTab(selected);
    }

    private void editSelectedProfile() {
        SshProfile selected = profileList.getSelectedValue();
        if (selected == null) return;
        showConnectionDialog(selected);
    }

    private void deleteSelectedProfile() {
        SshProfile selected = profileList.getSelectedValue();
        if (selected == null) return;
        int answer = JOptionPane.showConfirmDialog(this,
                "Delete profile \"" + selected.getDisplayLabel() + "\"?",
                "Confirm Delete", JOptionPane.YES_NO_OPTION);
        if (answer == JOptionPane.YES_OPTION) {
            profiles.remove(selected);
            profileListModel.removeElement(selected);
            store.saveProfiles(profiles);
            statusLabel.setText("Profile deleted");
        }
    }

    private void toggleSidebar() {
        if (splitPane.getDividerLocation() <= 0) {
            splitPane.setDividerLocation(180);
        } else {
            splitPane.setDividerLocation(0);
        }
    }

    // ------------------------------------------------------------------
    // Session operations
    // ------------------------------------------------------------------

    private void disconnectCurrentSession() {
        SessionTab tab = getCurrentTab();
        if (tab != null) {
            closeSessionTab(tab);
        }
    }

    private void reconnectCurrentSession() {
        SessionTab tab = getCurrentTab();
        if (tab != null && !tab.session.isConnected()) {
            SshProfile profile = tab.session.getProfile();
            closeSessionTab(tab);
            createSessionTab(profile);
        }
    }

    private void changeFontSize(int delta) {
        SessionTab tab = getCurrentTab();
        if (tab != null) {
            int newSize = tab.terminal.getFontSize() + delta;
            tab.terminal.setFontSize(newSize);
            settings.setFontSize(newSize);
            statusLabel.setText("Font size: " + newSize);
        }
    }

    // ------------------------------------------------------------------
    // Status bar
    // ------------------------------------------------------------------

    private void updateStatusBar() {
        SessionTab tab = getCurrentTab();
        if (tab == null || !tab.session.isConnected()) {
            connectionInfoLabel.setText(" ");
            return;
        }
        SshSession s = tab.session;
        long uptime = (System.currentTimeMillis() - s.getConnectedAt()) / 1000;
        String duration = String.format("%02d:%02d:%02d", uptime / 3600, (uptime % 3600) / 60, uptime % 60);
        connectionInfoLabel.setText(String.format("%s | %s | \u2193%s \u2191%s",
                s.getProfile().getDisplayLabel(),
                duration,
                formatBytes(s.getBytesReceived()),
                formatBytes(s.getBytesSent())));
    }

    private void updateConnectionInfo() {
        updateStatusBar();
        SessionTab tab = getCurrentTab();
        disconnectBtn.setEnabled(tab != null && tab.session.isConnected());
        reconnectBtn.setEnabled(tab != null && !tab.session.isConnected()
                && tab.session.getState() == SshSession.State.DISCONNECTED);
    }

    private void updateToolbarState() {
        SessionTab tab = getCurrentTab();
        disconnectBtn.setEnabled(tab != null && tab.session.isConnected());
        reconnectBtn.setEnabled(tab != null && tab.session.getState() == SshSession.State.DISCONNECTED);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fK", bytes / 1024.0);
        return String.format("%.1fM", bytes / (1024.0 * 1024.0));
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Shuts down all sessions and releases resources. */
    public void shutdown() {
        statusTimer.stop();
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof JPanel jp) {
                Object prop = jp.getClientProperty("sshTab");
                if (prop instanceof SessionTab tab) {
                    tab.session.close();
                }
            }
        }
        store.saveSettings(settings);
    }

    /** Sets the callback invoked when the user presses Close (for Frame3D hosting). */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    // ------------------------------------------------------------------
    // Test hooks (package-private)
    // ------------------------------------------------------------------

    int tabCount() { return tabbedPane.getTabCount(); }
    JTabbedPane tabs() { return tabbedPane; }
    JLabel statusLbl() { return statusLabel; }
    List<SshProfile> profiles() { return profiles; }
}
