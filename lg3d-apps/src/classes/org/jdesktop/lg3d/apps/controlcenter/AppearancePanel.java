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
package org.jdesktop.lg3d.apps.controlcenter;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D;
import org.jdesktop.lg3d.scenemanager.utils.background.SimpleImageBackground;
import org.jdesktop.lg3d.scenemanager.utils.event.BackgroundChangeRequestEvent;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.jdesktop.lg3d.wg.event.LgEventConnector;

/**
 * Appearance / wallpaper panel: enumerates the wallpapers bundled under the
 * runtime {@code resources/images/background} tree (plus a custom image chosen
 * from disk), previews them, and applies the selection by posting a
 * {@link BackgroundChangeRequestEvent} with a {@link SimpleImageBackground} -
 * the same mechanism the taskbar's theme icons use, so the live desktop
 * background changes immediately.
 */
public class AppearancePanel implements ControlPanel {

    private static final String BG_DIR = "resources/images/background";

    /** Used when the classpath directory cannot be listed (e.g. jar-only). */
    private static final List<String> FALLBACK = List.of(
            "DreamLakeReflections.jpg",
            "GrandCanyon-0.jpg",
            "Leaves_and_Sky-0.jpg",
            "Stanford-0.jpg");

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final DefaultListModel<String> names = new DefaultListModel<>();
    private final JList<String> nameList = new JList<>(names);
    private final JLabel preview = new JLabel(" ", JLabel.CENTER);
    private final JLabel statusLabel = new JLabel(" ");

    private URL customUrl;
    private String customName;

    /** Slideshow on/off selector ("Off"/"On"). */
    private final DefaultListModel<String> onOffNames = new DefaultListModel<>();
    private final JList<String> onOffList = new JList<>(onOffNames);
    /** Slideshow interval selector. */
    private final DefaultListModel<String> intervalNames = new DefaultListModel<>();
    private final JList<String> intervalList = new JList<>(intervalNames);
    private final JLabel folderLabel = new JLabel(" ");
    private final List<JComponent> slideshowControls = new ArrayList<>();

    /** Window-glass style selector ("Glassy"/"Frosted"). */
    private final DefaultListModel<String> glassNames = new DefaultListModel<>();
    private final JList<String> glassList = new JList<>(glassNames);

    /** The slideshow source folder; empty means the bundled wallpapers. */
    private String slideshowFolder = "";

    /** Interval choices offered by the slideshow selector (seconds / labels). */
    private static final int[] INTERVAL_SECONDS = {10, 30, 60, 300, 900, 1800, 3600};
    private static final String[] INTERVAL_LABELS = {
            "10 seconds", "30 seconds", "1 minute", "5 minutes",
            "15 minutes", "30 minutes", "1 hour"};

