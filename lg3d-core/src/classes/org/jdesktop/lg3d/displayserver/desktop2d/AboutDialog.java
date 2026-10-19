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
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import javax.swing.JEditorPane;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import org.jdesktop.lg3d.i18n.Messages;

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
    static final String DEFAULT_TITLE = Messages.tr("lg2d.about.defaultTitle");

    /** Description used when an application supplies no usable one. */
    static final String DEFAULT_DESCRIPTION =
            Messages.tr("lg2d.about.defaultDescription");

    /** Footer tying the application back to the product. */
    static final String PRODUCT_LINE = Messages.tr("lg2d.about.productLine");

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

    /**
     * The version shown in an application's About box: the per-app version
     * when supplied and non-blank, otherwise the desktop version. This is the
     * fallback that keeps old descriptors (without a version field) showing
     * a meaningful value instead of nothing.
     */
    static String displayVersion(String appVersion) {
        if (appVersion != null && !appVersion.isBlank()) {
            return appVersion.trim();
        }
        return getVersion();
    }

    /** The credit rows shown under the description, in display order. */
    static List<String> creditLines() {
        return creditLines(getVersion());
    }

    /**
     * The credit rows for a specific application version. When {@code version}
     * is non-null and non-blank it is shown as the app's own version; otherwise
     * the desktop version is used.
     */
    static List<String> creditLines(String version) {
        List<String> lines = new ArrayList<>();
        lines.add(Messages.tr("lg2d.about.author", AUTHOR));
        lines.add(Messages.tr("lg2d.about.version", displayVersion(version)));
        lines.add(PRODUCT_LINE);
        return Collections.unmodifiableList(lines);
    }

    /**
     * Resolves the per-app changelog from the classpath. The convention is
     * that each application ships a {@code CHANGELOG.md} beside its main panel
     * class: the main class's package directory is scanned for that file name.
     * Returns {@code null} when no changelog can be found, so the caller can
     * hide the "What's New" button.
     */
    static String resolveChangelog(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String mainClass = extractMainClass(command);
        if (mainClass == null || mainClass.isBlank()) {
            return null;
        }
        // org.jdesktop.lg3d.apps.calculator.Calculator
        //   -> org/jdesktop/lg3d/apps/calculator/CHANGELOG.md
        String resourcePath = mainClass.replace('.', '/');
        int lastSlash = resourcePath.lastIndexOf('/');
        if (lastSlash < 0) {
            return null;
        }
        String packagePath = resourcePath.substring(0, lastSlash + 1);
        String changelogResource = packagePath + "CHANGELOG.md";
        ClassLoader cl = AboutDialog.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(changelogResource)) {
            if (in == null) {
                return null;
            }
            return readFully(in);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the main class name from a descriptor command string.
     * Handles both {@code java <class> ...} and {@code swingapp <class> ...}.
     */
    private static String extractMainClass(String command) {
        String trimmed = command.trim();
        if (trimmed.startsWith("java ")) {
            trimmed = trimmed.substring(5).trim();
        } else if (trimmed.startsWith("swingapp ")) {
            trimmed = trimmed.substring(9).trim();
        } else {
            return null;
        }
        int space = trimmed.indexOf(' ');
        return (space < 0) ? trimmed : trimmed.substring(0, space);
    }

    /** Reads an {@link InputStream} fully as UTF-8 text. */
    private static String readFully(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /**
     * Builds the About box content: the application icon (when present), title,
     * wrapped description, a separator and the credit rows. A plain Swing panel
     * with no top-level window, so it can be constructed and asserted on
     * headless; {@link #show} wraps it in a modal dialog.
     */
    static JPanel buildContent(String title, String description, Icon icon) {
        return buildContent(title, description, icon, null);
    }

    /**
     * Builds the About box content with a per-app version. When {@code appVersion}
     * is non-null and non-blank it replaces the desktop version in the credit
     * lines; otherwise the desktop version is shown (backward compatibility for
     * descriptors that do not carry a version field).
     */
    static JPanel buildContent(String title, String description, Icon icon,
                               String appVersion) {
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

        for (String line : creditLines(appVersion)) {
            JLabel label = new JLabel(line, SwingConstants.CENTER);
            label.setAlignmentX(Component.CENTER_ALIGNMENT);
            if (line.startsWith(Messages.tr("lg2d.about.authorLabel"))) {
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
        show(owner, title, description, icon, null, null);
    }

    /**
     * Shows a modal About box with per-app version and an optional "What's New"
     * button when a changelog is found on the classpath.
     *
     * @param owner       the component the dialog is centred over
     * @param title       the application name
     * @param description the application description
     * @param icon        the application icon, or null
     * @param appVersion  the application's own version, or null for desktop version
     * @param command     the descriptor command (used to resolve the changelog)
     */
    public static void show(Component owner, String title, String description,
                            Icon icon, String appVersion, String command) {
        Window ownerWindow = (owner instanceof Window)
                ? (Window) owner
                : (owner == null ? null
                        : javax.swing.SwingUtilities.getWindowAncestor(owner));
        final JDialog dialog = new JDialog(ownerWindow,
                Messages.tr("lg2d.about.title", displayTitle(title)),
                ModalityType.APPLICATION_MODAL);

        JPanel root = new JPanel(new BorderLayout());
        root.setOpaque(true);
        root.setBackground(Color.WHITE);
        root.add(buildContent(title, description, icon, appVersion),
                BorderLayout.CENTER);

        String changelog = resolveChangelog(command);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttons.setOpaque(true);
        buttons.setBackground(Color.WHITE);
        if (changelog != null) {
            JButton whatsNew = new JButton(
                    Messages.tr("lg2d.about.whatsNew"));
            whatsNew.addActionListener(e ->
                    showChangelog(dialog, displayTitle(title), changelog));
            buttons.add(whatsNew);
        }
        JButton close = new JButton(Messages.tr("lg2d.about.close"));
        close.addActionListener(e -> dialog.dispose());
        buttons.add(close);
        root.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.setResizable(false);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /**
     * Opens a scrollable dialog showing the application's changelog rendered
     * as HTML, the same way the Help Center renders its <em>What's New</em>
     * topic. The markdown is converted to HTML with the Keep-a-Changelog
     * structure (version headings, subsection groups, bullet entries with
     * inline {@code **bold**}, {@code `code`} and {@code [text](url)} spans).
     */
    private static void showChangelog(JDialog owner, String appName,
                                      String changelogText) {
        JDialog changelogDialog = new JDialog(owner,
                Messages.tr("lg2d.about.whatsNewTitle", appName),
                ModalityType.APPLICATION_MODAL);

        String html = renderMarkdownToHtml(changelogText);
        JEditorPane editorPane = new JEditorPane();
        editorPane.setContentType("text/html");
        HTMLEditorKit kit = new HTMLEditorKit();
        editorPane.setEditorKit(kit);
        StyleSheet ss = kit.getStyleSheet();
        ss.addRule("body { font-family: sans-serif; font-size: 13px; "
                + "margin: 12px 16px; background: #ffffff; color: #222; }");
        ss.addRule("h1 { font-size: 18px; margin-top: 0; }");
        ss.addRule("h2 { font-size: 15px; margin-top: 1.2em; "
                + "border-bottom: 1px solid #ccc; padding-bottom: 4px; }");
        ss.addRule("h3 { font-size: 13px; margin-top: 1em; "
                + "font-weight: bold; }");
        ss.addRule("code { background: #f0f0f0; padding: 1px 4px; "
                + "font-family: monospace; font-size: 12px; }");
        ss.addRule("ul { padding-left: 1.4em; }");
        ss.addRule("li { margin-bottom: 0.5em; }");
        ss.addRule("a { color: #4a51a8; }");
        editorPane.setText(html);
        editorPane.setEditable(false);
        editorPane.setCaretPosition(0);

        JScrollPane scroll = new JScrollPane(editorPane,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new java.awt.Dimension(560, 480));
        changelogDialog.setContentPane(scroll);
        changelogDialog.pack();
        changelogDialog.setLocationRelativeTo(owner);
        changelogDialog.setVisible(true);
    }

    // ------------------------------------------------------------------
    // Markdown-to-HTML rendering (pure, testable)
    // ------------------------------------------------------------------

    /**
     * Converts a Keep-a-Changelog markdown document to an HTML fragment
     * suitable for display in a {@link JEditorPane}. Handles the subset of
     * markdown the changelogs use: {@code ## [version]} headings, {@code ### subsection}
     * headings, top-level {@code - } bullets with continuation lines, and the
     * inline spans {@code **bold**}, {@code `code`} and {@code [text](url)}.
     *
     * <p>The conversion is intentionally limited - it is not a general-purpose
     * markdown parser. It covers the vocabulary the changelogs are written in,
     * and leaves anything it does not recognise as literal text rather than
     * mangling it.</p>
     */
    static String renderMarkdownToHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "<html><body><p>(empty)</p></body></html>";
        }
        String[] lines = markdown.split("\n");
        StringBuilder out = new StringBuilder();
        out.append("<html>\n<body>\n");

        boolean inList = false;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            // Blank line: close any open list
            if (trimmed.isEmpty()) {
                if (inList) {
                    out.append("</ul>\n");
                    inList = false;
                }
                i++;
                continue;
            }

            // # Heading -> <h1>
            if (trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
                if (inList) { out.append("</ul>\n"); inList = false; }
                out.append("<h1>").append(inlineMarkdown(trimmed.substring(2)))
                        .append("</h1>\n");
                i++;
                continue;
            }

            // ## [version] or ## version -> <h2>
            if (trimmed.startsWith("## ")) {
                if (inList) { out.append("</ul>\n"); inList = false; }
                String heading = trimmed.substring(3).trim();
                // Strip surrounding brackets from [version]
                heading = heading.replace("[", "").replace("]", "");
                out.append("<h2>").append(inlineMarkdown(heading))
                        .append("</h2>\n");
                i++;
                continue;
            }

            // ### subsection -> <h3>
            if (trimmed.startsWith("### ")) {
                if (inList) { out.append("</ul>\n"); inList = false; }
                out.append("<h3>").append(inlineMarkdown(trimmed.substring(4)))
                        .append("</h3>\n");
                i++;
                continue;
            }

            // Top-level bullet: "- text"
            if (trimmed.startsWith("- ")) {
                if (!inList) {
                    out.append("<ul>\n");
                    inList = true;
                }
                // Collect continuation lines (indented, non-blank, non-bullet)
                StringBuilder bulletBody = new StringBuilder(trimmed.substring(2));
                int j = i + 1;
                while (j < lines.length) {
                    String next = lines[j];
                    String nextTrimmed = next.trim();
                    if (nextTrimmed.isEmpty()) break;
                    if (nextTrimmed.startsWith("### ")) break;
                    if (nextTrimmed.startsWith("## ")) break;
                    if (next.startsWith("- ")) break;
                    if (!next.startsWith("  ") && !next.startsWith("\t")) break;
                    if (nextTrimmed.startsWith("- ")) break;
                    bulletBody.append(' ').append(nextTrimmed);
                    j++;
                }
                out.append("<li>").append(inlineMarkdown(bulletBody.toString()))
                        .append("</li>\n");
                i = j;
                continue;
            }

            // Anything else: a plain paragraph
            if (inList) { out.append("</ul>\n"); inList = false; }
            out.append("<p>").append(inlineMarkdown(trimmed)).append("</p>\n");
            i++;
        }
        if (inList) {
            out.append("</ul>\n");
        }
        out.append("</body>\n</html>");
        return out.toString();
    }

    /**
     * Converts inline markdown spans ({@code **bold**}, {@code `code`},
     * {@code [text](url)}) to their HTML equivalents. Characters that would
     * break the HTML ({@code &}, {@code <}, {@code >}) are escaped first, so
     * a changelog that writes {@code &lt;} as a markdown entity does not
     * become a tag.
     */
    static String inlineMarkdown(String text) {
        // Escape HTML first
        String s = text.replace("&", "&amp;")
                       .replace("<", "&lt;")
                       .replace(">", "&gt;");
        StringBuilder out = new StringBuilder();
        int pos = 0;
        while (pos < s.length()) {
            // Find the earliest inline marker
            int boldAt = s.indexOf("**", pos);
            int codeAt = s.indexOf('`', pos);
            int linkAt = s.indexOf('[', pos);

            int earliest = -1;
            if (boldAt >= 0) earliest = boldAt;
            if (codeAt >= 0 && (earliest < 0 || codeAt < earliest)) earliest = codeAt;
            if (linkAt >= 0 && (earliest < 0 || linkAt < earliest)) earliest = linkAt;

            if (earliest < 0) {
                out.append(s, pos, s.length());
                break;
            }

            // Emit text before the marker
            out.append(s, pos, earliest);

            if (s.startsWith("**", earliest)) {
                // Bold span
                int tail = earliest + 2;
                int close = s.indexOf("**", tail);
                if (close < 0) {
                    // No closing **: emit literally
                    out.append(s, earliest, s.length());
                    break;
                }
                out.append("<strong>");
                out.append(s, tail, close);
                out.append("</strong>");
                pos = close + 2;
            } else if (s.charAt(earliest) == '`') {
                // Code span: find the matching backtick
                int close = s.indexOf('`', earliest + 1);
                if (close < 0) {
                    out.append('`');
                    pos = earliest + 1;
                } else {
                    out.append("<code>");
                    out.append(s, earliest + 1, close);
                    out.append("</code>");
                    pos = close + 1;
                }
            } else {
                // Possible link: [text](url)
                int tail = earliest + 1;
                int parenAt = s.indexOf("](", tail);
                if (parenAt < 0) {
                    out.append('[');
                    pos = tail;
                } else {
                    String label = s.substring(tail, parenAt);
                    int urlStart = parenAt + 2;
                    int urlEnd = s.indexOf(')', urlStart);
                    if (urlEnd < 0) {
                        out.append('[').append(label).append("](");
                        pos = urlStart;
                    } else {
                        String url = s.substring(urlStart, urlEnd);
                        out.append("<a href=\"").append(url).append("\">")
                           .append(label).append("</a>");
                        pos = urlEnd + 1;
                    }
                }
            }
        }
        return out.toString();
    }
}
