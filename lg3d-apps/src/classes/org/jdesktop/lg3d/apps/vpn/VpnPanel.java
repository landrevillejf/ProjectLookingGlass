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
package org.jdesktop.lg3d.apps.vpn;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.jdesktop.lg3d.utils.system.NetworkCut;

/**
 * The VPN client's user interface: a profile dock (NetworkManager connections
 * discovered live, plus OpenVPN/WireGuard configs the user imports), a detail /
 * status centre with Connect and Disconnect, and a backend picker. One panel serves
 * both the 3D desktop (hosted on a SwingNode inside a Frame3D by the {@code Vpn}
 * wrapper) and the 2D/Swing desktop (opened as an MDI internal frame via
 * {@code Desktop2DAppRegistry.PANEL_APPS}).
 *
 * <p>The desktop ships no tunnel stack, so the connection is delegated honestly to
 * an installed tool through {@link VpnBackend} - {@code nmcli} preferred (it reuses
 * NetworkManager's stored VPN connections and secrets), {@code openvpn} /
 * {@code wg-quick} for an imported config. No process is started, no dialog shown
 * and no probe run until the user acts (Refresh / Connect / Disconnect / Import),
 * so the panel constructs and is asserted on headless; the command / parse logic
 * behind it is pure and unit-testable. A missing tool, or a connect that needs
 * privilege the session lacks, surfaces as guidance, never a fake "connected".</p>
 */
public class VpnPanel extends JPanel {

    /** Preferred width in pixels. */
    public static final int WIDTH_PX = 820;
    /** Preferred height in pixels. */
    public static final int HEIGHT_PX = 540;

    private static final Color GOOD = new Color(0, 140, 0);
    private static final Color ATTENTION = new Color(200, 80, 0);
    private static final Color MUTED = new Color(90, 90, 90);
    private static final Color BAD = new Color(200, 0, 0);

    /** Public-IP echo service used by the tunnel leak check (plain-text body). */
    static final String IP_ECHO_URL = "https://api.ipify.org";
    /** Live-status polling interval while the panel is open, in milliseconds. */
    static final int STATUS_POLL_MS = 5000;

    private final VpnStore store;
    private final VpnSettings settings;
    private final List<VpnProfile> profiles = new ArrayList<>();

    private final DefaultListModel<VpnProfile> profileModel = new DefaultListModel<>();
    private final JList<VpnProfile> profileList = new JList<>(profileModel);
    private final JComboBox<String> backendBox = new JComboBox<>();
    private final JButton connectBtn = new JButton("Connect");
    private final JButton disconnectBtn = new JButton("Disconnect");
    private final JButton refreshBtn = new JButton("Refresh");
    private final JButton importBtn = new JButton("Import Config...");
    private final JButton removeBtn = new JButton("Remove");
    private final JButton newBtn = new JButton("New...");
    private final JButton editBtn = new JButton("Edit...");
    private final JButton verifyBtn = new JButton("Verify tunnel");
    private final JCheckBox autoConnectCheck = new JCheckBox("Connect this profile automatically on open");
    private final JCheckBox killSwitchCheck = new JCheckBox("Kill switch - cut the network if this tunnel drops");

    private final JLabel bannerLabel = new JLabel("Not connected", JLabel.CENTER);
    private final JLabel nameValue = new JLabel("-");
    private final JLabel typeValue = new JLabel("-");
    private final JLabel hostValue = new JLabel("-");
    private final JLabel userValue = new JLabel("-");
    private final JLabel backendValue = new JLabel("-");
    private final JLabel tunnelValue = new JLabel("-");
    private final JLabel statusLabel = new JLabel("Ready");

    private Runnable onClose;
    private volatile boolean connecting;
    private volatile VpnStatus status = VpnStatus.unknown();
    private volatile Process tunnelProcess;
    private volatile String statusMessage = "Ready";

    private final ReconnectPolicy reconnectPolicy = ReconnectPolicy.defaultPolicy();
    private Timer statusTimer;
    private Timer reconnectTimer;
    private int reconnectAttempts;
    private volatile boolean statusPollInFlight;
    private volatile VpnProfile lastConnectedProfile;
    private volatile VpnProfile reconnectProfile;
    private volatile boolean vpnCutActive;
    private volatile String baselinePublicIp = "";
    private volatile TunnelVerdict lastVerdict;

    /** Builds the panel with the default store. */
    public VpnPanel() {
        this(new VpnStore());
    }

