package com.protonmail.landrevillejf.swingide.update.ui;

import com.protonmail.landrevillejf.swingide.update.UpdateInfo;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.function.Supplier;

@Slf4j
public class UpdateAvailableDialog extends JDialog {
    
    private final UpdateInfo updateInfo;
    private final String currentVersion;
    private final Supplier<String> changelogHtmlSupplier;
    private Choice choice = Choice.DISMISSED;
    
    public UpdateAvailableDialog(Frame parent, UpdateInfo updateInfo, String currentVersion) {
        this(parent, updateInfo, currentVersion, null);
    }
    
    /**
     * Creates the dialog, optionally with a changelog source.
     *
     * @param parent                 the owning frame, may be {@code null}
     * @param updateInfo             the available update
     * @param currentVersion         the running version
     * @param changelogHtmlSupplier  blocking supplier returning the rendered
     *                               changelog, or {@code null} to hide the button
     */
    public UpdateAvailableDialog(Frame parent, UpdateInfo updateInfo, String currentVersion,
                                 Supplier<String> changelogHtmlSupplier) {
        super(parent, "Update Available", true);
        this.updateInfo = updateInfo;
        this.currentVersion = currentVersion;
        this.changelogHtmlSupplier = changelogHtmlSupplier;
        initializeUI();
    }
    
    private void initializeUI() {
        setSize(500, 350);
        setLocationRelativeTo(getParent());
        setResizable(false);
        
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        
        // Header
        JPanel headerPanel = createHeaderPanel();
        mainPanel.add(headerPanel, BorderLayout.NORTH);
        
        // Details
        JPanel detailsPanel = createDetailsPanel();
        mainPanel.add(detailsPanel, BorderLayout.CENTER);
        
        // Buttons
        JPanel buttonPanel = createButtonPanel();
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        add(mainPanel);
    }
    
    private JPanel createHeaderPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        
        JLabel titleLabel = new JLabel("New Update Available");
        titleLabel.setFont(new Font(titleLabel.getFont().getName(), Font.BOLD, 18));
        
        String iconText = updateInfo.isCritical() ? "⚠️" : "📦";
        JLabel iconLabel = new JLabel(iconText);
        iconLabel.setFont(new Font(iconLabel.getFont().getName(), Font.PLAIN, 24));
        
        panel.add(iconLabel, BorderLayout.WEST);
        panel.add(titleLabel, BorderLayout.CENTER);
        
