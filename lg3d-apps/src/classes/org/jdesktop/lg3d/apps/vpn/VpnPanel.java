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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

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
    private final JCheckBox autoConnectCheck = new JCheckBox("Connect this profile automatically on open");

    private final JLabel bannerLabel = new JLabel("Not connected", JLabel.CENTER);
    private final JLabel nameValue = new JLabel("-");
    private final JLabel typeValue = new JLabel("-");
    private final JLabel hostValue = new JLabel("-");
    private final JLabel userValue = new JLabel("-");
    private final JLabel backendValue = new JLabel("-");
    private final JLabel statusLabel = new JLabel("Ready");

    private Runnable onClose;
    private volatile boolean connecting;
    private volatile VpnStatus status = VpnStatus.unknown();
    private volatile Process tunnelProcess;
    private volatile String statusMessage = "Ready";

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

        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        panel.add(autoConnectCheck, c);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 8));
        actions.add(connectBtn);
        actions.add(disconnectBtn);
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
}
