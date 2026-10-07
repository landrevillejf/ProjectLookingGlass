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
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Dimension;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * The Archive application's user interface: choose a ZIP or TAR archive, list
 * its entries, extract it to a chosen directory, or create a new ZIP from a
 * directory. One panel serves both the 3D desktop (hosted on a SwingNode
 * inside a Frame3D by the {@link Archive} wrapper via {@code TitledSwingWindow})
 * and the 2D/Swing desktop (opened as an MDI internal frame through
 * {@code Desktop2DAppRegistry.PANEL_APPS}).
 *
 * <p>All blocking archive I/O runs on a {@link SwingWorker}; the EDT only ever
 * paints. The heavy lifting is delegated to the AWT-free {@link ZipManager} and
 * {@link TarManager}, which are Zip-Slip hardened. File and directory pickers
 * use {@link JFileChooser}, whose top-level dialog {@code SwingNode} captures
 * into the 3D scene.</p>
 */
@Slf4j
public class ArchivePanel extends JPanel {

    /** Panel size in native pixels; the wrapper hands these to TitledSwingWindow. */
    public static final int WIDTH_PX = 640;
    public static final int HEIGHT_PX = 420;

    private final JTextField pathField = new JTextField(24);
    private final DefaultListModel<String> entryModel = new DefaultListModel<>();
    private final JLabel statusLabel = new JLabel(" ");
    private final ZipManager zipManager;
    private final TarManager tarManager;
    private File currentArchive;
    private Runnable onClose;

    /** Creates the panel with its own archive engines. */
    public ArchivePanel() {
        this(new ZipManager(), new TarManager());
    }

    /**
     * Creates the panel.
     *
     * @param zipManager ZIP operations
     * @param tarManager TAR operations
     */
    public ArchivePanel(ZipManager zipManager, TarManager tarManager) {
        super(new BorderLayout(4, 4));
        this.zipManager = zipManager;
        this.tarManager = tarManager;
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        JButton browseButton = new JButton("Browse...");
        browseButton.addActionListener(e -> chooseArchive());
        JButton listButton = new JButton("List Entries");
        listButton.addActionListener(e -> listEntries());
        JButton extractButton = new JButton("Extract...");
        extractButton.addActionListener(e -> extractArchive());
        JButton createButton = new JButton("Create ZIP...");
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
        save.setDialogTitle("Choose the ZIP file to create");
        if (save.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File selected = save.getSelectedFile();
        final File targetZip = selected.getName().toLowerCase(Locale.ROOT).endsWith(".zip")
                ? selected
                : new File(selected.getParentFile(), selected.getName() + ".zip");
        setStatus("Creating " + targetZip.getName() + "...");
        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws IOException {
                return zipManager.create(sourceDir, targetZip);
            }

            @Override
            protected void done() {
                try {
                    int count = get();
                    setStatus("Created " + targetZip.getName() + " with " + count + " files");
                } catch (Exception ex) {
                    log.warn("Creation failed: {}", ex.getMessage());
                    setStatus("Creation failed");
                }
            }
        }.execute();
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
        return isZip(archive) ? zipManager.list(archive) : tarManager.list(archive);
    }

    private int extract(File archive, File targetDir) throws IOException {
        return isZip(archive)
                ? zipManager.extract(archive, targetDir)
                : tarManager.extract(archive, targetDir);
    }

    private boolean isZip(File archive) {
        return archive.getName().toLowerCase(Locale.ROOT).endsWith(".zip");
    }
}
