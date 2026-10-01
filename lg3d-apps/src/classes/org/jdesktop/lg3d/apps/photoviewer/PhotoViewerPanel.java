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
package org.jdesktop.lg3d.apps.photoviewer;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * The photo viewer's user interface: a thumbnail gallery with tag / keyword /
 * rating filters on the left, a large preview with previous / next navigation on
 * the right, and a tag-and-rating editor along the bottom. One panel serves both
 * the 3D desktop (hosted on a SwingNode inside a Frame3D by the
 * {@code PhotoViewer} wrapper) and the 2D/Swing desktop (opened as an MDI
 * internal frame via {@code Desktop2DAppRegistry.PANEL_APPS}).
 *
 * <p>Everything is native: images are decoded with {@code ImageIO} and thumbnails
 * are cached lazily. No pixel is read, no folder scanned and no dialog opened
 * until the user acts, so the panel constructs and is asserted on headless -
 * the {@link PhotoLibrary} model behind it is pure and unit-testable.</p>
 */
public class PhotoViewerPanel extends JPanel {

    /** Preferred width in pixels. */
    public static final int WIDTH_PX = 900;
    /** Preferred height in pixels. */
    public static final int HEIGHT_PX = 620;

    /** Thumbnail edge length in pixels. */
    static final int THUMB_PX = 96;
    /** Preview box width in pixels. */
    static final int PREVIEW_W = 520;
    /** Preview box height in pixels. */
    static final int PREVIEW_H = 430;

    /** Lower-case image extensions the viewer recognises. */
    static final List<String> IMAGE_EXTENSIONS =
            List.of("jpg", "jpeg", "png", "gif", "bmp", "webp", "tif", "tiff");

    private final PhotoViewerStore store;
    private final PhotoLibrary library = new PhotoLibrary();
    private final Map<String, ImageIcon> thumbCache = new HashMap<>();

    private final DefaultListModel<PhotoItem> listModel = new DefaultListModel<>();
    private final JList<PhotoItem> gallery = new JList<>(listModel);
    private final JLabel preview = new JLabel("No photo selected", JLabel.CENTER);
    private final JLabel statusLabel = new JLabel("Ready");

    private final JComboBox<String> tagFilter = new JComboBox<>();
    private final JTextField keywordField = new JTextField(12);
    private final JSpinner ratingFilter =
            new JSpinner(new SpinnerNumberModel(0, 0, PhotoItem.MAX_RATING, 1));

    private final JTextField tagEditor = new JTextField(16);
    private final JSpinner ratingEditor =
            new JSpinner(new SpinnerNumberModel(0, 0, PhotoItem.MAX_RATING, 1));

    private final JButton prevBtn = new JButton("< Prev");
    private final JButton nextBtn = new JButton("Next >");

    private Runnable onClose;

    /** Builds the panel with the default store. */
    public PhotoViewerPanel() {
        this(new PhotoViewerStore());
    }

    /**
     * Builds the panel over an explicit store (package-private for tests).
     *
     * @param store the library store
     */
    PhotoViewerPanel(PhotoViewerStore store) {
        this.store = store;

        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildFilterPane(), BorderLayout.WEST);
        add(buildSplit(), BorderLayout.CENTER);
        add(buildBottomPane(), BorderLayout.SOUTH);

