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
package org.jdesktop.lg3d.apps.controlcenter;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
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
