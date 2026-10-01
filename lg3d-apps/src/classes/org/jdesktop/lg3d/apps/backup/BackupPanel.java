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
package org.jdesktop.lg3d.apps.backup;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
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
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The backup/restore tool's user interface: a profile list on the left, a
 * profile editor in the centre, and an action/progress/log strip along the
 * bottom. One panel serves both the 3D desktop (hosted on a SwingNode inside a
 * Frame3D by the {@code Backup} wrapper) and the 2D/Swing desktop (opened as an
 * MDI internal frame via {@code Desktop2DAppRegistry.PANEL_APPS}).
 *
 * <p>All I/O runs on a {@link SwingWorker}; the EDT only ever paints. Archive
 * creation and extraction are delegated to the AWT-free {@link BackupEngine},
 * so the security-hardening (Zip-Slip guard) and reliability (atomic writes)
 * live in one tested place. {@link JFileChooser}s are created lazily inside
 * action handlers, never in the constructor, so the panel builds headless.</p>
 */
public class BackupPanel extends JPanel {

    /** Preferred width in pixels. */
    public static final int WIDTH_PX = 860;
    /** Preferred height in pixels. */
    public static final int HEIGHT_PX = 580;

    private static final Integer[] LEVELS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

    private final BackupStore store;
    private final BackupEngine engine;
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    private final DefaultListModel<BackupProfile> profileModel = new DefaultListModel<>();
    private final JList<BackupProfile> profileList = new JList<>(profileModel);
    private final DefaultListModel<String> sourceModel = new DefaultListModel<>();
    private final JList<String> sourceList = new JList<>(sourceModel);

    private final JTextField nameField = new JTextField(18);
    private final JTextField archiveBaseField = new JTextField(12);
    private final JTextField destField = new JTextField(18);
    private final JTextField excludeField = new JTextField(18);
    private final JComboBox<Integer> compressionCombo = new JComboBox<>(LEVELS);
    private final JCheckBox includeHiddenCheck = new JCheckBox("Include hidden files");
    private final JCheckBox timestampCheck = new JCheckBox("Timestamp archive name");

    private final JButton newBtn = new JButton("New");
    private final JButton dupBtn = new JButton("Duplicate");
    private final JButton delBtn = new JButton("Delete");
    private final JButton addFileBtn = new JButton("Add File...");
    private final JButton addDirBtn = new JButton("Add Folder...");
    private final JButton removeSrcBtn = new JButton("Remove");
    private final JButton chooseDestBtn = new JButton("Browse...");
    private final JButton backupBtn = new JButton("Back Up Now");
    private final JButton restoreBtn = new JButton("Restore...");
    private final JButton browseBtn = new JButton("Browse Archive...");
    private final JButton cancelBtn = new JButton("Cancel");
    private final JButton closeBtn = new JButton("Close");

    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel statusLabel = new JLabel("Ready");
    private final JTextArea logArea = new JTextArea(8, 40);

    private boolean populating;
    private Runnable onClose;
    private SwingWorker<?, ?> currentWorker;

    /** Builds the panel with the default store and engine. */
    public BackupPanel() {
        this(new BackupStore(), new BackupEngine());
    }

