/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.update;

import com.protonmail.landrevillejf.swingide.update.UpdateService;
import com.protonmail.landrevillejf.swingide.update.ui.UpdatePresenter;
import com.protonmail.landrevillejf.swingide.update.ui.UpdateSettingsPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Properties;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;

/**
 * The Software Update content: the {@code update-manager} module's Swing update
 * pipeline (check / download / verify / install, plus channel and scheduling
 * settings) presented as a self-contained panel.
 *
 * <p>This is a plain Swing {@link JPanel} with a no-argument constructor, so it
 * is hosted two ways exactly like the Help Center: in the 3D desktop the
 * {@link UpdateManager} wrapper puts it on a {@code SwingNode} inside a
 * {@code Frame3D} via {@code TitledSwingWindow}; in the 2D/Swing desktop
 * {@code Desktop2DAppRegistry} constructs it reflectively and hosts it in an MDI
 * internal frame. It touches no Java 3D, so the 2D path never needs the scene
 * graph.</p>
 *
 * <p>The panel embeds the module's {@link UpdateSettingsPanel} and drives an
 * {@link UpdatePresenter} for the manual check and the changelog. If the update
 * service cannot be created (for example a missing runtime dependency) the panel
 * degrades to a readable message instead of throwing, so a broken update bundle
 * can never take down the window that hosts it.</p>
 */
public class UpdateManagerPanel extends JPanel {

    /** Panel size in native pixels; the wrapper hands these to TitledSwingWindow. */
    public static final int WIDTH_PX = 720;
    public static final int HEIGHT_PX = 560;

    /** The wired service, or {@code null} when it could not be created. */
    private final UpdateService service;

    /** The presenter driving the check / changelog flow, or {@code null}. */
    private final UpdatePresenter presenter;

    /** The embedded settings form; kept for {@link #applySettings()}. */
    private UpdateSettingsPanel settingsPanel;

    /** The status line; kept for {@link #applySettings()}. */
    private JLabel statusLabel;

    public UpdateManagerPanel() {
        super(new BorderLayout());
        setOpaque(true);
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        UpdateService createdService = null;
        UpdatePresenter createdPresenter = null;
        try {
            createdService = UpdateService.createDefault();
            createdPresenter = new UpdatePresenter(createdService);
        } catch (RuntimeException | LinkageError e) {
            // A missing runtime dependency or unreadable configuration must not
            // escape from the constructor: fall back to an explanatory pane.
            createdService = null;
            createdPresenter = null;
        }
        this.service = createdService;
        this.presenter = createdPresenter;

        if (service == null) {
            add(buildUnavailablePane(), BorderLayout.CENTER);
            return;
        }

        add(buildHeader(), BorderLayout.NORTH);
        add(buildSettings(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        // The tray callbacks and the update-event subscription are harmless
        // headless; initialize() is what schedules the periodic network checks,
        // so it is gated on a real display to keep headless construction (and the
        // unit test) fast and side-effect free.
        presenter.registerActionListener();
        presenter.subscribeToUpdateEvents();
        if (!GraphicsEnvironment.isHeadless()) {
            service.initialize();
        }
    }

    /** Title, running version and channel, plus the action button bar. */
    private Component buildHeader() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(true);
        header.setBackground(Color.WHITE);
        header.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));

        JLabel title = new JLabel("Software Update");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(title);

        String channel = service.getChannel() != null
                ? service.getChannel().getDisplayName()
                : "Stable";
        JLabel subtitle = new JLabel(
                "Project Looking Glass " + service.getCurrentVersion()
                + "  \u2022  channel: " + channel);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(subtitle);

        header.add(Box.createVerticalStrut(10));
        header.add(buildButtonBar());
        return header;
    }

    private Component buildButtonBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.setOpaque(true);
        bar.setBackground(Color.WHITE);
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton check = new JButton("Check for Updates");
        check.addActionListener(e -> presenter.checkForUpdates());

        JButton apply = new JButton("Apply Settings");
        apply.addActionListener(e -> applySettings());

        JButton changelog = new JButton("What's New");
        changelog.addActionListener(e -> presenter.showChangelog());

        bar.add(check);
        bar.add(apply);
        bar.add(changelog);
        return bar;
    }

    /** The module's settings form, loaded from the running configuration. */
    private Component buildSettings() {
        UpdateSettingsPanel settings = new UpdateSettingsPanel();
        settings.loadFrom(service.getConfiguration());
        settings.setBackground(Color.WHITE);
        this.settingsPanel = settings;
        JScrollPane scroll = new JScrollPane(settings);
        scroll.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    /** One-line status sink fed by the presenter's progress messages. */
    private Component buildStatusBar() {
        JLabel status = new JLabel(" ");
        status.setHorizontalAlignment(SwingConstants.LEFT);
        status.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0xDD, 0xDD, 0xDD)),
                BorderFactory.createEmptyBorder(6, 16, 6, 16)));
        this.statusLabel = status;
        presenter.setStatusListener(message ->
                status.setText(message != null ? message : " "));
        return status;
    }

    /** Persists the embedded form's values to the running service and to disk. */
    private void applySettings() {
        try {
            Properties configuration = settingsPanel.applyTo(new Properties());
            service.applyConfiguration(configuration);
            if (UpdateService.saveConfiguration(configuration)) {
                statusLabel.setText("Settings saved");
            } else {
                statusLabel.setText("Settings applied for this session (not saved)");
            }
        } catch (RuntimeException ex) {
            statusLabel.setText("Could not apply the update settings");
        }
    }

    /** A centred message shown when the update service cannot be presented. */
    private static JPanel buildUnavailablePane() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(true);
        panel.setBackground(Color.WHITE);
        JLabel label = new JLabel(
                "<html><div style='text-align:center;'>"
                + "<h2>Software Update unavailable</h2>"
                + "<p>The update service could not be started.</p>"
                + "</div></html>",
                SwingConstants.CENTER);
        label.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }
}
