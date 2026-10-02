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
package org.jdesktop.lg3d.apps.webbrowser;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * The 3D desktop's face of the Web Browser: a pure-Swing static preview. It
 * shows the product identity, a feature blurb and a rendered mock of the browser
 * chrome, plus an "Open Full Browser" button.
 *
 * <p><strong>This class imports no {@code javafx.*}.</strong> The 3D desktop
 * hosts Swing on a {@code SwingNode}, whose {@code captureNow} paints the panel
 * offscreen into a {@code BufferedImage}; a JavaFX {@code WebView} is a
 * heavyweight native peer that yields a blank quad when painted offscreen, so it
 * cannot ride that capture path. Rather than initialise JavaFX (and its
 * WebKit/OpenGL pipeline) inside the Java&nbsp;3D JVM &mdash; where the two GL
 * toolkits clash &mdash; the preview stays pure Swing and launches the real
 * browser in a <em>separate child-process JVM</em>.</p>
 *
 * <p>The button spawns
 * {@code "<java.home>/bin/java" -cp "<java.class.path>"
 * org.jdesktop.lg3d.apps.webbrowser.WebBrowserApp} with the display propagated,
 * so the interactive {@link BrowserPanel} runs in its own process and never
 * touches the Java&nbsp;3D GL context. If the spawn fails (an unusual sandbox),
 * it degrades to launching {@link WebBrowserApp#main} on a thread in this JVM.</p>
 */
public class BrowserPreviewPanel extends JPanel {

    private static final Logger LOG = Logger.getLogger(BrowserPreviewPanel.class.getName());

    /** Panel size in native pixels; the 3D window sizes itself to this. */
    public static final int WIDTH_PX = 720;
    public static final int HEIGHT_PX = 560;

    /** The child-process entry point. */
    static final String APP_MAIN = "org.jdesktop.lg3d.apps.webbrowser.WebBrowserApp";

    private static final Color ACCENT = new Color(0x2D, 0x6C, 0xDF);
    private static final Color CARD = new Color(0xF4, 0xF6, 0xFA);
    private static final Color INK = new Color(0x1E, 0x24, 0x30);
    private static final Color MUTED = new Color(0x5B, 0x64, 0x72);

    private final JLabel statusLabel = new JLabel(" ");

    /** Builds the static preview. */
    public BrowserPreviewPanel() {
        super(new BorderLayout(0, 12));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createEmptyBorder(20, 24, 16, 24));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildMock(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Web Browser");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26f));
        title.setForeground(INK);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel(
                "<html><div style='width:600px'>A full-featured browser powered by JavaFX "
                + "WebKit: tabbed browsing, a smart address bar with search, bookmarks, "
                + "history, find-in-page, zoom, view-source, JavaScript, persistent cookies, "
                + "private browsing and best-effort downloads.</div></html>");
        subtitle.setForeground(MUTED);
        subtitle.setFont(subtitle.getFont().deriveFont(13f));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        header.add(title);
        header.add(Box.createVerticalStrut(6));
        header.add(subtitle);
        header.add(Box.createVerticalStrut(10));
        return header;
    }

    /** A lightweight, non-interactive mock of the browser chrome. */
    private JPanel buildMock() {
        JPanel mock = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                paintMock((Graphics2D) g);
            }
        };
        mock.setOpaque(true);
        mock.setBackground(CARD);
        mock.setPreferredSize(new Dimension(WIDTH_PX - 48, 300));
        mock.setBorder(BorderFactory.createLineBorder(new Color(0xD8, 0xDD, 0xE6)));
        return mock;
    }

    private void paintMock(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        // Toolbar strip.
        g.setColor(new Color(0xE6, 0xEA, 0xF1));
        g.fillRect(0, 0, w, 46);
        // Nav buttons.
        g.setColor(new Color(0xC3, 0xCB, 0xD8));
        for (int i = 0; i < 4; i++) {
            g.fillRoundRect(12 + i * 34, 13, 26, 20, 8, 8);
        }
        // Address bar.
        g.setColor(Color.WHITE);
        g.fillRoundRect(156, 11, Math.max(120, w - 200), 24, 12, 12);
        g.setColor(new Color(0x9A, 0xA3, 0xB2));
        g.drawString("https://duckduckgo.com", 170, 28);
        // Page body placeholder lines.
        g.setColor(new Color(0xDD, 0xE2, 0xEB));
        int y = 78;
        int[] widths = {w - 60, w - 120, w - 80, w - 200, w - 140};
        for (int width : widths) {
            g.fillRoundRect(30, y, Math.max(60, width - 30), 12, 6, 6);
            y += 26;
        }
        // A hero block.
        g.setColor(new Color(0xD3, 0xE0, 0xFB));
        g.fillRoundRect(30, y + 6, Math.max(120, (w - 60) / 2), 90, 10, 10);
        g.setColor(ACCENT);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 15f));
        g.drawString("WebKit rendering", 48, y + 40);
        g.setColor(MUTED);
        g.setFont(g.getFont().deriveFont(12f));
        g.drawString("Live page in the full browser window", 48, y + 62);
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.setOpaque(false);

        JLabel note = new JLabel(
                "<html><div style='width:420px'><i>The 3D desktop shows this preview. "
                + "The interactive browser opens in its own window (a separate process) "
                + "so JavaFX never shares the Java&nbsp;3D graphics context.</i></div></html>");
        note.setForeground(MUTED);
        note.setFont(note.getFont().deriveFont(12f));

        JButton open = new JButton("Open Full Browser");
        open.setFont(open.getFont().deriveFont(Font.BOLD, 14f));
        open.setBackground(ACCENT);
        open.setForeground(Color.WHITE);
        open.setFocusPainted(false);
        open.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        open.setBorder(BorderFactory.createEmptyBorder(10, 22, 10, 22));
        open.addActionListener(e -> openFullBrowser());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(open);

        statusLabel.setForeground(MUTED);
        statusLabel.setHorizontalAlignment(SwingConstants.LEFT);

        footer.add(note, BorderLayout.CENTER);
        footer.add(right, BorderLayout.EAST);
        footer.add(statusLabel, BorderLayout.SOUTH);
        return footer;
    }

    // ------------------------------------------------------------------
    // Launch
    // ------------------------------------------------------------------

    /**
     * Launches the full browser. Prefers a detached child-process JVM so JavaFX
     * is isolated from the Java&nbsp;3D GL context; falls back to an in-JVM
     * thread launch if the process cannot be spawned.
     */
    void openFullBrowser() {
        if (spawnChildProcess()) {
            statusLabel.setText("Opening the full browser in a new window...");
        } else {
            statusLabel.setText("Launching in-process (child spawn unavailable)...");
            launchInProcess();
        }
    }

    private boolean spawnChildProcess() {
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-cp");
        command.add(classpath);
        command.add(APP_MAIN);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            String display = System.getProperty("lg.lgserverdisplay");
            if (display == null) {
                display = System.getenv("DISPLAY");
            }
            if (display != null) {
                pb.environment().put("DISPLAY", display);
            }
            Process process = pb.start();
            drain(process);
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.INFO, "Could not spawn the browser child process", e);
            return false;
        }
    }

    /** Consumes the child's output so its buffer never fills and blocks it. */
    private static void drain(Process process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                while (line != null) {
                    LOG.log(Level.FINE, "webbrowser: {0}", line);
                    line = reader.readLine();
                }
            } catch (IOException e) {
                LOG.log(Level.FINE, "Error draining browser output", e);
            }
        }, "webbrowser-child-output");
        thread.setDaemon(true);
        thread.start();
    }

    /** Degraded fallback: run the browser main on a daemon thread in this JVM. */
    private void launchInProcess() {
        Thread thread = new Thread(() -> {
            try {
                WebBrowserApp.main(new String[0]);
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "In-process browser launch failed", t);
            }
        }, "webbrowser-inprocess");
        thread.setDaemon(true);
        thread.start();
    }
}
