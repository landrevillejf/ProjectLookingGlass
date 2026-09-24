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
package com.protonmail.landrevillejf.swingide.update.ui;

import com.protonmail.landrevillejf.swingide.update.UpdateService;
import lombok.extern.slf4j.Slf4j;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Modal dialog editing the update settings.
 * <p>
 * Validating the form writes {@code update-config.properties} into the user
 * configuration directory (see {@link UpdateService#getUserConfigFile()}) so the
 * choices survive a restart. When a running {@link UpdateService} is supplied,
 * the changes are also applied immediately through
 * {@link UpdateService#applyConfiguration(Properties)}.
 * </p>
 */
@Slf4j
public class UpdateSettingsDialog extends JDialog {

    private static final String DEFAULT_TITLE = "Update Settings";

    private final UpdateSettingsPanel settingsPanel;
    private final UpdateService service;
    private final Properties baseConfiguration;

    private Properties result;
    private boolean confirmed;

    /**
     * Creates the dialog without applying the changes to a running service.
     *
     * @param parent        the owning window, may be {@code null}
     * @param configuration settings to display, {@code null} uses the defaults
     */
    public UpdateSettingsDialog(Window parent, Properties configuration) {
        this(parent, configuration, null);
    }

    /**
     * Creates the dialog.
     *
     * @param parent        the owning window, may be {@code null}
     * @param configuration settings to display, {@code null} uses the defaults
     * @param service       running service updated on validation, may be {@code null}
     */
    public UpdateSettingsDialog(Window parent, Properties configuration, UpdateService service) {
        super(parent, DEFAULT_TITLE, Dialog.ModalityType.APPLICATION_MODAL);

        this.service = service;
        this.baseConfiguration = copyOf(configuration);
        this.settingsPanel = new UpdateSettingsPanel();
        this.settingsPanel.loadFrom(this.baseConfiguration);

        initializeUI();
    }

    private void initializeUI() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        mainPanel.add(settingsPanel, BorderLayout.CENTER);
        mainPanel.add(createButtonPanel(), BorderLayout.SOUTH);

        add(mainPanel);
        setSize(560, 560);
        setMinimumSize(new Dimension(480, 420));
        setLocationRelativeTo(getParent());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getRootPane().setDefaultButton(null);
    }

    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 15, 15, 15));

        JButton okButton = new JButton("OK");
        okButton.setPreferredSize(new Dimension(100, 30));
        okButton.addActionListener(event -> onOkClicked());

        JButton cancelButton = new JButton("Cancel");
        cancelButton.setPreferredSize(new Dimension(100, 30));
        cancelButton.addActionListener(event -> dispose());

        panel.add(okButton);
        panel.add(cancelButton);

        return panel;
    }

    private void onOkClicked() {
        // The form only knows a subset of the keys: start from the loaded
        // configuration so unrelated entries (repository URL, downgrade folder)
        // are not dropped from the persisted file.
        Properties updated = settingsPanel.applyTo(copyOf(baseConfiguration));

        if (!UpdateService.saveConfiguration(updated)) {
            log.warn("The update settings could not be written to {}",
                UpdateService.getUserConfigFile());
        }

        if (service != null) {
            service.applyConfiguration(updated);
        }

        this.result = updated;
        this.confirmed = true;
        dispose();
    }

    /**
     * Whether the user validated the form.
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Settings validated by the user, {@code null} when the dialog was cancelled.
     */
    public Properties getConfiguration() {
        return result;
    }

    private static Properties copyOf(Properties source) {
        Properties copy = new Properties();

        if (source != null) {
            source.stringPropertyNames().forEach(name -> copy.setProperty(name, source.getProperty(name)));
        }

        return copy;
    }

    /**
     * Displays the dialog and blocks until it is closed.
     *
     * @param parent        the owning window, may be {@code null}
     * @param configuration settings to display, {@code null} uses the defaults
     * @return the validated settings, empty when cancelled or headless
     */
    public static Optional<Properties> show(Window parent, Properties configuration) {
        return show(parent, configuration, null);
    }

    /**
     * Displays the dialog and blocks until it is closed.
     *
     * @param parent        the owning window, may be {@code null}
     * @param configuration settings to display, {@code null} uses the defaults
     * @param service       running service updated on validation, may be {@code null}
     * @return the validated settings, empty when cancelled or headless
     */
    public static Optional<Properties> show(Window parent, Properties configuration,
                                            UpdateService service) {
        if (GraphicsEnvironment.isHeadless()) {
            log.warn("Headless environment, the update settings dialog cannot be displayed");
            return Optional.empty();
        }

        AtomicReference<Properties> validated = new AtomicReference<>();

        Runnable display = () -> {
            UpdateSettingsDialog dialog = new UpdateSettingsDialog(parent, configuration, service);
            dialog.setVisible(true);

            if (dialog.isConfirmed()) {
                validated.set(dialog.getConfiguration());
            }
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                display.run();
            } else {
                SwingUtilities.invokeAndWait(display);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while displaying the update settings dialog");
            return Optional.empty();
        } catch (InvocationTargetException e) {
            log.error("The update settings dialog failed", e.getCause());
            return Optional.empty();
        }

        return Optional.ofNullable(validated.get());
    }
}
