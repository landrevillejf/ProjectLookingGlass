package com.protonmail.landrevillejf.swingide.update.ui;

import com.protonmail.landrevillejf.swingide.update.channels.UpdateChannel;
import lombok.extern.slf4j.Slf4j;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Properties;

/**
 * Preferences panel driving the update system: channel selection, automatic
 * download/installation, desktop notifications and check frequency.
 */
@Slf4j
public class UpdateSettingsPanel extends JPanel {

    private JCheckBox enabledCheckBox;
    private JCheckBox autoDownloadCheckBox;
    private JCheckBox autoInstallCheckBox;
    private JCheckBox notifyOnStartupCheckBox;
    private JCheckBox trayNotificationsCheckBox;
    private JComboBox<UpdateChannel> channelComboBox;
    private JSpinner checkIntervalSpinner;

    public UpdateSettingsPanel() {
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        // Create form panel
        JPanel formPanel = createFormPanel();
        add(formPanel, BorderLayout.NORTH);

        // Create info panel
        JPanel infoPanel = createInfoPanel();
        add(infoPanel, BorderLayout.CENTER);
    }

    private JPanel createFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 5, 8, 5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;

        // Enable updates
        enabledCheckBox = new JCheckBox("Enable automatic updates");
        enabledCheckBox.setSelected(true);
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        panel.add(enabledCheckBox, gbc);
        row++;

        // Separator
        gbc.gridy = row;
        gbc.gridwidth = 2;
        panel.add(Box.createVerticalStrut(10), gbc);
        row++;

        // Auto download
        autoDownloadCheckBox = new JCheckBox("Automatically download updates");
        autoDownloadCheckBox.setSelected(false);
        gbc.gridy = row;
        panel.add(autoDownloadCheckBox, gbc);
        row++;

        // Auto install
        autoInstallCheckBox = new JCheckBox("Automatically install updates");
        autoInstallCheckBox.setSelected(false);
        gbc.gridy = row;
        panel.add(autoInstallCheckBox, gbc);
        row++;

        // Notify on startup
        notifyOnStartupCheckBox = new JCheckBox("Check for updates on startup");
        notifyOnStartupCheckBox.setSelected(true);
        gbc.gridy = row;
        panel.add(notifyOnStartupCheckBox, gbc);
        row++;

        // Desktop notifications
        trayNotificationsCheckBox = new JCheckBox("Show desktop notifications");
        trayNotificationsCheckBox.setSelected(true);
        trayNotificationsCheckBox.setToolTipText(
            "Display update events in the system tray / notification center"
        );
        gbc.gridy = row;
        panel.add(trayNotificationsCheckBox, gbc);
        row++;

        // Separator
        gbc.gridy = row;
        panel.add(Box.createVerticalStrut(10), gbc);
        row++;

        // Update channel
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        panel.add(new JLabel("Update Channel:"), gbc);

        channelComboBox = new JComboBox<>(UpdateChannel.values());
        channelComboBox.setRenderer(new ChannelRenderer());
        channelComboBox.setSelectedItem(UpdateChannel.STABLE);
        gbc.gridx = 1;
        panel.add(channelComboBox, gbc);
        row++;

        // Check interval
        gbc.gridx = 0;
        gbc.gridy = row;
        panel.add(new JLabel("Check Interval (hours):"), gbc);

        SpinnerNumberModel spinnerModel = new SpinnerNumberModel(24, 1, 720, 1);
        checkIntervalSpinner = new JSpinner(spinnerModel);
        checkIntervalSpinner.setPreferredSize(new Dimension(100, 25));
        gbc.gridx = 1;
        panel.add(checkIntervalSpinner, gbc);