    /**
     * Builds the panel over an explicit store (package-private for tests).
     *
     * @param store the settings / profiles store
     */
    VpnPanel(VpnStore store) {
        this.store = store;
        this.settings = store.loadSettings();

        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        add(buildBanner(), BorderLayout.NORTH);
        add(buildProfileDock(), BorderLayout.WEST);
        add(buildDetail(), BorderLayout.CENTER);
        add(buildBottomBar(), BorderLayout.SOUTH);

        profiles.addAll(store.loadProfiles());
        refreshProfileList();
        refreshBackendBox();
        wireListeners();
        renderStatus();
        updateButtons();
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private Component buildBanner() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 4));
        bannerLabel.setFont(bannerLabel.getFont().deriveFont(Font.BOLD, 22f));
        bannerLabel.setForeground(MUTED);
        panel.add(bannerLabel, BorderLayout.CENTER);
        return panel;
    }

    private Component buildProfileDock() {
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Profiles"));
        panel.setPreferredSize(new Dimension(260, 100));

        profileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scroll = new JScrollPane(profileList);
        scroll.setPreferredSize(new Dimension(250, 280));
        panel.add(scroll);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 4));
        buttons.add(newBtn);
        buttons.add(editBtn);
        buttons.add(refreshBtn);
        buttons.add(importBtn);
        buttons.add(removeBtn);
        panel.add(buttons);
        return panel;
    }

    private Component buildDetail() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Connection"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 6, 5, 6);
        c.anchor = GridBagConstraints.WEST;

        int row = 0;
        addDetailRow(panel, c, row++, "Name:", nameValue);
        addDetailRow(panel, c, row++, "Type:", typeValue);
        addDetailRow(panel, c, row++, "Gateway:", hostValue);
        addDetailRow(panel, c, row++, "Username:", userValue);
        addDetailRow(panel, c, row++, "Driven by:", backendValue);
        addDetailRow(panel, c, row++, "Tunnel check:", tunnelValue);

        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        panel.add(autoConnectCheck, c);

        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        panel.add(killSwitchCheck, c);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 8));
        actions.add(connectBtn);
        actions.add(disconnectBtn);
        actions.add(verifyBtn);
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.CENTER;
        panel.add(actions, c);

        JLabel note = new JLabel("<html><i>The desktop has no built-in VPN stack, "
                + "so the tunnel is delegated to NetworkManager (nmcli) or, for an "
                + "imported config, to openvpn / wg-quick. Bringing a tunnel up "
                + "usually needs administrator rights.</i></html>");
        note.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(note, c);

        c.gridy = row;
        c.weighty = 1.0;
        panel.add(javax.swing.Box.createVerticalGlue(), c);
        return panel;
    }

    private void addDetailRow(JPanel panel, GridBagConstraints c, int row,
                              String label, JLabel value) {
        c.gridwidth = 1;
        c.weighty = 0;
        c.fill = GridBagConstraints.NONE;
        c.gridx = 0;
        c.gridy = row;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(value, c);
        c.weightx = 0;
    }

    private Component buildBottomBar() {
        JPanel panel = new JPanel(new BorderLayout(6, 4));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        top.add(new JLabel("Tunnel tool:"));
        backendBox.setPreferredSize(new Dimension(220, 26));
        top.add(backendBox);
        JButton close = new JButton("Close");
        close.addActionListener(e -> {
            stopTunnel();
            if (onClose != null) {
                onClose.run();
            }
        });
        top.add(close);
        panel.add(top, BorderLayout.NORTH);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        panel.add(statusLabel, BorderLayout.SOUTH);
        return panel;
    }

    private void refreshBackendBox() {
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        model.addElement("Auto-detect");
        for (String backend : VpnBackend.KNOWN_BACKENDS) {
            model.addElement(VpnBackend.describeBackend(backend));
        }
        backendBox.setModel(model);
        String preferred = settings.getPreferredBackend();
        backendBox.setSelectedItem(preferred.isBlank()
                ? "Auto-detect" : VpnBackend.describeBackend(preferred));
    }

    private void wireListeners() {
        connectBtn.addActionListener(e -> connect());
        disconnectBtn.addActionListener(e -> disconnect());
        refreshBtn.addActionListener(e -> refresh());
        importBtn.addActionListener(e -> importConfig());
        removeBtn.addActionListener(e -> removeSelected());
        newBtn.addActionListener(e -> showProfileDialog(null));
        editBtn.addActionListener(e -> editSelected());
        verifyBtn.addActionListener(e -> verifyTunnel());
        profileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadSelectedIntoDetail();
            }
        });
        autoConnectCheck.addActionListener(e -> {
            VpnProfile selected = profileList.getSelectedValue();
            if (selected != null) {
                selected.setAutoConnect(autoConnectCheck.isSelected());
                persist();
            }
        });
        killSwitchCheck.addActionListener(e -> {
            VpnProfile selected = profileList.getSelectedValue();
            if (selected == null) {
                return;
            }
            selected.setKillSwitch(killSwitchCheck.isSelected());
            persist();
            if (!selected.isKillSwitch()) {
                restoreVpnCut();
            }
            setStatus(selected.isKillSwitch()
                    ? "Kill switch armed for " + selected.getName() + "."
                    : "Kill switch off for " + selected.getName() + ".");
        });
        backendBox.addActionListener(e -> {
            Object sel = backendBox.getSelectedItem();
            String chosen = backendForLabel(sel == null ? "" : sel.toString());
            settings.setPreferredBackend(chosen);
            persist();
        });
    }

    private String backendForLabel(String label) {
        for (String backend : VpnBackend.KNOWN_BACKENDS) {
            if (VpnBackend.describeBackend(backend).equals(label)) {
                return backend;
            }
        }
        return "";
    }

    private void loadSelectedIntoDetail() {
        VpnProfile profile = profileList.getSelectedValue();
        if (profile == null) {
            nameValue.setText("-");
            typeValue.setText("-");
            hostValue.setText("-");
            userValue.setText("-");
            backendValue.setText("-");
            autoConnectCheck.setSelected(false);
            killSwitchCheck.setSelected(false);
            return;
        }
        nameValue.setText(profile.getName().isBlank() ? "-" : profile.getName());
        typeValue.setText(profile.getType().describe());
        hostValue.setText(profile.getHost().isBlank() ? "-" : profile.getHost());
        userValue.setText(profile.getUsername().isBlank() ? "-" : profile.getUsername());
        backendValue.setText(VpnBackend.describeBackend(
                profile.getBackend().isBlank() ? settings.getPreferredBackend()
                        : profile.getBackend()));
        autoConnectCheck.setSelected(profile.isAutoConnect());
        killSwitchCheck.setSelected(profile.isKillSwitch());
    }

    // ------------------------------------------------------------------
    // Profile list
    // ------------------------------------------------------------------

    private void refreshProfileList() {
        VpnProfile keep = profileList.getSelectedValue();
        profileModel.clear();
        for (VpnProfile profile : profiles) {
            profileModel.addElement(profile);
        }
        if (keep != null && profiles.contains(keep)) {
            profileList.setSelectedValue(keep, true);
        }
    }

    private void persist() {
        store.saveSettings(settings);
        store.saveProfiles(new ArrayList<>(profiles));
    }

    private void removeSelected() {
        VpnProfile selected = profileList.getSelectedValue();
        if (selected == null) {
            setStatus("Select a profile to remove.");
            return;
        }
        if (selected.isNetworkManagerManaged()) {
            setStatus("NetworkManager connections are managed by the system - "
                    + "remove them in the network settings, not here.");
            return;
        }
        profiles.remove(selected);
        refreshProfileList();
        persist();
        loadSelectedIntoDetail();
        setStatus("Removed " + selected.getName() + ".");
    }

    private void importConfig() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "VPN config (.ovpn, .conf)", "ovpn", "conf"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        VpnProfile profile = profileForConfig(file.toPath());
        profiles.add(profile);
        refreshProfileList();
        profileList.setSelectedValue(profile, true);
        persist();
        setStatus("Imported " + profile.getName() + " (" + profile.getType().describe() + ").");
    }

    /**
     * Builds an imported (file-based) profile for a config the user chose,
     * deriving its name, type and backend from the file. Package-visible so a test
     * can import without a file dialog.
     *
     * @param path the config file path
     * @return the new profile, never null
     */
    VpnProfile profileForConfig(Path path) {
        String fileName = (path == null) ? "" : path.getFileName().toString();
        String name = fileName.contains(".")
                ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        ConnectionType type = ConnectionType.fromText(fileName);
        VpnProfile profile = new VpnProfile();
        profile.setName(name.isBlank() ? "Imported VPN" : name);
        profile.setType(type);
        profile.setConfigPath((path == null) ? "" : path.toAbsolutePath().toString());
        profile.setBackend(type == ConnectionType.WIREGUARD
                ? VpnBackend.WG_QUICK : VpnBackend.OPENVPN);
        if (type == ConnectionType.OPENVPN && path != null) {
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                profile.setHost(VpnBackend.parseOvpnRemote(text).orElse(""));
            } catch (IOException | RuntimeException e) {
                // Leave the host blank; the path is still usable.
            }
        }
        return profile;
    }

    /**
     * Adds an imported profile directly. Package-visible for tests.
     *
     * @param profile the profile to add (ignored when null)
     */
    void addProfile(VpnProfile profile) {
        if (profile != null) {
            profiles.add(profile);
            refreshProfileList();
            persist();
        }
    }

    // ------------------------------------------------------------------
    // Discover / refresh (delegated to nmcli)
    // ------------------------------------------------------------------

    private void refresh() {
        if (connecting) {
            setStatus("Busy - wait for the current operation to finish.");
            return;
        }
        Optional<String> backend = VpnBackend.resolveBackend(
                settings.getPreferredBackend(), VpnPanel::onPath);
        if (backend.isEmpty()) {
            setStatus("No VPN tool found. Install NetworkManager (nmcli), openvpn "
                    + "or wg-quick.");
            return;
        }
        if (!VpnBackend.NMCLI.equals(backend.get())) {
            setStatus(VpnBackend.describeBackend(backend.get())
                    + " is available; live discovery needs NetworkManager (nmcli). "
                    + "Import a config to connect.");
            return;
        }
        setStatus("Refreshing connections...");
        Thread thread = new Thread(this::runRefresh, "lg3d-vpn-refresh");
        thread.setDaemon(true);
        thread.start();
    }

    private void runRefresh() {
        ProcessResult list = exec(VpnBackend.listConnectionsCommand());
        List<VpnProfile> discovered = VpnBackend.parseConnections(list.lines);
        ProcessResult active = exec(VpnBackend.activeConnectionsCommand());
        VpnStatus probed = list.ioError
                ? VpnStatus.disconnected("Could not read NetworkManager.")
                : VpnBackend.parseStatus(active.lines);
        SwingUtilities.invokeLater(() -> {
            applyDiscoveredProfiles(discovered);
            applyStatus(probed);
        });
    }

    /**
     * Merges freshly-discovered NetworkManager profiles with the imported (file)
     * profiles, carrying over each profile's auto-connect flag by name.
     * Package-visible so a test can drive discovery without running nmcli.
     *
     * @param discovered the nmcli-derived profiles (may be null)
     */
    void applyDiscoveredProfiles(List<VpnProfile> discovered) {
        List<VpnProfile> imported = new ArrayList<>();
        for (VpnProfile profile : profiles) {
            if (!profile.getConfigPath().isBlank()) {
                imported.add(profile);
            }
        }
        List<VpnProfile> merged = new ArrayList<>();
        if (discovered != null) {
            for (VpnProfile found : discovered) {
                VpnProfile previous = findByName(profiles, found.getName());
                if (previous != null) {
                    found.setAutoConnect(previous.isAutoConnect());
                }
                merged.add(found);
            }
        }
        merged.addAll(imported);
        profiles.clear();
        profiles.addAll(merged);
        refreshProfileList();
        persist();
        setStatus(discovered == null ? "Refreshed."
                : "Found " + discovered.size() + " VPN connection(s).");
    }

    private static VpnProfile findByName(List<VpnProfile> list, String name) {
        for (VpnProfile profile : list) {
            if (profile.getName().equals(name)) {
                return profile;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Connect / disconnect
    // ------------------------------------------------------------------

    private void connect() {
        if (connecting) {
            setStatus("Already connecting - please wait.");
            return;
        }
        VpnProfile profile = profileList.getSelectedValue();
        if (profile == null) {
            setStatus("Select a profile to connect.");
            return;
        }
        String backend = profile.getBackend().isBlank()
                ? VpnBackend.resolveBackend(settings.getPreferredBackend(), VpnPanel::onPath)
                        .orElse("")
                : profile.getBackend();
        if (backend.isBlank()) {
            setStatus("No VPN tool found. Install NetworkManager (nmcli), openvpn "
                    + "or wg-quick.");
            return;
        }
        List<String> command = VpnBackend.connectCommand(backend, profile);
        if (command.isEmpty()) {
            setStatus("Nothing to connect - the profile is missing a UUID or config path.");
            return;
        }
        connecting = true;
        updateButtons();
        setStatus("Connecting to " + profile.getName() + " via "
                + VpnBackend.describeBackend(backend) + "...");
        settings.setLastProfile(profile.getName());
        persist();
        final String tool = backend;
        Thread thread = new Thread(() -> runConnect(command, tool, profile), "lg3d-vpn-connect");
        thread.setDaemon(true);
        thread.start();
    }

    private void runConnect(List<String> command, String backend, VpnProfile profile) {
        // Record the direct egress IP before the tunnel comes up, so "Verify tunnel"
        // can prove the tunnel actually changed the route (best effort; blank offline).
        baselinePublicIp = fetchPublicIp();
        ProcessResult result = exec(command);
        String message = VpnBackend.describeResult(result.lines, result.exitCode, true);
        VpnStatus newStatus;
        if (result.ioError) {
            newStatus = VpnStatus.disconnected(message);
        } else if (result.exitCode == 0 && VpnBackend.NMCLI.equals(backend)) {
            ProcessResult active = exec(VpnBackend.activeConnectionsCommand());
            newStatus = active.ioError
                    ? VpnStatus.connected(profile.getName(), "")
                    : VpnBackend.parseStatus(active.lines);
        } else if (result.exitCode == 0) {
            newStatus = VpnStatus.connected(profile.getName(), "");
        } else {
            newStatus = VpnStatus.disconnected(message);
        }
        final VpnStatus finalStatus = newStatus;
        SwingUtilities.invokeLater(() -> {
            connecting = false;
            applyStatus(finalStatus);
            updateButtons();
        });
    }

    private void disconnect() {
        if (connecting) {
            setStatus("Busy - wait for the current operation to finish.");
            return;
        }
        // An explicit disconnect cancels any pending auto-reconnect loop.
        reconnectProfile = null;
        if (reconnectTimer != null) {
            reconnectTimer.stop();
        }
        VpnProfile profile = profileList.getSelectedValue();
        String backend = (profile != null && !profile.getBackend().isBlank())
                ? profile.getBackend()
                : VpnBackend.resolveBackend(settings.getPreferredBackend(), VpnPanel::onPath)
                        .orElse(VpnBackend.NMCLI);
        if (profile == null) {
            // Fall back to whatever the live status says is connected.
            if (!status.connected() || status.profileName().isBlank()) {
                setStatus("Select a profile to disconnect.");
                return;
            }
            profile = new VpnProfile(status.profileName(), "", ConnectionType.GENERIC);
        }
        List<String> command = VpnBackend.disconnectCommand(backend, profile);
        connecting = true;
        updateButtons();
        setStatus("Disconnecting " + profile.getName() + "...");
        final VpnProfile target = profile;
        final String tool = backend;
        Thread thread = new Thread(() -> runDisconnect(command, tool, target), "lg3d-vpn-disconnect");
        thread.setDaemon(true);
        thread.start();
    }

    private void runDisconnect(List<String> command, String backend, VpnProfile profile) {
        String message;
        if (command.isEmpty()) {
            // A standalone openvpn tunnel is torn down by stopping its process.
            stopTunnel();
            message = "Disconnected.";
        } else {
            ProcessResult result = exec(command);
            message = VpnBackend.describeResult(result.lines, result.exitCode, false);
        }
        final String finalMessage = message;
        SwingUtilities.invokeLater(() -> {
            connecting = false;
            applyStatus(VpnStatus.disconnected(finalMessage));
            updateButtons();
        });
    }

    /** Stops a tracked standalone tunnel process (openvpn), if any. */
    void stopTunnel() {
        Process process = tunnelProcess;
        if (process != null) {
            process.destroy();
            tunnelProcess = null;
        }
    }

    // ------------------------------------------------------------------
    // Live status polling (Swing Timer, started only while shown)
    // ------------------------------------------------------------------

    /**
     * Starts the {@value #STATUS_POLL_MS} ms live-status poll. Called from
     * {@link #addNotify()} (never the constructor) so a headless-constructed panel
     * that is never shown spawns no timer and runs no {@code nmcli}.
     */
    @Override
    public void addNotify() {
        super.addNotify();
        if (statusTimer == null) {
            statusTimer = new Timer(STATUS_POLL_MS, e -> pollStatus());
            statusTimer.setRepeats(true);
        }
        if (!statusTimer.isRunning()) {
            statusTimer.start();
        }
    }

    /**
     * Stops the poll / reconnect timers and lifts any kill-switch cut this panel
     * raised, so closing the window never leaves the desktop's network clients stuck
     * cut with no way to restore them.
     */
    @Override
    public void removeNotify() {
        if (statusTimer != null) {
            statusTimer.stop();
        }
        if (reconnectTimer != null) {
            reconnectTimer.stop();
        }
        reconnectProfile = null;
        restoreVpnCut();
        super.removeNotify();
    }

    /**
     * One live-status tick: resolves {@code nmcli}, reads the active connections on a
     * daemon thread and applies the result on the EDT. Skipped while a connect /
     * disconnect is running or a previous poll is still in flight; a read error is
     * ignored rather than flapping the UI to "disconnected".
     */
    private void pollStatus() {
        if (connecting || statusPollInFlight) {
            return;
        }
        Optional<String> backend = VpnBackend.resolveBackend(
                settings.getPreferredBackend(), VpnPanel::onPath);
        if (backend.isEmpty() || !VpnBackend.NMCLI.equals(backend.get())) {
            // Live polling needs nmcli; an imported-only setup has no CLI status.
            return;
        }
        statusPollInFlight = true;
        Thread thread = new Thread(() -> {
            ProcessResult active = exec(VpnBackend.activeConnectionsCommand());
            final VpnStatus probed = active.ioError ? null : VpnBackend.parseStatus(active.lines);
            SwingUtilities.invokeLater(() -> {
                statusPollInFlight = false;
                if (probed != null) {
                    applyPolledStatus(probed);
                }
            });
        }, "lg3d-vpn-status-poll");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Applies a status read by the live poll. Unlike {@link #applyStatus} (used for a
     * user action or a manual refresh), a connected-then-disconnected transition seen
     * here is an <em>unexpected drop</em> and is handled as such. Package-visible so a
     * test can drive a drop deterministically without {@code nmcli} or a timer.
     *
     * @param value the polled status (null is ignored)
     */
    void applyPolledStatus(VpnStatus value) {
        if (value == null) {
            return;
        }
        boolean wasConnected = status.connected();
        boolean nowConnected = value.connected();
        applyStatus(value);
        if (wasConnected && !nowConnected && !connecting) {
            onUnexpectedDrop();
        }
    }

    /**
     * Reacts to a tunnel that was up going down on its own: arms the kill switch
     * (cutting the desktop's network clients) when the dropped profile asked for it,
     * and schedules auto-reconnect when the profile is auto-connect.
     */
    private void onUnexpectedDrop() {
        VpnProfile dropped = lastConnectedProfile;
        boolean armed = dropped != null && dropped.isKillSwitch();
        boolean auto = dropped != null && dropped.isAutoConnect();
        if (armed) {
            armVpnCut("Tunnel dropped unexpectedly - network cut to prevent a leak.");
        }
        if (auto) {
            reconnectProfile = dropped;
            reconnectAttempts = 0;
            scheduleReconnect();
        } else if (!armed) {
            bannerLabel.setText("Tunnel dropped");
            bannerLabel.setForeground(ATTENTION);
        }
    }

    /**
     * Raises the desktop-wide network cut (PR 1's {@link NetworkCut} seam) and shows
     * the alarm. Only this panel's own cut is tracked, so restoring it never stomps a
     * cut raised by the Tor private mode.
     */
    private void armVpnCut(String why) {
        if (!vpnCutActive) {
            vpnCutActive = true;
            NetworkCut.cut();
        }
        bannerLabel.setText("Kill switch: network cut");
        bannerLabel.setForeground(BAD);
        setStatus(why);
    }

    /** Lifts a cut this panel raised; a no-op when it did not. */
    private void restoreVpnCut() {
        if (vpnCutActive) {
            vpnCutActive = false;
            NetworkCut.restore();
        }
    }

    /**
     * Schedules the next auto-reconnect attempt using the pure {@link ReconnectPolicy}
     * backoff, or reports that the retry cap was reached.
     */
    private void scheduleReconnect() {
        VpnProfile profile = reconnectProfile;
        if (profile == null) {
            return;
        }
        if (!reconnectPolicy.shouldRetry(reconnectAttempts)) {
            reconnectProfile = null;
            bannerLabel.setText("Reconnect failed");
            bannerLabel.setForeground(BAD);
            setStatus("Gave up reconnecting " + profile.getName() + " after "
                    + reconnectAttempts + " attempt(s). Connect manually, or check the tunnel.");
            return;
        }
        long delay = reconnectPolicy.delayForAttempt(reconnectAttempts + 1);
        bannerLabel.setText("Reconnecting in " + Math.max(1, delay / 1000) + "s...");
        bannerLabel.setForeground(ATTENTION);
        if (reconnectTimer != null) {
            reconnectTimer.stop();
        }
        reconnectTimer = new Timer((int) Math.min(delay, Integer.MAX_VALUE),
                e -> attemptReconnect());
        reconnectTimer.setRepeats(false);
        reconnectTimer.start();
    }

    /** One auto-reconnect attempt: bump the counter and re-run connect for the profile. */
    private void attemptReconnect() {
        reconnectAttempts++;
        VpnProfile profile = reconnectProfile;
        if (profile == null || status.connected() || connecting) {
            return;
        }
        profileList.setSelectedValue(profile, true);
        connect();
    }

    // ------------------------------------------------------------------
    // Tunnel verification (public-IP leak check)
    // ------------------------------------------------------------------

    /**
     * Verifies the live tunnel by fetching the current public IP and comparing it with
     * the baseline recorded when the tunnel came up. Guarded so it only runs from the
     * button (never the constructor) and only while connected.
     */
    void verifyTunnel() {
        if (!status.connected()) {
            setStatus("Connect a tunnel first, then verify it.");
            return;
        }
        setStatus("Verifying tunnel via " + IP_ECHO_URL + " ...");
        verifyBtn.setEnabled(false);
        Thread thread = new Thread(() -> {
            String now = fetchPublicIp();
            TunnelVerdict verdict = TunnelVerdict.classify(baselinePublicIp, now);
            SwingUtilities.invokeLater(() -> renderVerdict(verdict, now));
        }, "lg3d-vpn-verify");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Fetches the public egress IP from {@link #IP_ECHO_URL} as plain text. Runs on a
     * daemon thread; any failure yields an empty string so the caller reports
     * {@link TunnelVerdict#UNREACHABLE} rather than guessing. Never throws.
     */
    private String fetchPublicIp() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(IP_ECHO_URL))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "text/plain")
                    .GET()
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return "";
            }
            return TunnelVerdict.parsePublicIp(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** Renders a leak-check verdict on the tunnel row and status line. */
    private void renderVerdict(TunnelVerdict verdict, String ip) {
        TunnelVerdict v = (verdict == null) ? TunnelVerdict.UNREACHABLE : verdict;
        lastVerdict = v;
        String shown = (ip == null || ip.isBlank()) ? "unreachable" : ip;
        tunnelValue.setText(v.describe() + "  [egress: " + shown + "]");
        tunnelValue.setForeground(v == TunnelVerdict.LEAK ? BAD
                : (v == TunnelVerdict.TUNNELED ? GOOD : MUTED));
        setStatus(v.describe());
        updateButtons();
    }

    // ------------------------------------------------------------------
    // New / Edit profile dialogs
    // ------------------------------------------------------------------

    /** Opens the Edit dialog for the selected profile, or asks for a selection. */
    private void editSelected() {
        VpnProfile selected = profileList.getSelectedValue();
        if (selected == null) {
            setStatus("Select a profile to edit.");
            return;
        }
        showProfileDialog(selected);
    }

    /**
     * Builds and shows the New / Edit profile form (name, type, gateway, auto-connect,
     * kill switch) and, on OK, applies it via {@link #saveProfile}. Only ever reached
     * from a button action, so the panel still constructs headless.
     *
     * @param existing the profile to edit, or null to create a new one
     */
    private void showProfileDialog(VpnProfile existing) {
        JTextField nameField = new JTextField(existing == null ? "" : existing.getName(), 20);
        JTextField hostField = new JTextField(existing == null ? "" : existing.getHost(), 20);
        JComboBox<String> typeBox = new JComboBox<>(typeLabels());
        ConnectionType currentType = (existing == null) ? ConnectionType.GENERIC : existing.getType();
        typeBox.setSelectedItem(currentType.describe());
        JCheckBox autoBox = new JCheckBox("Connect automatically on open",
                existing != null && existing.isAutoConnect());
        JCheckBox killBox = new JCheckBox("Kill switch - cut the network if it drops",
                existing != null && existing.isKillSwitch());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 6, 4, 6);
        g.anchor = GridBagConstraints.WEST;
        int r = 0;
        g.gridx = 0;
        g.gridy = r;
        form.add(new JLabel("Name:"), g);
        g.gridx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1.0;
        form.add(nameField, g);
        r++;
        g.gridx = 0;
        g.gridy = r;
        g.fill = GridBagConstraints.NONE;
        g.weightx = 0;
        form.add(new JLabel("Type:"), g);
        g.gridx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1.0;
        form.add(typeBox, g);
        r++;
        g.gridx = 0;
        g.gridy = r;
        g.fill = GridBagConstraints.NONE;
        g.weightx = 0;
        form.add(new JLabel("Gateway:"), g);
        g.gridx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1.0;
        form.add(hostField, g);
        r++;
        g.gridx = 0;
        g.gridy = r;
        g.gridwidth = 2;
        form.add(autoBox, g);
        r++;
        g.gridy = r;
        form.add(killBox, g);

        int choice = JOptionPane.showConfirmDialog(this, form,
                (existing == null) ? "New Profile" : "Edit Profile",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        saveProfile(existing, nameField.getText(), typeForLabel((String) typeBox.getSelectedItem()),
                hostField.getText(), autoBox.isSelected(), killBox.isSelected());
    }

    /** The combo labels for every {@link ConnectionType}, in enum order. */
    private static String[] typeLabels() {
        ConnectionType[] types = ConnectionType.values();
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            labels[i] = types[i].describe();
        }
        return labels;
    }

    /** Maps a combo label back to its type ({@link ConnectionType#GENERIC} when unknown). */
    private static ConnectionType typeForLabel(String label) {
        for (ConnectionType type : ConnectionType.values()) {
            if (type.describe().equals(label)) {
                return type;
            }
        }
        return ConnectionType.GENERIC;
    }

    /**
     * Applies the New / Edit form values to a profile (creating one when
     * {@code existing} is null), persists and re-selects it. Package-visible so a test
     * can drive create / edit without a dialog.
     *
     * @param existing    the profile to edit, or null to create a new one
     * @param name        the profile name (normalised by the bean)
     * @param type        the connection type (null keeps the current type)
     * @param host        the gateway / host (normalised by the bean)
     * @param autoConnect whether to auto-connect on open
     * @param killSwitch  whether an unexpected drop should cut the network
     * @return the saved profile, never null
     */
    VpnProfile saveProfile(VpnProfile existing, String name, ConnectionType type, String host,
                           boolean autoConnect, boolean killSwitch) {
        boolean isNew = (existing == null);
        VpnProfile profile = isNew ? new VpnProfile() : existing;
        profile.setName(name);
        if (type != null) {
            profile.setType(type);
        }
        profile.setHost(host);
        profile.setAutoConnect(autoConnect);
        profile.setKillSwitch(killSwitch);
        if (isNew) {
            profiles.add(profile);
        }
        refreshProfileList();
        profileList.setSelectedValue(profile, true);
        persist();
        loadSelectedIntoDetail();
        setStatus((isNew ? "Created " : "Updated ")
                + (profile.getName().isBlank() ? "profile" : profile.getName()) + ".");
        return profile;
    }

    // ------------------------------------------------------------------
    // Status rendering
    // ------------------------------------------------------------------

    /**
     * Renders a tunnel status on the banner and status line. Package-visible so a
     * test can drive the status without running any tool.
     *
     * @param value the status to render (null is ignored)
     */
    void applyStatus(VpnStatus value) {
        if (value == null) {
            return;
        }
        this.status = value;
        if (value.connected()) {
            reconnectAttempts = 0;
            reconnectProfile = null;
            if (reconnectTimer != null) {
                reconnectTimer.stop();
            }
            restoreVpnCut();
            VpnProfile matched = findByName(profiles, value.profileName());
            if (matched != null) {
                lastConnectedProfile = matched;
            }
        } else if (reconnectProfile != null && !connecting && !isReconnectScheduled()) {
            // A reconnect attempt just failed - step to the next backoff slot.
            scheduleReconnect();
        }
        renderStatus();
        setStatus(value.summary());
    }

    private void renderStatus() {
        if (connecting) {
            bannerLabel.setText("Working...");
            bannerLabel.setForeground(ATTENTION);
            return;
        }
        if (status.connected()) {
            bannerLabel.setText("Connected: " + status.profileName());
            bannerLabel.setForeground(GOOD);
        } else {
            bannerLabel.setText("Not connected");
            bannerLabel.setForeground(MUTED);
        }
    }

    private void updateButtons() {
        connectBtn.setEnabled(!connecting);
        disconnectBtn.setEnabled(!connecting);
        refreshBtn.setEnabled(!connecting);
        importBtn.setEnabled(!connecting);
        removeBtn.setEnabled(!connecting);
        newBtn.setEnabled(!connecting);
        editBtn.setEnabled(!connecting);
        verifyBtn.setEnabled(!connecting && status.connected());
    }

    // ------------------------------------------------------------------
    // Process plumbing
    // ------------------------------------------------------------------

    /**
     * Runs a command, capturing its merged output and exit code. A missing
     * executable surfaces as {@link ProcessResult#ioError}; it never throws.
     */
    private ProcessResult exec(List<String> command) {
        ProcessResult result = new ProcessResult();
        if (command == null || command.isEmpty()) {
            result.ioError = true;
            result.ioMessage = "empty command";
            return result;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            String display = System.getenv("DISPLAY");
            if (display != null && !display.isBlank()) {
                pb.environment().put("DISPLAY", display);
            }
            Process process = pb.start();
            tunnelProcess = process;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.lines.add(line);
                }
            }
            result.exitCode = process.waitFor();
        } catch (IOException e) {
            result.ioError = true;
            result.ioMessage = (e.getMessage() == null) ? e.toString() : e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.ioError = true;
            result.ioMessage = "interrupted";
        } catch (RuntimeException e) {
            result.ioError = true;
            result.ioMessage = (e.getMessage() == null) ? e.toString() : e.getMessage();
        } finally {
            tunnelProcess = null;
        }
        return result;
    }

    /** The captured output of one external command. */
    private static final class ProcessResult {
        final List<String> lines = new ArrayList<>();
        int exitCode;
        boolean ioError;
        String ioMessage = "";
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

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    private void setStatus(String text) {
        final String message = text;
        // Track the latest status synchronously so it is observable the instant it
        // is set (headless tests never pump the EDT); the label still updates on
        // the EDT.
        statusMessage = message;
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

    /** The number of profiles listed. */
    int profileCount() {
        return profileModel.size();
    }

    /** An unmodifiable view of the profiles. */
    List<VpnProfile> profiles() {
        return List.copyOf(profiles);
    }

    /** The current tunnel status. */
    VpnStatus status() {
        return status;
    }

    /** True when a tunnel is up. */
    boolean isConnected() {
        return status.connected();
    }

    /** True while a connect / disconnect is running. */
    boolean isConnecting() {
        return connecting;
    }

    /** The current status text. */
    String statusText() {
        return statusMessage;
    }

    /** The live settings bean (package-visible for tests). */
    VpnSettings settings() {
        return settings;
    }

    /** True while this panel has the desktop's network cut (kill switch tripped). */
    boolean isVpnCutActive() {
        return vpnCutActive;
    }

    /** The number of auto-reconnect attempts made since the tunnel was last up. */
    int reconnectAttempts() {
        return reconnectAttempts;
    }

    /** True while an auto-reconnect attempt is scheduled and waiting to fire. */
    boolean isReconnectScheduled() {
        return reconnectTimer != null && reconnectTimer.isRunning();
    }

    /** The last tunnel-verification verdict, or null when never verified. */
    TunnelVerdict lastVerdict() {
        return lastVerdict;
    }

    /** Sets the baseline public IP a later {@link #verifyTunnel()} compares against. */
    void setBaselinePublicIp(String ip) {
        this.baselinePublicIp = (ip == null) ? "" : ip.trim();
    }

    /** Drives a tunnel verdict onto the UI without a network fetch (test seam). */
    void applyVerdict(TunnelVerdict verdict, String ip) {
        renderVerdict(verdict, ip);
    }
}