        return panel;
    }
    
    private JPanel createDetailsPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        
        // Top section with version info
        JPanel versionPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        
        // Current version
        gbc.gridx = 0;
        gbc.gridy = 0;
        versionPanel.add(new JLabel("Current Version:"), gbc);
        gbc.gridx = 1;
        versionPanel.add(new JLabel(currentVersion), gbc);
        
        // New version
        gbc.gridx = 0;
        gbc.gridy = 1;
        versionPanel.add(new JLabel("New Version:"), gbc);
        gbc.gridx = 1;
        JLabel newVersionLabel = new JLabel(updateInfo.getVersion());
        newVersionLabel.setFont(new Font(newVersionLabel.getFont().getName(), Font.BOLD, 12));
        versionPanel.add(newVersionLabel, gbc);
        
        // Release date
        gbc.gridx = 0;
        gbc.gridy = 2;
        versionPanel.add(new JLabel("Release Date:"), gbc);
        gbc.gridx = 1;
        versionPanel.add(new JLabel(updateInfo.getReleaseDate().toString()), gbc);
        
        // Size
        gbc.gridx = 0;
        gbc.gridy = 3;
        versionPanel.add(new JLabel("Download Size:"), gbc);
        gbc.gridx = 1;
        versionPanel.add(new JLabel(updateInfo.getFormattedSize()), gbc);
        
        panel.add(versionPanel, BorderLayout.NORTH);
        
        // Warnings panel
        JPanel warningsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints warningGbc = new GridBagConstraints();
        warningGbc.gridx = 0;
        warningGbc.gridy = 0;
        warningGbc.gridwidth = 2;
        warningGbc.insets = new Insets(5, 5, 5, 5);
        
        if (updateInfo.isCritical()) {
            JLabel warningLabel = new JLabel(
                "<html><font color='red'><b>⚠️ Critical Security Update</b></font></html>"
            );
            warningsPanel.add(warningLabel, warningGbc);
            warningGbc.gridy++;
        }
        
        if (updateInfo.isBreakingChanges()) {
            JLabel warningLabel = new JLabel(
                "<html><font color='orange'>⚡ This update contains breaking changes</font></html>"
            );
            warningsPanel.add(warningLabel, warningGbc);
            warningGbc.gridy++;
        }
        
        if (warningGbc.gridy > 0) {
            panel.add(warningsPanel, BorderLayout.CENTER);
        }
        
        return panel;
    }
    
    private JPanel createButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        
        if (changelogHtmlSupplier != null) {
            JButton changelogButton = new JButton("View Changelog");
            changelogButton.setPreferredSize(new Dimension(150, 30));
            changelogButton.addActionListener(this::onChangelogClicked);
            panel.add(changelogButton);
        }
        
        JButton updateButton = new JButton("Update Now");
        updateButton.setPreferredSize(new Dimension(120, 30));
        updateButton.addActionListener(this::onUpdateClicked);
        
        JButton laterButton = new JButton("Remind Me Later");
        laterButton.setPreferredSize(new Dimension(140, 30));
        laterButton.addActionListener(this::onLaterClicked);
        
        JButton skipButton = new JButton("Skip This Version");
        skipButton.setPreferredSize(new Dimension(150, 30));
        skipButton.addActionListener(this::onSkipClicked);
        
        panel.add(updateButton);
        panel.add(laterButton);
        panel.add(skipButton);
        
        return panel;
    }
    
    /**
     * Fetches the changelog off the EDT and displays it in a modal dialog.
     */
    private void onChangelogClicked(ActionEvent e) {
        JButton source = (JButton) e.getSource();
        source.setEnabled(false);
        source.setText("Loading...");
        
        Thread loader = new Thread(() -> {
            String html = null;
            try {
                html = changelogHtmlSupplier.get();
            } catch (RuntimeException ex) {
                log.warn("Could not load the changelog: {}", ex.getMessage());
            }
            
            final String rendered = html;
            SwingUtilities.invokeLater(() -> {
                source.setEnabled(true);
                source.setText("View Changelog");
                ChangelogDialog.show(
                    this,
                    "What's new in " + updateInfo.getVersion(),
                    rendered
                );
            });
        }, "Changelog-Loader");
        loader.setDaemon(true);
        loader.start();
    }
    
    private void onUpdateClicked(ActionEvent e) {
        choice = Choice.UPDATE_NOW;
        dispose();
    }
    
    private void onLaterClicked(ActionEvent e) {
        choice = Choice.SCHEDULE_LATER;
        dispose();
    }
    
    private void onSkipClicked(ActionEvent e) {
        choice = Choice.SKIP_VERSION;
        dispose();
    }
    
    /**
     * Button pressed by the user, {@link Choice#DISMISSED} when the dialog was
     * simply closed.
     */
    public Choice getChoice() {
        return choice;
    }

    public boolean showAndWait() {
        setVisible(true);
        return choice == Choice.UPDATE_NOW;
    }

    /**
     * Outcome of the "update available" dialog.
     */
    public enum Choice {
        /** Install immediately. */
        UPDATE_NOW,
        /** Defer the installation; the host may offer a scheduled install. */
        SCHEDULE_LATER,
        /** Never propose this version again. */
        SKIP_VERSION,
        /** Closed without an explicit choice. */
        DISMISSED
    }
    
    public static boolean showUpdateAvailable(Frame parent, UpdateInfo updateInfo, String currentVersion) {
        UpdateAvailableDialog dialog = new UpdateAvailableDialog(parent, updateInfo, currentVersion);
        return dialog.showAndWait();
    }
    
    /**
     * Shows the dialog with a changelog source.
     *
     * @param parent                the owning frame, may be {@code null}
     * @param updateInfo            the available update
     * @param currentVersion        the running version
     * @param changelogHtmlSupplier blocking supplier returning the rendered changelog
     * @return {@code true} when the user accepted the update
     */
    public static boolean showUpdateAvailable(Frame parent, UpdateInfo updateInfo, String currentVersion,
                                              Supplier<String> changelogHtmlSupplier) {
        UpdateAvailableDialog dialog = new UpdateAvailableDialog(
            parent, updateInfo, currentVersion, changelogHtmlSupplier
        );
        return dialog.showAndWait();
    }
}
