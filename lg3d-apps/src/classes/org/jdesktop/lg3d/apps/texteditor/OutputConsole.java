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
package org.jdesktop.lg3d.apps.texteditor;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * The editor's south output console: a read-only, monospaced transcript that
 * receives tool output (compiler errors, program stdout/stderr, stack traces)
 * instead of polluting the document. Extensions reach it through
 * {@link org.jdesktop.lg3d.apps.texteditor.ext.EditorContext#showOutput};
 * the bundled JVM Build Tools actions write every run here, and its
 * {@code Clean Output} action calls {@link #clear()}.
 *
 * <p>Each run is delimited by a {@code ---- title ----} rule followed by the
 * body. The transcript is capped at {@link #MAX_LINES} lines (oldest output is
 * dropped first, with a one-line note), and every append auto-scrolls to the
 * bottom. All methods must be called on the EDT.</p>
 */
final class OutputConsole extends JPanel {

    /** Transcript cap; oldest blocks are dropped beyond it. */
    static final int MAX_LINES = 2_000;

    private final JTextArea text = new JTextArea();
    private int droppedLines;

    OutputConsole() {
        super(new BorderLayout());
        setName("outputConsole");

        JPanel header = new JPanel(new BorderLayout());
        header.add(new JLabel("Output"), BorderLayout.WEST);
        JButton clear = new JButton("Clear");
        clear.setToolTipText("Empty the output console");
        clear.setFocusable(false);
        clear.addActionListener(e -> clear());
        header.add(clear, BorderLayout.EAST);

        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(text);
        scroll.setName("outputConsoleScroll");

        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        // The split pane stretches the width; only the initial height matters.
        setPreferredSize(new Dimension(420, 160));
    }

    /**
     * Appends one run's output: a {@code ---- title ----} rule, the body and
     * a blank separator. An empty body is recorded as {@code (no output)}.
     */
    void appendOutput(String title, String body) {
        String safeTitle = (title == null || title.isBlank()) ? "output" : title.trim();
        String safeBody = (body == null || body.isEmpty()) ? "(no output)"
                : body.stripTrailing();
        text.append("---- " + safeTitle + " ----\n");
        text.append(safeBody + "\n\n");
        trimToCap();
        text.setCaretPosition(text.getDocument().getLength());
    }

    /** Empties the console (also resets the drop counter). */
    void clear() {
        text.setText("");
        droppedLines = 0;
    }

    /** The whole transcript (test seam; synchronous). */
    String consoleText() {
        return text.getText();
    }

    /** How many header/body rules are in the transcript (test seam). */
    int blockCount() {
        return countMatches(consoleText(), "---- ");
    }

    /**
     * Drops the oldest lines when the cap is exceeded. Runs on the EDT only;
     * called synchronously from {@link #appendOutput}.
     */
    private void trimToCap() {
        String[] lines = text.getText().split("\n", -1);
        // Keep the trailing newline as its own implicit line for the count.
        int real = lines.length;
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) {
            real = lines.length - 1;
        }
        if (real <= MAX_LINES) {
            return;
        }
        int excess = real - MAX_LINES;
        droppedLines += excess;
        StringBuilder sb = new StringBuilder();
        for (int i = excess; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        text.setText("[dropped " + droppedLines + " older line(s)]\n" + sb);
    }

    private static int countMatches(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    /** How many older lines the cap has dropped so far (test seam). */
    int droppedLines() {
        return droppedLines;
    }

    /** Invokes {@link #appendOutput} on the EDT when called off it. */
    void appendOutputOnEdt(String title, String body) {
        if (SwingUtilities.isEventDispatchThread()) {
            appendOutput(title, body);
        } else {
            SwingUtilities.invokeLater(() -> appendOutput(title, body));
        }
    }
}
