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
package org.jdesktop.lg3d.apps.about;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;

/**
 * The About window content: the product logo and name, the resolved build
 * version, a short description, a grid of host-runtime facts (Java 3D, Java,
 * platform) and the attribution / licence text, all read from the headless
 * {@link AboutInfo} model.
 *
 * <p>This is a plain Swing {@link JPanel} with a no-argument constructor, so it
 * is hosted two ways: in the 3D desktop the {@link About} wrapper puts it on a
 * {@code SwingNode} inside a {@code Frame3D} via {@code TitledSwingWindow}; in
 * the 2D/Swing desktop {@code Desktop2DAppRegistry} constructs it reflectively
 * and hosts it in an MDI internal frame. It touches no Java 3D, so the 2D path
 * never needs the scene graph.</p>
 *
 * <p>A {@code BorderLayout} root with a vertical {@code BoxLayout} body (the
 * same approach {@code HelpCenterPanel} uses) gives a real size that
 * {@code SwingNode.setHostedSize} supplies offscreen; the body is wrapped in a
 * scroll pane so it degrades gracefully at a smaller hosted size. The logo is
 * loaded from the classpath and simply omitted if absent, so a missing asset can
 * never take down the window that hosts this panel.</p>
 */
public class AboutPanel extends JPanel {

    /** Panel size in native pixels; the wrapper hands these to TitledSwingWindow. */
    public static final int WIDTH_PX = 620;
    public static final int HEIGHT_PX = 640;

    /** Classpath location of the product logo, resolved with the context loader. */
    static final String LOGO_PATH = "resources/images/icon/lg3d-logo.png";

    /** The logo is scaled to at most this height, preserving aspect ratio. */
    private static final int LOGO_MAX_HEIGHT = 120;

    public AboutPanel() {
        super(new BorderLayout());
        setOpaque(true);
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(true);
        body.setBackground(Color.WHITE);
        body.setBorder(BorderFactory.createEmptyBorder(24, 28, 24, 28));

        body.add(buildHeader());
        body.add(Box.createVerticalStrut(12));
        body.add(descriptionBlock());
        body.add(Box.createVerticalStrut(16));
        body.add(new JSeparator(SwingConstants.HORIZONTAL));
        body.add(Box.createVerticalStrut(16));
        body.add(buildFields());
        body.add(Box.createVerticalStrut(16));
        body.add(new JSeparator(SwingConstants.HORIZONTAL));
        body.add(Box.createVerticalStrut(12));
        body.add(creditsBlock());
        body.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(body,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    /**
     * Builds the centred header: the product logo (when present), the product
     * name, the resolved version and the tagline.
     */
    private Component buildHeader() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel logo = buildLogoLabel();
        if (logo != null) {
            JPanel logoRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
            logoRow.setOpaque(false);
            logoRow.add(logo);
            logoRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            header.add(logoRow);
            header.add(Box.createVerticalStrut(12));
        }

        JLabel name = new JLabel(AboutInfo.PRODUCT_NAME, SwingConstants.CENTER);
        name.setFont(name.getFont().deriveFont(Font.BOLD, 26f));
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(name);

        JLabel version = new JLabel("Version " + AboutInfo.getVersion(),
                SwingConstants.CENTER);
        version.setFont(version.getFont().deriveFont(Font.PLAIN, 15f));
        version.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(Box.createVerticalStrut(4));
        header.add(version);

        JLabel tagline = new JLabel(AboutInfo.TAGLINE, SwingConstants.CENTER);
        tagline.setForeground(new Color(0x55, 0x55, 0x55));
        tagline.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(Box.createVerticalStrut(2));
        header.add(tagline);
        return header;
    }

    /**
     * Loads the product logo from the classpath and scales it to fit, or returns
     * {@code null} if the asset is missing or unreadable so the header simply
     * omits it.
     */
    private static JLabel buildLogoLabel() {
        BufferedImage image = loadLogo();
        if (image == null) {
            return null;
        }
        int height = Math.min(LOGO_MAX_HEIGHT, image.getHeight());
        int width = Math.max(1,
                (int) Math.round((double) image.getWidth() * height
                        / image.getHeight()));
        ImageIcon icon = new ImageIcon(
                image.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH));
        return new JLabel(icon, SwingConstants.CENTER);
    }

    /** Reads the logo PNG from the classpath, or null if it cannot be read. */
    private static BufferedImage loadLogo() {
        ClassLoader loader = AboutPanel.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(LOGO_PATH)) {
            if (in == null) {
                return null;
            }
            return ImageIO.read(in);
        } catch (Exception e) {
            return null;
        }
    }

    /** The multi-line product description. */
    private static Component descriptionBlock() {
        return htmlLabel(AboutInfo.DESCRIPTION, null, Component.LEFT_ALIGNMENT);
    }

    /** The system-information grid built from {@link AboutInfo#getFields()}. */
    private Component buildFields() {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 0, 3, 12);
        c.anchor = GridBagConstraints.NORTHWEST;

        int row = 0;
        for (AboutInfo.Field field : AboutInfo.getFields()) {
            c.gridx = 0;
            c.gridy = row;
            c.weightx = 0.0;
            JLabel label = new JLabel(field.getLabel());
            label.setFont(label.getFont().deriveFont(Font.BOLD));
            grid.add(label, c);

            c.gridx = 1;
            c.weightx = 1.0;
            c.fill = GridBagConstraints.HORIZONTAL;
            JLabel value = new JLabel(field.getValue());
            grid.add(value, c);
            c.fill = GridBagConstraints.NONE;
            row++;
        }
        return grid;
    }

    /** The attribution and licence footer. */
    private static Component creditsBlock() {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setOpaque(false);
        box.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel credits = htmlLabel(AboutInfo.CREDITS, null, Component.LEFT_ALIGNMENT);
        JLabel license = htmlLabel(AboutInfo.LICENSE,
                new Color(0x55, 0x55, 0x55), Component.LEFT_ALIGNMENT);
        box.add(credits);
        box.add(Box.createVerticalStrut(8));
        box.add(license);
        return box;
    }

    /**
     * Builds a wrapping HTML {@link JLabel} (HTML gives soft line wrapping that
     * a plain label lacks). {@code color} may be null to keep the default.
     */
    private static JLabel htmlLabel(String text, Color color, float alignment) {
        JLabel label = new JLabel(
                "<html><div style='width:" + (WIDTH_PX - 60) + "px;'>"
                + text + "</div></html>");
        label.setAlignmentX(alignment);
        if (color != null) {
            label.setForeground(color);
        }
        return label;
    }
}