    public AppearancePanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        nameList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nameList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updatePreview();
            }
        });

        JButton custom = new JButton("Custom Image...");
        custom.addActionListener(e -> chooseCustom());
        JButton apply = new JButton("Apply");
        apply.addActionListener(e -> apply());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(custom);
        buttons.add(apply);

        JPanel right = new JPanel(new BorderLayout(6, 6));
        preview.setVerticalAlignment(JLabel.CENTER);
        preview.setHorizontalAlignment(JLabel.CENTER);
        preview.setBorder(BorderFactory.createLineBorder(new java.awt.Color(190, 196, 206)));
        right.add(preview, BorderLayout.CENTER);
        right.add(buttons, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(nameList), right);
        split.setDividerLocation(200);
        split.setResizeWeight(0.35);
        split.setBorder(BorderFactory.createEmptyBorder());

        root.add(split, BorderLayout.CENTER);
        root.add(statusLabel, BorderLayout.SOUTH);
        JPanel north = new JPanel(new GridLayout(0, 1, 6, 6));
        north.add(buildGlassPanel());
        north.add(buildSlideshowPanel());
        root.add(north, BorderLayout.NORTH);

        reload();
    }

    @Override
    public String displayName() {
        return "Appearance";
    }

    @Override
    public javax.swing.Icon icon() {
        return null;
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void onShow() {
        reload();
    }

    // ------------------------------------------------------------------

    private void reload() {
        names.clear();
        for (String n : enumerate()) {
            names.addElement(n);
        }
        if (customName != null) {
            names.addElement(customName);
        }
        if (!names.isEmpty()) {
            nameList.setSelectedIndex(0);
        }
        loadGlassState();
        loadSlideshowState();
    }

    /**
     * Builds the window-glass style section: a two-entry {@link JList}
     * (Glassy / Frosted) plus an apply button. A list selector (never a combo
     * box or radio buttons) keeps the panel working when it is hosted offscreen
     * in a {@code SwingNode}. The choice is persisted on {@link DesktopConfig}
     * and read when a window decoration is built, so it applies to newly opened
     * windows on the 3D desktop.
     */
    private JComponent buildGlassPanel() {
        glassNames.addElement("Glassy (classic)");
        glassNames.addElement("Frosted (GPU)");
        glassList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        glassList.setVisibleRowCount(2);
        JScrollPane glassScroll = new JScrollPane(glassList);
        glassScroll.setPreferredSize(new Dimension(150, 58));

        JButton applyGlass = new JButton("Apply");
        applyGlass.addActionListener(e -> applyGlass());

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(new JLabel("Window glass:"));
        row.add(glassScroll);
        row.add(applyGlass);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(row, BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createTitledBorder("Window Glass"));
        return panel;
    }

    /** Reflects the persisted window-glass style in the selector. */
    private void loadGlassState() {
        glassList.setSelectedIndex(DesktopConfig.get().isFrostedGlass() ? 1 : 0);
    }

    private void applyGlass() {
        boolean frosted = glassList.getSelectedIndex() == 1;
        DesktopConfig cfg = DesktopConfig.get();
        cfg.setFrostedGlass(frosted);
        cfg.save();
        statusLabel.setText((frosted
                ? "Window glass: Frosted (GPU)"
                : "Window glass: Glassy (classic)")
                + " - applies to newly opened windows");
    }

    /**
     * Builds the wallpaper-slideshow section: an on/off {@link JList}, an
     * interval {@link JList}, a folder chooser and an apply button. The list
     * selectors (never a combo box or radio buttons) keep the panel working when
     * it is hosted offscreen in a {@code SwingNode}. On the 3D desktop, where no
     * scene-manager slideshow exists, the controls are left inert.
     */
    private JComponent buildSlideshowPanel() {
        onOffNames.addElement("Off");
        onOffNames.addElement("On");
        for (String label : INTERVAL_LABELS) {
            intervalNames.addElement(label);
        }
        onOffList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        intervalList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        onOffList.setVisibleRowCount(2);
        intervalList.setVisibleRowCount(3);

        JScrollPane onOffScroll = new JScrollPane(onOffList);
        onOffScroll.setPreferredSize(new Dimension(72, 58));
        JScrollPane intervalScroll = new JScrollPane(intervalList);
        intervalScroll.setPreferredSize(new Dimension(120, 58));

        JButton chooseFolder = new JButton("Choose Folder...");
        chooseFolder.addActionListener(e -> chooseSlideshowFolder());
        JButton bundled = new JButton("Use Bundled");
        bundled.addActionListener(e -> useBundledWallpapers());
        JButton applySlideshow = new JButton("Apply Slideshow");
        applySlideshow.addActionListener(e -> applySlideshow());

        JPanel selectors = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        selectors.add(new JLabel("Slideshow:"));
        selectors.add(onOffScroll);
        selectors.add(new JLabel("Change every:"));
        selectors.add(intervalScroll);

        JPanel folderRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        folderRow.add(new JLabel("Folder:"));
        folderRow.add(folderLabel);
        folderRow.add(chooseFolder);
        folderRow.add(bundled);
        folderRow.add(applySlideshow);

        JPanel panel = new JPanel(new GridLayout(2, 1, 6, 4));
        panel.add(selectors);
        panel.add(folderRow);
        panel.setBorder(BorderFactory.createTitledBorder("Wallpaper Slideshow"));

        slideshowControls.add(onOffList);
        slideshowControls.add(intervalList);
        slideshowControls.add(chooseFolder);
        slideshowControls.add(bundled);
        slideshowControls.add(applySlideshow);
        if (!Boolean.getBoolean(Desktop2D.MODE_PROPERTY)) {
            setSlideshowControlsEnabled(false);
            folderLabel.setText("(2D/Swing desktop only)");
        }
        return panel;
    }

    /** Reflects the persisted slideshow state in the selectors. */
    private void loadSlideshowState() {
        DesktopConfig cfg = DesktopConfig.get();
        onOffList.setSelectedIndex(cfg.isSlideshowEnabled() ? 1 : 0);
        intervalList.setSelectedIndex(intervalIndex(cfg.getSlideshowIntervalSec()));
        slideshowFolder = cfg.getSlideshowFolder();
        if (Boolean.getBoolean(Desktop2D.MODE_PROPERTY)) {
            folderLabel.setText(folderText(slideshowFolder));
        }
    }

    private void chooseSlideshowFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a wallpaper folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File dir = chooser.getSelectedFile();
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        slideshowFolder = dir.getAbsolutePath();
        folderLabel.setText(folderText(slideshowFolder));
    }

    private void useBundledWallpapers() {
        slideshowFolder = "";
        folderLabel.setText(folderText(slideshowFolder));
    }

    private void applySlideshow() {
        if (!Boolean.getBoolean(Desktop2D.MODE_PROPERTY)) {
            warn("The wallpaper slideshow runs on the 2D/Swing desktop only.");
            return;
        }
        boolean on = onOffList.getSelectedIndex() == 1;
        int seconds = selectedIntervalSeconds();
        // Persist the folder and interval first, then the enable flag, so the
        // final call restarts the running slideshow with all three in place.
        Desktop2D.setSlideshowFolder(slideshowFolder);
        Desktop2D.setSlideshowIntervalSec(seconds);
        Desktop2D.setSlideshowEnabled(on);
        statusLabel.setText(on
                ? "Slideshow on: changing every " + intervalLabel(seconds)
                        + " from " + folderText(slideshowFolder)
                : "Slideshow off");
    }

    private int selectedIntervalSeconds() {
        int i = intervalList.getSelectedIndex();
        return (i >= 0 && i < INTERVAL_SECONDS.length)
                ? INTERVAL_SECONDS[i] : DesktopConfig.DEFAULT_SLIDESHOW_INTERVAL_SEC;
    }

    private void setSlideshowControlsEnabled(boolean enabled) {
        for (JComponent c : slideshowControls) {
            c.setEnabled(enabled);
        }
    }

    /** The selector row for a stored interval, falling back to the default. */
    private static int intervalIndex(int seconds) {
        for (int i = 0; i < INTERVAL_SECONDS.length; i++) {
            if (INTERVAL_SECONDS[i] == seconds) {
                return i;
            }
        }
        for (int i = 0; i < INTERVAL_SECONDS.length; i++) {
            if (INTERVAL_SECONDS[i] == DesktopConfig.DEFAULT_SLIDESHOW_INTERVAL_SEC) {
                return i;
            }
        }
        return 0;
    }

    private static String intervalLabel(int seconds) {
        for (int i = 0; i < INTERVAL_SECONDS.length; i++) {
            if (INTERVAL_SECONDS[i] == seconds) {
                return INTERVAL_LABELS[i];
            }
        }
        return seconds + " seconds";
    }

    private static String folderText(String folder) {
        return (folder == null || folder.isBlank()) ? "Bundled wallpapers" : folder;
    }

    private List<String> enumerate() {
        List<String> result = new ArrayList<>();
        URL dirUrl = getClass().getClassLoader().getResource(BG_DIR);
        if (dirUrl != null && "file".equals(dirUrl.getProtocol())) {
            try {
                File dir = new File(dirUrl.toURI());
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String lower = f.getName().toLowerCase();
                        if (f.isFile() && (lower.endsWith(".jpg")
                                || lower.endsWith(".jpeg") || lower.endsWith(".png"))) {
                            result.add(f.getName());
                        }
                    }
                }
            } catch (Exception e) {
                result.clear();
            }
        }
        Collections.sort(result);
        if (result.isEmpty()) {
            result.addAll(FALLBACK);
        }
        return result;
    }

    private void updatePreview() {
        URL url = resolveSelected();
        if (url == null) {
            preview.setText(" ");
            preview.setIcon(null);
            return;
        }
        try (InputStream in = url.openStream()) {
            java.awt.image.BufferedImage img = ImageIO.read(in);
            if (img != null) {
                preview.setIcon(new ImageIcon(img.getScaledInstance(
                        260, 160, Image.SCALE_SMOOTH)));
                preview.setText(null);
                return;
            }
        } catch (Exception e) {
            // fall through to the text placeholder
        }
        preview.setIcon(null);
        preview.setText("(preview unavailable)");
    }

    private URL resolveSelected() {
        String sel = nameList.getSelectedValue();
        if (sel == null) {
            return null;
        }
        if (sel.equals(customName)) {
            return customUrl;
        }
        return getClass().getClassLoader().getResource(BG_DIR + "/" + sel);
    }

    private void chooseCustom() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a wallpaper image");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Images (jpg, png)", "jpg", "jpeg", "png"));
        if (chooser.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = chooser.getSelectedFile();
        if (f == null || !f.isFile()) {
            return;
        }
        try {
            customUrl = f.toURI().toURL();
            customName = f.getName() + "  (custom)";
            if (!names.contains(customName)) {
                names.addElement(customName);
            }
            nameList.setSelectedValue(customName, true);
        } catch (Exception e) {
            warn("Could not use that file:\n" + e.getMessage());
        }
    }

    private void apply() {
        URL url = resolveSelected();
        if (url == null) {
            warn("Select a wallpaper first.");
            return;
        }
        String applied = "Background applied: " + nameList.getSelectedValue();
        // On the conventional 2D desktop there is no scene manager listening
        // for BackgroundChangeRequestEvent, so hand the image straight to the
        // running shell. Guarded on the mode property so the Java 3D classes
        // below are never touched on a JVM where they are absent.
        if (Boolean.getBoolean(Desktop2D.MODE_PROPERTY)) {
            Desktop2D.setWallpaper(url);
            statusLabel.setText(applied);
            return;
        }
        try {
            SimpleImageBackground background = new SimpleImageBackground(url);
            LgEventConnector.getLgEventConnector().postEvent(
                    new BackgroundChangeRequestEvent(background), null);
            statusLabel.setText(applied);
        } catch (RuntimeException e) {
            warn("Could not apply the background:\n" + e.getMessage());
        }
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(root, message, "Appearance",
                JOptionPane.WARNING_MESSAGE);
    }
}