    /**
     * Builds the panel with explicit collaborators (package-private for tests).
     *
     * @param store  the profile store
     * @param engine the backup engine
     */
    BackupPanel(BackupStore store, BackupEngine engine) {
        this.store = store;
        this.engine = engine;
        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        add(new JLabel("Backup & Restore", JLabel.CENTER), BorderLayout.NORTH);
        add(buildProfilePane(), BorderLayout.WEST);
        add(buildEditorPane(), BorderLayout.CENTER);
        add(buildBottomPane(), BorderLayout.SOUTH);

        wireListeners();
        loadProfiles();
        setBusy(false);
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private JComponent buildProfilePane() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Profiles"));
        panel.setPreferredSize(new Dimension(190, 100));
        profileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        profileList.setVisibleRowCount(12);
        panel.add(new JScrollPane(profileList), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        buttons.add(newBtn);
        buttons.add(dupBtn);
        buttons.add(delBtn);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildEditorPane() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Profile Settings"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        addRow(panel, c, row++, "Name:", nameField);
        addRow(panel, c, row++, "Archive base name:", archiveBaseField);

        // Destination row with a Browse button.
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        panel.add(new JLabel("Destination folder:"), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(destField, c);
        c.gridx = 2;
        c.weightx = 0;
        panel.add(chooseDestBtn, c);
        row++;

        // Sources row: a scrollable list with add/remove buttons.
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        c.gridwidth = 1;
        panel.add(new JLabel("Sources:"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.gridheight = 4;
        sourceList.setVisibleRowCount(5);
        panel.add(new JScrollPane(sourceList), c);
        c.gridx = 2;
        c.weightx = 0;
        c.gridheight = 1;
        JPanel srcButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        srcButtons.add(addFileBtn);
        srcButtons.add(addDirBtn);
        srcButtons.add(removeSrcBtn);
        panel.add(srcButtons, c);
        row += 4;

        c.gridwidth = 1;
        c.gridheight = 1;
        addRow(panel, c, row++, "Compression level:", compressionCombo);
        addRow(panel, c, row++, "Exclude patterns:", excludeField);

        JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        opts.add(includeHiddenCheck);
        opts.add(timestampCheck);
        c.gridx = 1;
        c.gridy = row++;
        c.weightx = 1;
        panel.add(opts, c);

        c.gridx = 0;
        c.gridy = row;
        c.weighty = 1;
        panel.add(new JLabel(""), c);
        return panel;
    }

    private void addRow(JPanel panel, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.gridwidth = 2;
        panel.add(field, c);
        c.gridwidth = 1;
    }

    private JComponent buildBottomPane() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        actions.add(backupBtn);
        actions.add(restoreBtn);
        actions.add(browseBtn);
        actions.add(cancelBtn);
        actions.add(closeBtn);

        JPanel progress = new JPanel(new BorderLayout(4, 2));
        progress.add(statusLabel, BorderLayout.NORTH);
        progress.add(progressBar, BorderLayout.CENTER);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Log"));
        logScroll.setPreferredSize(new Dimension(100, 150));

        panel.add(actions, BorderLayout.NORTH);
        panel.add(progress, BorderLayout.CENTER);
        panel.add(logScroll, BorderLayout.SOUTH);
        return panel;
    }

    private void wireListeners() {
        profileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                populateEditor(profileList.getSelectedValue());
            }
        });
        newBtn.addActionListener(e -> newProfile());
        dupBtn.addActionListener(e -> duplicateSelected());
        delBtn.addActionListener(e -> deleteSelectedProfile());
        addFileBtn.addActionListener(e -> addSourceFiles());
        addDirBtn.addActionListener(e -> addSourceDir());
        removeSrcBtn.addActionListener(e -> removeSelectedSource());
        chooseDestBtn.addActionListener(e -> chooseDestination());
        backupBtn.addActionListener(e -> runBackup());
        restoreBtn.addActionListener(e -> runRestore());
        browseBtn.addActionListener(e -> browseArchive());
        cancelBtn.addActionListener(e -> requestCancel());
        closeBtn.addActionListener(e -> {
            commitAndPersist();
            if (onClose != null) {
                onClose.run();
            }
        });

        bindText(nameField, (p, t) -> p.setName(t));
        bindText(archiveBaseField, (p, t) -> p.setArchiveBaseName(t));
        bindText(destField, (p, t) -> p.setDestinationDir(t));
        bindText(excludeField, (p, t) -> p.setExcludePatternsFromCsv(t));
        compressionCombo.addActionListener(e -> {
            if (!populating) {
                BackupProfile p = selectedProfile();
                if (p != null && compressionCombo.getSelectedItem() != null) {
                    p.setCompressionLevel((Integer) compressionCombo.getSelectedItem());
                }
            }
        });
        includeHiddenCheck.addActionListener(e -> {
            if (!populating) {
                BackupProfile p = selectedProfile();
                if (p != null) {
                    p.setIncludeHidden(includeHiddenCheck.isSelected());
                }
            }
        });
        timestampCheck.addActionListener(e -> {
            if (!populating) {
                BackupProfile p = selectedProfile();
                if (p != null) {
                    p.setTimestampArchiveName(timestampCheck.isSelected());
                }
            }
        });
    }

    private interface ProfileSetter {
        void set(BackupProfile profile, String text);
    }

