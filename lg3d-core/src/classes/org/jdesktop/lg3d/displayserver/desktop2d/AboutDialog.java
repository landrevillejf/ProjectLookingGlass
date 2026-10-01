/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.BorderLayout;
import java.awt.Window;
import java.awt.Dialog.ModalityType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;

/**
 * A reusable "About" box for the 2D/Swing desktop's application windows.
 *
 * <p>Every panel application the 2D desktop hosts is a {@link Desktop2DWindow},
 * whose menu bar carries a <em>Help &rarr; About&nbsp;&lt;App&gt;</em> item that
 * opens this dialog. It reads what the shared {@code .lgcfg} start-menu
 * descriptor already supplies - the application's <strong>title</strong> and
 * <strong>description</strong> (via {@link Desktop2DMenuConfig.ItemSpec}) - and
 * always credits the same author, {@value #AUTHOR}, alongside the resolved build
 * version. One helper therefore gives every 2D application a consistent About
 * box with no per-application code.</p>
 *
 * <p>This is the <em>per-application</em> About box; it is distinct from the
 * desktop-wide product About window ({@code org.jdesktop.lg3d.apps.about}),
 * which describes Project Looking Glass itself. The dialog assembly needs a
 * display, so all of the content logic lives in pure, headless-testable static
 * helpers ({@link #displayTitle}, {@link #displayDescription},
 * {@link #resolveVersion}, {@link #buildContent}) - the same model/view split the
 * rest of the desktop uses.</p>
 */
public final class AboutDialog {

    /** The author credited in every 2D application About box. */
    public static final String AUTHOR = "Jean-Francois Landreville";

    /** System property carrying the canonical build version at runtime. */
    public static final String VERSION_PROPERTY = "lg.version";

    /** Shown when no version can be resolved from any source. */
    public static final String UNKNOWN_VERSION = "unknown";

    /** Title used when an application supplies no usable name. */
    static final String DEFAULT_TITLE = "Application";

    /** Description used when an application supplies no usable one. */
    static final String DEFAULT_DESCRIPTION =
            "A Project Looking Glass desktop application.";

    /** Footer tying the application back to the product. */
    static final String PRODUCT_LINE = "Part of Project Looking Glass";

    /** Width the description text wraps to, in pixels. */
    private static final int DESCRIPTION_WIDTH_PX = 240;

    private AboutDialog() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Pure, headless-testable content logic
    // ------------------------------------------------------------------

    /** The trimmed application title, or {@link #DEFAULT_TITLE} if blank. */
    static String displayTitle(String title) {
        return (title == null || title.isBlank()) ? DEFAULT_TITLE : title.trim();
    }

    /** The trimmed description, or {@link #DEFAULT_DESCRIPTION} if blank. */
    static String displayDescription(String description) {
        return (description == null || description.isBlank())
                ? DEFAULT_DESCRIPTION : description.trim();
    }

    /**
     * Pure version-resolution helper: the first non-blank of {@code sysProp}
     * and {@code manifest}, else {@link #UNKNOWN_VERSION}. Split out so the
     * fallback chain is unit-testable without touching global system state.
     */
    static String resolveVersion(String sysProp, String manifest) {
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp.trim();
        }
        if (manifest != null && !manifest.isBlank()) {
            return manifest.trim();
        }
        return UNKNOWN_VERSION;
    }

    /**
     * Resolves the build version from the {@value #VERSION_PROPERTY} system
     * property (set by the Gradle {@code run} task to the canonical project
     * version), falling back to this jar's manifest and then to
     * {@link #UNKNOWN_VERSION}. Never hardcodes a literal, so a version bump
     * cannot miss it.
     */
    public static String getVersion() {
        return resolveVersion(System.getProperty(VERSION_PROPERTY),
                AboutDialog.class.getPackage().getImplementationVersion());
    }

    /** The credit rows shown under the description, in display order. */
    static List<String> creditLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Author: " + AUTHOR);
        lines.add("Version: " + getVersion());
        lines.add(PRODUCT_LINE);
        return Collections.unmodifiableList(lines);
    }

    /**
     * Builds the About box content: the application icon (when present), title,
     * wrapped description, a separator and the credit rows. A plain Swing panel
     * with no top-level window, so it can be constructed and asserted on
     * headless; {@link #show} wraps it in a modal dialog.
     */
    static JPanel buildContent(String title, String description, Icon icon) {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(true);
        content.setBackground(Color.WHITE);
        content.setBorder(BorderFactory.createEmptyBorder(20, 24, 16, 24));

        if (icon != null) {
            JLabel iconLabel = new JLabel(icon, SwingConstants.CENTER);
            iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            content.add(iconLabel);
            content.add(Box.createVerticalStrut(10));
        }

        JLabel titleLabel = new JLabel(displayTitle(title), SwingConstants.CENTER);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 18f));
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(titleLabel);
        content.add(Box.createVerticalStrut(8));

        JLabel descLabel = new JLabel(wrappedHtml(displayDescription(description)),
                SwingConstants.CENTER);
        descLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(descLabel);
        content.add(Box.createVerticalStrut(14));

        JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
        separator.setAlignmentX(Component.CENTER_ALIGNMENT);
        separator.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 2));
        content.add(separator);
        content.add(Box.createVerticalStrut(12));

        for (String line : creditLines()) {
            JLabel label = new JLabel(line, SwingConstants.CENTER);
            label.setAlignmentX(Component.CENTER_ALIGNMENT);
            if (line.startsWith("Author:")) {
                label.setFont(label.getFont().deriveFont(Font.BOLD));
            } else {
                label.setForeground(new Color(0x44, 0x44, 0x44));
            }
            content.add(label);
            content.add(Box.createVerticalStrut(3));
        }
        return content;
    }

    /** Wraps {@code text} in a centred, width-constrained HTML block. */
    private static String wrappedHtml(String text) {
        return "<html><div style='text-align:center; width:"
                + DESCRIPTION_WIDTH_PX + "px'>" + escapeHtml(text)
                + "</div></html>";
    }

    /** Escapes the few characters that would break the HTML label. */
    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    // ------------------------------------------------------------------
    // Dialog assembly (needs a display)
    // ------------------------------------------------------------------

    /**
     * Shows a modal About box for {@code title} over {@code owner}, presenting
     * the application's {@code description}, {@code icon}, the fixed
     * {@value #AUTHOR} author credit and the resolved build version.
     *
     * @param owner       the component the dialog is centred over and made
     *                    modal to (a {@link Desktop2DWindow} resolves to its
     *                    top-level desktop window); may be null
     * @param title       the application name
     * @param description the application description from its descriptor
     * @param icon        the application icon, or null for none
     */
    public static void show(Component owner, String title, String description,
                            Icon icon) {
        Window ownerWindow = (owner instanceof Window)
                ? (Window) owner
                : (owner == null ? null
                        : javax.swing.SwingUtilities.getWindowAncestor(owner));
        final JDialog dialog = new JDialog(ownerWindow,
                "About " + displayTitle(title),
                ModalityType.APPLICATION_MODAL);

        JPanel root = new JPanel(new BorderLayout());
        root.setOpaque(true);
        root.setBackground(Color.WHITE);
        root.add(buildContent(title, description, icon), BorderLayout.CENTER);

        JButton close = new JButton("Close");
        close.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttons.setOpaque(true);
        buttons.setBackground(Color.WHITE);
        buttons.add(close);
        root.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.setResizable(false);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }
}
