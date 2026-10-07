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
package org.jdesktop.lg3d.apps.archive;

import lombok.extern.slf4j.Slf4j;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * The Archive application's user interface: choose an archive of any supported
 * format, list its entries, extract it to a chosen directory, or create a new
 * archive from a directory. One panel serves both the 3D desktop (hosted on a
 * SwingNode inside a Frame3D by the {@link Archive} wrapper via
 * {@code TitledSwingWindow}) and the 2D/Swing desktop (opened as an MDI internal
 * frame through {@code Desktop2DAppRegistry.PANEL_APPS}).
 *
 * <p>All blocking archive I/O runs on a {@link SwingWorker}; the EDT only ever
 * paints. The heavy lifting is delegated to the AWT-free, Zip-Slip hardened
 * {@link ArchiveManager}, which reads and writes every common open archive
 * format through Apache Commons Compress. File and directory pickers use
 * {@link JFileChooser}, whose top-level dialog {@code SwingNode} captures into
 * the 3D scene.</p>
 */
@Slf4j
public class ArchivePanel extends JPanel {

    /** Panel size in native pixels; the wrapper hands these to TitledSwingWindow. */
    public static final int WIDTH_PX = 640;
    public static final int HEIGHT_PX = 420;

    /** Extensions offered by the archive file pickers (all open formats). */
    private static final String[] ARCHIVE_EXTENSIONS = {
            "zip", "7z", "tar", "gz", "tgz", "bz2", "tbz", "tbz2",
            "xz", "txz", "lzma", "cpio", "ar",
    };

    private final JTextField pathField = new JTextField(24);
    private final DefaultListModel<String> entryModel = new DefaultListModel<>();
    private final JLabel statusLabel = new JLabel(" ");
    private final ArchiveManager archiveManager;
    private File currentArchive;
    private Runnable onClose;

    /** Creates the panel with its own archive engine. */
    public ArchivePanel() {
        this(new ArchiveManager());
    }

    /**
     * Creates the panel.
     *
     * @param archiveManager the multi-format archive engine
     */
    public ArchivePanel(ArchiveManager archiveManager) {
        super(new BorderLayout(4, 4));
        this.archiveManager = archiveManager;
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        JButton browseButton = new JButton("Browse...");
        browseButton.addActionListener(e -> chooseArchive());
        JButton listButton = new JButton("List Entries");
        listButton.addActionListener(e -> listEntries());
        JButton extractButton = new JButton("Extract...");
        extractButton.addActionListener(e -> extractArchive());
        JButton createButton = new JButton("Create...");
        createButton.addActionListener(e -> createArchive());
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> {
            if (onClose != null) {
                onClose.run();
            }
        });

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        top.add(pathField);
        top.add(browseButton);
        top.add(listButton);
        top.add(extractButton);
        top.add(createButton);
        top.add(closeButton);
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(new JList<>(entryModel)), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    /**
     * Registers the callback invoked when the user clicks Close, so the host
     * window can discard itself (the 3D wrapper disables its Frame3D; the 2D MDI
     * frame closes). Optional: the registry only calls it when present.
     *
     * @param onClose the close callback
     */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /**
     * Shows the given archive file in the panel and lists its entries.
     *
     * @param archive the archive to display
     */
    public void showArchive(File archive) {
        currentArchive = archive;
        SwingUtilities.invokeLater(() -> pathField.setText(archive.getAbsolutePath()));
        listEntries();
    }

    /** Number of entries currently listed (package-private test hook). */
    int entryCount() {
        return entryModel.size();
    }

    /** The archive currently loaded, or {@code null} (package-private test hook). */
    File currentArchive() {
        return currentArchive;
    }

    private void chooseArchive() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Archives (zip, 7z, tar, tar.gz, tar.bz2, tar.xz, gz, bz2, xz, cpio, ar)",
                ARCHIVE_EXTENSIONS));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            showArchive(chooser.getSelectedFile());
        }
    }

    private void listEntries() {
        if (notReady()) {
            return;
        }
        final File archive = currentArchive;
        setStatus("Listing " + archive.getName() + "...");
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws IOException {
                return list(archive);
            }

            @Override
            protected void done() {
                try {
                    List<String> entries = get();
                    entryModel.clear();
                    entries.forEach(entryModel::addElement);
                    setStatus(entries.size() + " entries in " + archive.getName());
                } catch (Exception ex) {
                    log.warn("Failed to list archive: {}", ex.getMessage());
                    setStatus("Failed to list archive");
                }
            }
        }.execute();
    }

    private void extractArchive() {
        if (notReady()) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        final File archive = currentArchive;
        final File targetDir = chooser.getSelectedFile();
        setStatus("Extracting to " + targetDir.getName() + "...");
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws IOException {
                return extract(archive, targetDir);
            }

            @Override
            protected void done() {
                try {
                    int count = get();
                    log.info("Extracted {} files to {}", count, targetDir);
                    setStatus("Extracted " + count + " files to " + targetDir.getAbsolutePath());
                } catch (Exception ex) {
                    log.warn("Extraction failed: {}", ex.getMessage());
                    setStatus("Extraction failed");
                }
            }
        }.execute();
    }

    private void createArchive() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Choose the folder to compress");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        final File sourceDir = chooser.getSelectedFile();
        JFileChooser save = new JFileChooser();
        save.setDialogTitle("Choose the archive to create (.zip, .tar, .tar.gz, .tar.bz2, .tar.xz)");
        if (save.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File selected = save.getSelectedFile();
        final File target = hasKnownArchiveSuffix(selected.getName())
                ? selected
                : new File(selected.getParentFile(), selected.getName() + ".zip");
        setStatus("Creating " + target.getName() + "...");
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws IOException {
                return archiveManager.create(sourceDir, target);
            }

            @Override
            protected void done() {
                try {
                    int count = get();
                    setStatus("Created " + target.getName() + " with " + count + " files");
                } catch (Exception ex) {
                    log.warn("Creation failed: {}", ex.getMessage());
                    setStatus("Creation failed");
                }
            }
        }.execute();
    }

    /** True when the name already ends in one of the extensions we can create. */
    private static boolean hasKnownArchiveSuffix(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : ARCHIVE_EXTENSIONS) {
            if (lower.endsWith("." + ext)) {
                return true;
            }
        }
        return false;
    }

    private boolean notReady() {
        if (currentArchive == null || !currentArchive.isFile()) {
            log.debug("No archive selected");
            setStatus("No archive selected");
            return true;
        }
        return false;
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(text));
    }

    private List<String> list(File archive) throws IOException {
        return archiveManager.list(archive);
    }

    private int extract(File archive, File targetDir) throws IOException {
        return archiveManager.extract(archive, targetDir);
    }
}