    private void bindText(JTextField field, ProfileSetter setter) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                apply();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                apply();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                apply();
            }

            private void apply() {
                if (populating) {
                    return;
                }
                BackupProfile p = selectedProfile();
                if (p != null) {
                    setter.set(p, field.getText());
                    profileList.repaint();
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Profile management
    // ------------------------------------------------------------------

    private void loadProfiles() {
        profileModel.clear();
        for (BackupProfile p : store.loadProfiles()) {
            profileModel.addElement(p);
        }
        if (profileModel.isEmpty()) {
            profileModel.addElement(defaultProfile());
        }
        if (!profileModel.isEmpty()) {
            profileList.setSelectedIndex(0);
        }
        populateEditor(profileList.getSelectedValue());
    }

    private static BackupProfile defaultProfile() {
        BackupProfile p = new BackupProfile("My Documents",
                System.getProperty("user.home"), "documents-backup");
        Path docs = Paths.get(System.getProperty("user.home"), "Documents");
        if (Files.exists(docs)) {
            p.getSources().add(docs.toString());
        }
        return p;
    }

    /** Adds a fresh profile, selects it and persists. */
    public void newProfile() {
        BackupProfile p = new BackupProfile("New Backup",
                System.getProperty("user.home"), "backup");
        profileModel.addElement(p);
        commitAndPersist();
        profileList.setSelectedIndex(profileModel.size() - 1);
    }

    private void duplicateSelected() {
        BackupProfile p = selectedProfile();
        if (p == null) {
            return;
        }
        BackupProfile copy = p.copy();
        copy.setName(p.getName() + " (copy)");
        profileModel.addElement(copy);
        commitAndPersist();
        profileList.setSelectedIndex(profileModel.size() - 1);
    }

    /** Removes the selected profile and persists. */
    public void deleteSelectedProfile() {
        int idx = profileList.getSelectedIndex();
        if (idx < 0) {
            return;
        }
        profileModel.remove(idx);
        if (profileModel.isEmpty()) {
            profileModel.addElement(defaultProfile());
        }
        commitAndPersist();
        profileList.setSelectedIndex(Math.min(idx, profileModel.size() - 1));
    }

    private void populateEditor(BackupProfile p) {
        populating = true;
        try {
            if (p == null) {
                nameField.setText("");
                archiveBaseField.setText("");
                destField.setText("");
                excludeField.setText("");
                sourceModel.clear();
                return;
            }
            nameField.setText(p.getName());
            archiveBaseField.setText(p.getArchiveBaseName());
            destField.setText(p.getDestinationDir());
            excludeField.setText(p.getExcludePatternsAsCsv());
            compressionCombo.setSelectedItem(p.getCompressionLevel());
            includeHiddenCheck.setSelected(p.isIncludeHidden());
            timestampCheck.setSelected(p.isTimestampArchiveName());
            sourceModel.clear();
            for (String s : p.getSources()) {
                sourceModel.addElement(s);
            }
        } finally {
            populating = false;
        }
    }

    // ------------------------------------------------------------------
    // Source / destination pickers
    // ------------------------------------------------------------------

    private void addSourceFiles() {
        JFileChooser fc = new JFileChooser(lastDir());
        fc.setMultiSelectionEnabled(true);
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File f : fc.getSelectedFiles()) {
                addSource(f.getAbsolutePath());
            }
        }
    }

    private void addSourceDir() {
        JFileChooser fc = new JFileChooser(lastDir());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            addSource(fc.getSelectedFile().getAbsolutePath());
        }
    }

    /** Adds a source path to the selected profile (package-visible for tests). */
    public void addSource(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) {
            return;
        }
        BackupProfile p = selectedProfile();
        if (p == null) {
            return;
        }
        if (!sourceModel.contains(absolutePath)) {
            sourceModel.addElement(absolutePath);
        }
        p.setSources(snapshotSources());
        commitAndPersist();
    }

    private void removeSelectedSource() {
        int idx = sourceList.getSelectedIndex();
        if (idx < 0) {
            return;
        }
        sourceModel.remove(idx);
        BackupProfile p = selectedProfile();
        if (p != null) {
            p.setSources(snapshotSources());
            commitAndPersist();
        }
    }

    private void chooseDestination() {
        JFileChooser fc = new JFileChooser(lastDir());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            destField.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private List<String> snapshotSources() {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < sourceModel.size(); i++) {
            list.add(sourceModel.get(i));
        }
        return list;
    }

    private File lastDir() {
        BackupProfile p = selectedProfile();
        if (p != null && p.getDestinationDir() != null) {
            return Paths.get(p.getDestinationDir()).toFile();
        }
        return new File(System.getProperty("user.home"));
    }

    // ------------------------------------------------------------------
    // Jobs
    // ------------------------------------------------------------------

    private void runBackup() {
        BackupProfile p = selectedProfile();
        if (p == null) {
            setStatus("No profile selected");
            return;
        }
        commitAndPersist();
        if (p.getSources().isEmpty()) {
            setStatus("Add at least one source folder or file");
            appendLog("Backup aborted: no sources in profile '" + p.getName() + "'.");
            return;
        }
        Path archive = engine.resolveArchiveName(p);
        appendLog("Backing up '" + p.getName() + "' to " + archive);
        startJob("Backing up", pl -> engine.backup(p, pl, cancelFlag::get));
    }

    private void runRestore() {
        JFileChooser fc = archiveChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path archive = fc.getSelectedFile().toPath();
        JFileChooser dc = new JFileChooser(lastDir());
        dc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        dc.setDialogTitle("Choose the folder to restore into");
        if (dc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path dest = dc.getSelectedFile().toPath();
        appendLog("Restoring " + archive.getFileName() + " into " + dest);
        startJob("Restoring", pl -> engine.restore(archive, dest, pl, cancelFlag::get));
    }

    private void browseArchive() {
        JFileChooser fc = archiveChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path archive = fc.getSelectedFile().toPath();
        try {
            List<ArchiveEntryInfo> entries = engine.list(archive);
            String[] lines = new String[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                ArchiveEntryInfo en = entries.get(i);
                lines[i] = en.isDirectory()
                        ? en.getName() + "  <folder>"
                        : en.getName() + "  (" + en.getSize() + " bytes)";
            }
            JOptionPane.showMessageDialog(this, new JScrollPane(new JList<>(lines)),
                    "Archive contents (" + entries.size() + " entries)",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Cannot read archive: " + ex.getMessage(),
                    "Backup", JOptionPane.ERROR_MESSAGE);
        }
    }

    private JFileChooser archiveChooser() {
        JFileChooser fc = new JFileChooser(lastDir());
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fc.setDialogTitle("Choose a backup archive (.zip)");
        return fc;
    }

    private void requestCancel() {
        if (currentWorker != null && !currentWorker.isDone()) {
            cancelFlag.set(true);
            setStatus("Cancelling...");
            appendLog("Cancel requested.");
        }
    }

    private void startJob(String description, Function<BackupProgressListener, BackupResult> job) {
        cancelFlag.set(false);
        setBusy(true);
        progressBar.setValue(0);
        setStatus(description + "...");
        SwingWorker<BackupResult, int[]> worker = new SwingWorker<>() {
            @Override
            protected BackupResult doInBackground() {
                BackupProgressListener pl = (entry, done, total, bytesDone, bytesTotal) -> {
                    int pct = (total > 0) ? (int) Math.min(100, done * 100 / total) : 0;
                    publish(new int[]{pct, (int) done, (int) total});
                };
                return job.apply(pl);
            }

            @Override
            protected void process(List<int[]> chunks) {
                int[] last = chunks.get(chunks.size() - 1);
                progressBar.setValue(last[0]);
                setStatus(description + " (" + last[1] + "/" + last[2] + " entries)");
            }

            @Override
            protected void done() {
                setBusy(false);
                try {
                    showResult(get());
                } catch (Exception ex) {
                    setStatus("Failed");
                    appendLog("Job error: " + ex.getMessage());
                }
            }
        };
        currentWorker = worker;
        worker.execute();
    }

    private void showResult(BackupResult r) {
        if (r == null) {
            return;
        }
        progressBar.setValue(r.isSuccess() ? 100 : progressBar.getValue());
        setStatus(r.isSuccess() ? "Done" : (r.isCancelled() ? "Cancelled" : "Failed"));
        appendLog(r.getMessage());
        for (String w : r.getWarnings()) {
            appendLog("  ! " + w);
        }
        if (!r.isSuccess() && !r.isCancelled()) {
            JOptionPane.showMessageDialog(this, r.getMessage(), "Backup",
                    JOptionPane.ERROR_MESSAGE);
        }
        commitAndPersist();
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private void setBusy(boolean busy) {
        backupBtn.setEnabled(!busy);
        restoreBtn.setEnabled(!busy);
        browseBtn.setEnabled(!busy);
        newBtn.setEnabled(!busy);
        dupBtn.setEnabled(!busy);
        delBtn.setEnabled(!busy);
        cancelBtn.setEnabled(busy);
        progressBar.setIndeterminate(false);
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private void appendLog(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        logArea.append(line);
        logArea.append(System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void commitAndPersist() {
        store.saveProfiles(allProfiles());
    }

    private List<BackupProfile> allProfiles() {
        List<BackupProfile> list = new ArrayList<>();
        for (int i = 0; i < profileModel.size(); i++) {
            list.add(profileModel.get(i));
        }
        return list;
    }

    /**
     * Sets the callback invoked by the Close button. Wired by the 3D host to
     * disable its window and by the 2D host to close the MDI frame.
     *
     * @param onClose the close action, or null to clear
     */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    // ------------------------------------------------------------------
    // Package-private test hooks
    // ------------------------------------------------------------------

    BackupProfile selectedProfile() {
        return profileList.getSelectedValue();
    }

    int profileCount() {
        return profileModel.size();
    }

    BackupProfile profileAt(int i) {
        return profileModel.get(i);
    }

    void selectProfile(int i) {
        profileList.setSelectedIndex(i);
    }

    int sourceCount() {
        return sourceModel.size();
    }

    String sourceAt(int i) {
        return sourceModel.get(i);
    }

    String statusText() {
        return statusLabel.getText();
    }

    JTextField nameField() {
        return nameField;
    }

    BackupEngine engine() {
        return engine;
    }
}