        library.addAll(store.loadLibrary());
        refreshTagFilter();
        refreshList();
        wireListeners();
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private Component buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(action("Open Folder...", this::chooseFolder));
        bar.add(action("Add Files...", this::chooseFiles));
        bar.addSeparator();
        bar.add(action("Remove", this::removeSelected));
        bar.add(action("Clear", this::clearLibrary));
        return bar;
    }

    private Component buildFilterPane() {
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Filter"));
        panel.setPreferredSize(new Dimension(210, 100));

        panel.add(new JLabel("Tag:"));
        tagFilter.setPreferredSize(new Dimension(180, 26));
        panel.add(tagFilter);
        panel.add(new JLabel("Keyword:"));
        panel.add(keywordField);
        panel.add(new JLabel("Min rating:"));
        panel.add(ratingFilter);
        JButton apply = new JButton("Apply");
        apply.addActionListener(e -> refreshList());
        panel.add(apply);
        return panel;
    }

    private Component buildSplit() {
        gallery.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        gallery.setVisibleRowCount(-1);
        gallery.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        gallery.setCellRenderer(new ThumbRenderer());
        JScrollPane galleryScroll = new JScrollPane(gallery);
        galleryScroll.setBorder(BorderFactory.createTitledBorder("Gallery"));
        galleryScroll.setPreferredSize(new Dimension(320, 100));

        JPanel previewPane = new JPanel(new BorderLayout(6, 6));
        previewPane.setBorder(BorderFactory.createTitledBorder("Preview"));
        preview.setVerticalTextPosition(JLabel.BOTTOM);
        preview.setHorizontalTextPosition(JLabel.CENTER);
        previewPane.add(preview, BorderLayout.CENTER);

        JPanel nav = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        nav.add(prevBtn);
        nav.add(nextBtn);
        previewPane.add(nav, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                galleryScroll, previewPane);
        split.setDividerLocation(330);
        split.setResizeWeight(0.35);
        return split;
    }

    private Component buildBottomPane() {
        JPanel panel = new JPanel(new BorderLayout(6, 4));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        left.add(new JLabel("Tags:"));
        left.add(tagEditor);
        left.add(action("Apply Tags", this::applyTags));
        left.add(new JLabel("Rating:"));
        ratingEditor.setPreferredSize(new Dimension(56, 26));
        left.add(ratingEditor);
        left.add(action("Set Rating", this::applyRating));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        JButton close = new JButton("Close");
        close.addActionListener(e -> {
            if (onClose != null) {
                onClose.run();
            }
        });
        right.add(close);

        JPanel row = new JPanel(new BorderLayout());
        row.add(left, BorderLayout.WEST);
        row.add(right, BorderLayout.EAST);
        panel.add(row, BorderLayout.NORTH);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        panel.add(statusLabel, BorderLayout.SOUTH);
        return panel;
    }

    private JButton action(String label, Runnable handler) {
        JButton button = new JButton(label);
        button.addActionListener(e -> handler.run());
        return button;
    }

    private void wireListeners() {
        prevBtn.addActionListener(e -> step(-1));
        nextBtn.addActionListener(e -> step(1));
        gallery.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelected();
            }
        });
        tagFilter.addActionListener(e -> refreshList());
        keywordField.addActionListener(e -> refreshList());
        ratingFilter.addChangeListener(e -> refreshList());
        ratingEditor.addChangeListener(e -> {
            PhotoItem item = gallery.getSelectedValue();
            if (item != null) {
                item.setRating((Integer) ratingEditor.getValue());
                persist();
            }
        });
    }

    // ------------------------------------------------------------------
    // Library editing
    // ------------------------------------------------------------------

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            scanFolder(chooser.getSelectedFile());
        }
    }

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Images", IMAGE_EXTENSIONS.toArray(new String[0])));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            int added = 0;
            for (File f : chooser.getSelectedFiles()) {
                if (library.add(PhotoItem.of(f.getAbsolutePath()))) {
                    added++;
                }
            }
            afterEdit(added + " photo(s) added.");
        }
    }

    /**
     * Adds every recognisable image in {@code dir} (non-recursive) to the
     * library. Package-visible so a test can grow the model without a dialog.
     *
     * @param dir the folder to scan
     * @return the number of photos added
     */
    int scanFolder(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return 0;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        int added = 0;
        for (File f : files) {
            if (f.isFile() && isImage(f.getName())) {
                if (library.add(PhotoItem.of(f.getAbsolutePath()))) {
                    added++;
                }
            }
        }
        afterEdit(added + " photo(s) found.");
        return added;
    }

    private void removeSelected() {
        PhotoItem item = gallery.getSelectedValue();
        if (item != null && library.removeByPath(item.getPath())) {
            thumbCache.remove(item.getPath());
            afterEdit("Removed " + item.getTitle() + ".");
        }
    }

    private void clearLibrary() {
        library.clear();
        thumbCache.clear();
        afterEdit("Library cleared.");
    }

    /**
     * Adds a single file to the library without reading its pixels.
     * Package-visible so a test can grow the model without a file chooser.
     *
     * @param path the absolute file path
     */
    void addFile(String path) {
        if (path != null && !path.isBlank() && library.add(PhotoItem.of(path.trim()))) {
            afterEdit("Added " + PhotoItem.deriveTitle(path) + ".");
        }
    }

    private void afterEdit(String status) {
        refreshTagFilter();
        refreshList();
        persist();
        setStatus(status);
    }

    private void applyTags() {
        PhotoItem item = gallery.getSelectedValue();
        if (item == null) {
            setStatus("Select a photo to edit its tags.");
            return;
        }
        Set<String> tags = new LinkedHashSet<>();
        for (String t : tagEditor.getText().split(",")) {
            if (!t.isBlank()) {
                tags.add(t.trim());
            }
        }
        item.setTags(tags);
        refreshTagFilter();
        refreshList();
        persist();
        setStatus("Tags for " + item.getTitle() + ": " + item.getTags());
    }

    private void applyRating() {
        PhotoItem item = gallery.getSelectedValue();
        if (item == null) {
            setStatus("Select a photo to rate it.");
            return;
        }
        item.setRating((Integer) ratingEditor.getValue());
        refreshList();
        persist();
        setStatus(item.getTitle() + " rated " + item.getRating() + " star(s).");
    }

    // ------------------------------------------------------------------
    // Filtering, selection and preview
    // ------------------------------------------------------------------

    private void refreshTagFilter() {
        Object previous = tagFilter.getSelectedItem();
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        model.addElement("All tags");
        for (String tag : library.allTags()) {
            model.addElement(tag);
        }
        tagFilter.setModel(model);
        if (previous != null) {
            model.setSelectedItem(previous);
        }
    }

    private void refreshList() {
        String tag = tagFilter.getSelectedItem() == null
                ? "" : tagFilter.getSelectedItem().toString();
        if ("All tags".equals(tag)) {
            tag = "";
        }
        List<PhotoItem> matches =
                library.filter(tag, keywordField.getText(), (Integer) ratingFilter.getValue());
        listModel.clear();
        for (PhotoItem item : matches) {
            listModel.addElement(item);
        }
    }

    private void showSelected() {
        PhotoItem item = gallery.getSelectedValue();
        if (item == null) {
            preview.setIcon(null);
            preview.setText("No photo selected");
            return;
        }
        tagEditor.setText(String.join(", ", item.getTags()));
        ratingEditor.setValue(item.getRating());
        ImageIcon full = loadPreview(item.getPath());
        if (full == null) {
            preview.setIcon(null);
            preview.setText("<html><center>Cannot preview<br>"
                    + escape(item.getTitle()) + "</center></html>");
        } else {
            preview.setText(item.getTitle() + "  " + stars(item.getRating()));
            preview.setIcon(full);
        }
    }

    private void step(int delta) {
        int n = listModel.size();
        if (n == 0) {
            return;
        }
        int i = gallery.getSelectedIndex();
        int next = (i < 0) ? 0 : Math.floorMod(i + delta, n);
        gallery.setSelectedIndex(next);
        gallery.ensureIndexIsVisible(next);
    }

    // ------------------------------------------------------------------
    // Image loading (lazy, guarded)
    // ------------------------------------------------------------------

    private ImageIcon loadThumb(String path) {
        ImageIcon cached = thumbCache.get(path);
        if (cached != null) {
            return cached;
        }
        BufferedImage img = read(path);
        if (img == null) {
            return null;
        }
        ImageIcon icon = new ImageIcon(scale(img, THUMB_PX, THUMB_PX));
        thumbCache.put(path, icon);
        return icon;
    }

    private ImageIcon loadPreview(String path) {
        BufferedImage img = read(path);
        return (img == null) ? null : new ImageIcon(scale(img, PREVIEW_W, PREVIEW_H));
    }

    private static BufferedImage read(String path) {
        try {
            return ImageIO.read(new File(path));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Scales {@code src} to fit within the box, preserving aspect ratio. */
    static Image scale(BufferedImage src, int maxW, int maxH) {
        int w = src.getWidth();
        int h = src.getHeight();
        double ratio = Math.min((double) maxW / w, (double) maxH / h);
        int nw = Math.max(1, (int) Math.round(w * ratio));
        int nh = Math.max(1, (int) Math.round(h * ratio));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            return false;
        }
        return IMAGE_EXTENSIONS.contains(lower.substring(dot + 1));
    }

    private static String stars(int rating) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PhotoItem.MAX_RATING; i++) {
            sb.append(i < rating ? '\u2605' : '\u2606');
        }
        return sb.toString();
    }

    private static String escape(String text) {
        return (text == null) ? "" : text.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private void persist() {
        store.saveLibrary(library.all());
    }

    private void setStatus(String text) {
        final String message = text;
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

    /** The number of gallery rows currently shown (after filtering). */
    int visibleCount() {
        return listModel.size();
    }

    /** The total number of photos in the library (before filtering). */
    int librarySize() {
        return library.size();
    }

    /** An unmodifiable snapshot of the whole library. */
    List<PhotoItem> library() {
        return library.all();
    }

    /** The underlying model (package-visible for tests). */
    PhotoLibrary model() {
        return library;
    }

    /** The currently selected photo, or null. */
    PhotoItem currentPhoto() {
        return gallery.getSelectedValue();
    }

    /** Selects the gallery row at {@code index} (test hook). */
    void selectIndex(int index) {
        if (index >= 0 && index < listModel.size()) {
            gallery.setSelectedIndex(index);
        }
    }

    /** The current status text. */
    String statusText() {
        return statusLabel.getText();
    }

    /** The tags currently shown in the tag filter (test hook). */
    List<String> tagFilterOptions() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tagFilter.getItemCount(); i++) {
            out.add(tagFilter.getItemAt(i));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Thumbnail cell renderer
    // ------------------------------------------------------------------

    /** A gallery cell showing a lazily-decoded thumbnail above its title. */
    private final class ThumbRenderer extends JPanel
            implements ListCellRenderer<PhotoItem> {

        private static final long serialVersionUID = 1L;
        private final JLabel icon = new JLabel(" ", JLabel.CENTER);
        private final JLabel name = new JLabel(" ", JLabel.CENTER);

        ThumbRenderer() {
            setLayout(new BorderLayout(2, 2));
            setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            icon.setPreferredSize(new Dimension(THUMB_PX, THUMB_PX));
            add(icon, BorderLayout.CENTER);
            add(name, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends PhotoItem> list,
                PhotoItem value, int index, boolean isSelected, boolean cellHasFocus) {
            ImageIcon thumb = (value == null) ? null : loadThumb(value.getPath());
            icon.setIcon(thumb);
            icon.setText((thumb == null && value != null) ? "?" : "");
            name.setText((value == null) ? "" : value.getTitle());
            if (isSelected) {
                setBackground(list.getSelectionBackground());
                name.setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                name.setForeground(list.getForeground());
            }
            setOpaque(true);
            return this;
        }
    }
}
