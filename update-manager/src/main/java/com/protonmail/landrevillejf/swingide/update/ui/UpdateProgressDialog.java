package com.protonmail.landrevillejf.swingide.update.ui;

import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;

@Slf4j
public class UpdateProgressDialog extends JDialog {
    
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JLabel detailLabel;
    private JButton cancelButton;
    private boolean cancelled = false;
    
    public UpdateProgressDialog(Frame parent) {
        this(parent, true);
    }

    /**
     * Creates the progress dialog.
     * <p>
     * A modal dialog blocks the caller until the update finishes, which is only
     * usable from a background thread. Hosts driving the download from the EDT
     * must use the non-modal variant and close the dialog from their
     * {@code UpdateProgressEvent} / completion callbacks.
     * </p>
     *
     * @param parent the owning frame, may be {@code null}
     * @param modal  {@code true} to block the caller while the dialog is visible
     */
    public UpdateProgressDialog(Frame parent, boolean modal) {
        super(parent, "Updating Project Looking Glass", modal);
        initializeUI();
    }
    
    private void initializeUI() {
        setSize(450, 180);
        setLocationRelativeTo(getParent());
        setResizable(false);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        
        JPanel mainPanel = new JPanel(new BorderLayout(15, 15));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        
        // Status label
        statusLabel = new JLabel("Preparing update...");
        statusLabel.setFont(new Font(statusLabel.getFont().getName(), Font.BOLD, 14));
        mainPanel.add(statusLabel, BorderLayout.NORTH);
        
        // Progress panel
        JPanel progressPanel = new JPanel(new BorderLayout(5, 5));
        
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("0%");
        progressPanel.add(progressBar, BorderLayout.CENTER);
        
        detailLabel = new JLabel("Initializing...");
        detailLabel.setFont(new Font(detailLabel.getFont().getName(), Font.PLAIN, 11));
        progressPanel.add(detailLabel, BorderLayout.SOUTH);
        
        mainPanel.add(progressPanel, BorderLayout.CENTER);
        
        // Cancel button
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        cancelButton = new JButton("Cancel");
        cancelButton.setPreferredSize(new Dimension(100, 30));
        cancelButton.addActionListener(e -> onCancelClicked());
        buttonPanel.add(cancelButton);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        add(mainPanel);
    }
    
    private void onCancelClicked() {
        cancelled = true;
        cancelButton.setEnabled(false);
        statusLabel.setText("Cancelling...");
        log.debug("User cancelled update");
    }
    
    public void updateProgress(int percent, String status) {
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(percent);
            progressBar.setString(percent + "%");
            detailLabel.setText(status);
        });
    }
    
    public void updateStatus(String status) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(status);
        });
    }
    
    public void setIndeterminate(boolean indeterminate) {
        SwingUtilities.invokeLater(() -> {
            progressBar.setIndeterminate(indeterminate);
            if (indeterminate) {
                progressBar.setString("");
            }
        });
    }
    
    public void setCancelable(boolean cancelable) {
        SwingUtilities.invokeLater(() -> {
            cancelButton.setEnabled(cancelable);
        });
    }
    
    public void closeWithSuccess() {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Update completed successfully!");
            progressBar.setValue(100);
            progressBar.setString("100%");
            detailLabel.setText("Restarting application...");
            cancelButton.setEnabled(false);
            
            Timer timer = new Timer(2000, e -> dispose());
            timer.setRepeats(false);
            timer.start();
        });
    }
    
    public void closeWithError(String errorMessage) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Update Failed");
            detailLabel.setText(errorMessage);
            detailLabel.setForeground(Color.RED);
            progressBar.setValue(0);
            cancelButton.setText("Close");
            cancelButton.setEnabled(true);
            cancelButton.removeActionListener(cancelButton.getActionListeners()[0]);
            cancelButton.addActionListener(e -> dispose());
        });
    }
    
    public boolean isCancelled() {
        return cancelled;
    }
    
    public static UpdateProgressDialog showProgressDialog(Frame parent) {
        return showProgressDialog(parent, true);
    }

    /**
     * Displays the progress dialog on the event dispatch thread.
     *
     * @param parent the owning frame, may be {@code null}
     * @param modal  {@code true} to block the calling thread while visible
     * @return the visible dialog
     */
    public static UpdateProgressDialog showProgressDialog(Frame parent, boolean modal) {
        UpdateProgressDialog dialog = new UpdateProgressDialog(parent, modal);
        dialog.setVisible(true);
        return dialog;
    }
}