        return panel;
    }

    private JPanel createInfoPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createTitledBorder("Information"));

        JTextArea infoText = new JTextArea();
        infoText.setEditable(false);
        infoText.setLineWrap(true);
        infoText.setWrapStyleWord(true);
        infoText.setBackground(getBackground());
        infoText.setText(
            "Update Settings:\n\n"
            + "• Enable automatic updates: Turn on/off the update checking system\n"
            + "• Automatically download: Download updates without prompting\n"
            + "• Automatically install: Install updates without prompting (requires restart)\n"
            + "• Check on startup: Check for updates when the application starts\n"
            + "• Desktop notifications: Report update events in the system tray\n"
            + "• Update Channel: Stable (final releases), Beta (pre-releases) or Nightly (every build)\n"
            + "• Check Interval: How often to check for updates (1-720 hours). Leaving it at the\n"
            + "  channel default keeps the frequency recommended for the selected channel.\n\n"
            + "Note: Critical security updates will always be notified regardless of settings."
        );

        JScrollPane scrollPane = new JScrollPane(infoText);
        scrollPane.setPreferredSize(new Dimension(400, 170));

        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    public boolean isUpdateEnabled() {
        return enabledCheckBox.isSelected();
    }

    public boolean isAutoDownloadEnabled() {
        return autoDownloadCheckBox.isSelected();
    }

    public boolean isAutoInstallEnabled() {
        return autoInstallCheckBox.isSelected();
    }

    public boolean isNotifyOnStartupEnabled() {
        return notifyOnStartupCheckBox.isSelected();
    }

    public boolean isTrayNotificationsEnabled() {
        return trayNotificationsCheckBox.isSelected();
    }

    /**
     * Configuration value of the selected channel ({@code stable}, {@code beta},
     * {@code nightly}).
     */
    public String getUpdateChannel() {
        UpdateChannel selected = getSelectedChannel();
        return selected.getConfigValue();
    }

    /**
     * Channel selected in the combo box.
     */
    public UpdateChannel getSelectedChannel() {
        UpdateChannel selected = (UpdateChannel) channelComboBox.getSelectedItem();
        return selected != null ? selected : UpdateChannel.STABLE;
    }

    public int getCheckIntervalHours() {
        return (Integer) checkIntervalSpinner.getValue();
    }

    public void setUpdateEnabled(boolean enabled) {
        enabledCheckBox.setSelected(enabled);
    }

    public void setAutoDownloadEnabled(boolean enabled) {
        autoDownloadCheckBox.setSelected(enabled);
    }

    public void setAutoInstallEnabled(boolean enabled) {
        autoInstallCheckBox.setSelected(enabled);
    }

    public void setNotifyOnStartupEnabled(boolean enabled) {
        notifyOnStartupCheckBox.setSelected(enabled);
    }

    public void setTrayNotificationsEnabled(boolean enabled) {
        trayNotificationsCheckBox.setSelected(enabled);
    }

    public void setUpdateChannel(String channel) {
        channelComboBox.setSelectedItem(UpdateChannel.parse(channel));
    }

    public void setCheckIntervalHours(int hours) {
        checkIntervalSpinner.setValue(hours);
    }

    /**
     * Reflects an {@code update-config.properties} content in the form.
     *
     * @param properties the configuration to display
     */
    public void loadFrom(Properties properties) {
        if (properties == null) {
            log.debug("No properties provided, keeping the current form values");
            return;
        }

        setUpdateEnabled(Boolean.parseBoolean(properties.getProperty("update.enabled", "true")));
        setAutoDownloadEnabled(Boolean.parseBoolean(properties.getProperty("update.auto.download", "false")));
        setAutoInstallEnabled(Boolean.parseBoolean(properties.getProperty("update.auto.install", "false")));
        setNotifyOnStartupEnabled(Boolean.parseBoolean(properties.getProperty("update.notify.on.startup", "true")));
        setTrayNotificationsEnabled(Boolean.parseBoolean(
            properties.getProperty("update.notifications.tray.enabled", "true")
        ));
        setUpdateChannel(properties.getProperty("update.channel", UpdateChannel.STABLE.getConfigValue()));

        String interval = properties.getProperty("update.check.interval.hours", "");
        if (!interval.isBlank()) {
            try {
                setCheckIntervalHours(Integer.parseInt(interval.trim()));
            } catch (NumberFormatException e) {
                log.warn("Ignoring unparsable update.check.interval.hours '{}'", interval);
            }
        }
    }

    /**
     * Writes the form values back to a configuration object.
     *
     * @param properties the configuration to update, created when {@code null}
     * @return the updated configuration
     */
    public Properties applyTo(Properties properties) {
        Properties target = properties != null ? properties : new Properties();

        target.setProperty("update.enabled", String.valueOf(isUpdateEnabled()));
        target.setProperty("update.auto.download", String.valueOf(isAutoDownloadEnabled()));
        target.setProperty("update.auto.install", String.valueOf(isAutoInstallEnabled()));
        target.setProperty("update.notify.on.startup", String.valueOf(isNotifyOnStartupEnabled()));
        target.setProperty("update.notifications.tray.enabled", String.valueOf(isTrayNotificationsEnabled()));
        target.setProperty("update.channel", getUpdateChannel());
        target.setProperty("update.check.interval.hours", String.valueOf(getCheckIntervalHours()));

        return target;
    }

    public static JPanel createSettingsPanel() {
        return new UpdateSettingsPanel();
    }

    /**
     * Renders a channel with its display name and description.
     */
    private static class ChannelRenderer extends DefaultListCellRenderer {

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            Component component = super.getListCellRendererComponent(
                list, value, index, isSelected, cellHasFocus
            );

            if (value instanceof UpdateChannel channel) {
                setText(channel.getDisplayName());
                setToolTipText(channel.getDescription());
            }

            return component;
        }
    }
}
