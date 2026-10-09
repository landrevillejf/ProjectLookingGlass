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

import java.awt.FlowLayout;
import java.awt.GridLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The editor's slide-down Find &amp; Replace bar: two compact rows (find with
 * navigation and match count; replace with Go-to-Line on the right) shown
 * inside the panel rather than in a dialog, so it works identically in the
 * 2D MDI host and inside the 3D desktop's offscreen SwingNode capture
 * (a floating dialog would escape the captured quad; an inline bar cannot).
 *
 * <p>The bar owns only its widgets and query state; every action is delegated
 * to a {@link Host} (the panel), which keeps the search semantics in
 * {@link SearchEngine} and the document mutation in {@link EditorTab}. The
 * match-count label is backed by a plain field so headless tests can assert
 * it without pumping the EDT.</p>
 */
public final class FindReplaceBar extends JPanel {

    /** The panel-side callbacks the bar delegates to. */
    public interface Host {

        /** Re-runs the live match count / first-hit highlight. */
        void queryChanged();

        /** Selects the next match (forward, wrapping). */
        void findNext();

        /** Selects the previous match (backward, wrapping). */
        void findPrevious();

        /** Replaces the currently selected match and advances. */
        void replaceOne();

        /** Replaces every match in one undo step. */
        void replaceAll();

        /** Jumps to the line typed in the Go-to-Line field. */
        void goToLine();

        /** Hides the bar and returns focus to the text. */
        void closeBar();
    }

    private final Host host;
    private final JTextField findField = new JTextField(24);
    private final JTextField replaceField = new JTextField(24);
    private final JTextField lineField = new JTextField(5);
    private final JToggleButton caseToggle = new JToggleButton("Aa");
    private final JToggleButton regexToggle = new JToggleButton(".*");
    private final JToggleButton wrapToggle = new JToggleButton("Wrap");
    private final JLabel countLabel = new JLabel(" ");
    private String matchInfo = "";

    public FindReplaceBar(Host host) {
        this.host = host;
        setLayout(new GridLayout(2, 1));

        caseToggle.setToolTipText("Match case");
        regexToggle.setToolTipText("Regular expression");
        wrapToggle.setToolTipText("Wrap around the document");
        wrapToggle.setSelected(true);
        countLabel.setToolTipText("Matches found");

        JPanel findRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        findRow.add(new JLabel("Find:"));
        findRow.add(findField);
        findRow.add(button("\u25B2", "Previous match (Shift+Enter)",
                e -> host.findPrevious()));
        findRow.add(button("\u25BC", "Next match (Enter)", e -> host.findNext()));
        findRow.add(caseToggle);
        findRow.add(regexToggle);
        findRow.add(wrapToggle);
        findRow.add(countLabel);
        findRow.add(button("\u2715", "Close (Escape)", e -> host.closeBar()));

        JPanel replaceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        replaceRow.add(new JLabel("Replace:"));
        replaceRow.add(replaceField);
        replaceRow.add(button("Replace", "Replace the selected match",
                e -> host.replaceOne()));
        replaceRow.add(button("Replace All", "Replace every match at once",
                e -> host.replaceAll()));
        replaceRow.add(new JLabel("   Go to line:"));
        replaceRow.add(lineField);
        replaceRow.add(button("Go", "Jump to the line (Enter)",
                e -> host.goToLine()));

        add(findRow);
        add(replaceRow);

        findField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                host.queryChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                host.queryChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                host.queryChanged();
            }
        });
        findField.addActionListener(e -> host.findNext());
        // Shift+Enter walks backwards through the matches.
        findField.getInputMap().put(
                javax.swing.KeyStroke.getKeyStroke(
                        java.awt.event.KeyEvent.VK_ENTER,
                        java.awt.event.InputEvent.SHIFT_DOWN_MASK),
                "lg3d-find-prev");
        findField.getActionMap().put("lg3d-find-prev",
                new javax.swing.AbstractAction() {
                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent e) {
                        host.findPrevious();
                    }
                });
        lineField.addActionListener(e -> host.goToLine());
        // Toggling any search mode re-runs the live match count.
        caseToggle.addActionListener(e -> host.queryChanged());
        regexToggle.addActionListener(e -> host.queryChanged());
        wrapToggle.addActionListener(e -> host.queryChanged());
    }

    private static JButton button(String text, String tooltip,
            java.awt.event.ActionListener action) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.addActionListener(action);
        return button;
    }

    /** The current find query (never null). */
    public String query() {
        return findField.getText();
    }

    public void setQuery(String query) {
        findField.setText(query);
    }

    /** The current replacement spec (never null). */
    public String replacement() {
        return replaceField.getText();
    }

    public void setReplacement(String replacement) {
        replaceField.setText(replacement);
    }

    public boolean matchCase() {
        return caseToggle.isSelected();
    }

    public boolean regex() {
        return regexToggle.isSelected();
    }

    public boolean wrap() {
        return wrapToggle.isSelected();
    }

    /** The parsed Go-to-Line value, or -1 when blank/invalid. */
    public int lineNumber() {
        try {
            return Integer.parseInt(lineField.getText().trim());
        } catch (NumberFormatException nfe) {
            return -1;
        }
    }

    public void setLineNumber(int line) {
        lineField.setText(Integer.toString(line));
    }

    /** The "n of m matches" info text; backed by a field for tests. */
    public String matchInfo() {
        return matchInfo;
    }

    public void setMatchInfo(String info) {
        this.matchInfo = (info != null) ? info : "";
        countLabel.setText(this.matchInfo.isEmpty() ? " " : this.matchInfo);
    }

    /** Focuses the find field, selecting its content for quick retyping. */
    public void focusFind() {
        findField.requestFocusInWindow();
        findField.selectAll();
    }

    /** Focuses the Go-to-Line field. */
    public void focusLine() {
        lineField.requestFocusInWindow();
        lineField.selectAll();
    }
}
