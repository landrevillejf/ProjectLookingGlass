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
import java.util.List;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListModel;
import javax.swing.table.DefaultTableModel;

/**
 * The bottom Debug tab: the debugger's surface, driven entirely by the
 * {@link org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission#DEBUG}
 * capabilities on {@link org.jdesktop.lg3d.apps.texteditor.ext.EditorContext}.
 * It shows the run state, the breakpoint list, the suspended thread's call
 * stack and local variables, and a monospaced debug transcript.
 *
 * <p>This panel owns no VM logic: the JVM debugger extension (Phase 3) feeds it
 * through the context. Plain Swing, no popups or dialogs, so it renders when the
 * editor is captured offscreen. EDT-only.</p>
 */
final class DebugPanel extends JPanel {

    private static final String[] FRAME_COLS = {"Frame"};
    private static final String[] LOCAL_COLS = {"Name", "Value", "Type"};

    private final JLabel stateLabel = new JLabel("Not debugging");
    private final DefaultListModel<String> breakpointModel = new DefaultListModel<>();
    private final JList<String> breakpoints = new JList<>(breakpointModel);
    private final DefaultTableModel stackModel = readOnlyModel(FRAME_COLS);
    private final DefaultTableModel localsModel = readOnlyModel(LOCAL_COLS);
    private final JTable stackTable = new JTable(stackModel);
    private final JTable localsTable = new JTable(localsModel);
    private final JTextArea output = new JTextArea();

    DebugPanel() {
        super(new BorderLayout());
        setName("debugPanel");

        JLabel heading = new JLabel("State: ");
        heading.add(stateLabel);
        JPanel header = new JPanel(new BorderLayout());
        stateLabel.setName("debugStateLabel");
        header.add(heading, BorderLayout.WEST);
        header.add(stateLabel, BorderLayout.CENTER);

        JPanel bpBox = new JPanel(new BorderLayout());
        bpBox.add(new JLabel("Breakpoints"), BorderLayout.NORTH);
        breakpoints.setName("debugBreakpoints");
        breakpoints.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        bpBox.add(new JScrollPane(breakpoints), BorderLayout.CENTER);
        bpBox.setPreferredSize(new Dimension(220, 160));

        JPanel tables = new JPanel(new BorderLayout());
        stackTable.setName("debugStack");
        localsTable.setName("debugLocals");
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        stackTable.setFont(mono);
        localsTable.setFont(mono);
        JSplitPane tablesSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                titled("Call stack", stackTable), titled("Locals", localsTable));
        tablesSplit.setResizeWeight(0.5);
        tables.add(tablesSplit, BorderLayout.CENTER);

        JSplitPane topSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                bpBox, tables);
        topSplit.setResizeWeight(0.0);

        output.setEditable(false);
        output.setFont(mono);
        output.setName("debugOutput");
        JPanel outScroll = titled("Debug output", output);

        JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                topSplit, outScroll);
        vertical.setResizeWeight(0.6);

        add(header, BorderLayout.NORTH);
        add(vertical, BorderLayout.CENTER);
        setPreferredSize(new Dimension(520, 220));
    }

    private static JPanel titled(String title, java.awt.Component body) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JLabel(title), BorderLayout.NORTH);
        p.add((body instanceof JScrollPane) ? body : new JScrollPane(body),
                BorderLayout.CENTER);
        return p;
    }

    private static DefaultTableModel readOnlyModel(String[] cols) {
        return new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    /** Appends one line to the debug transcript. */
    void appendOutput(String line) {
        if (line != null) {
            output.append(line);
            if (!line.endsWith("\n")) {
                output.append("\n");
            }
            output.setCaretPosition(output.getDocument().getLength());
        }
    }

    /** Sets the state label (e.g. running / paused at Foo.java:12 / detached). */
    void setState(String state) {
        stateLabel.setText((state == null || state.isBlank())
                ? "Not debugging" : state);
    }

    /** Replaces the call-stack rows. */
    void setStack(List<String> frames) {
        stackModel.setRowCount(0);
        if (frames != null) {
            for (String f : frames) {
                stackModel.addRow(new Object[] {f});
            }
        }
    }

    /** Replaces the local-variable rows ({@code name = value : type}). */
    void setLocals(List<String> locals) {
        localsModel.setRowCount(0);
        if (locals != null) {
            for (String entry : locals) {
                int eq = (entry == null) ? -1 : entry.indexOf('=');
                String name = (eq < 0) ? String.valueOf(entry) : entry.substring(0, eq).trim();
                String rest = (eq < 0) ? "" : entry.substring(eq + 1).trim();
                int colon = rest.lastIndexOf(':');
                String value = (colon < 0) ? rest : rest.substring(0, colon).trim();
                String type = (colon < 0) ? "" : rest.substring(colon + 1).trim();
                localsModel.addRow(new Object[] {name, value, type});
            }
        }
    }

    /** Replaces the breakpoint list. */
    void setBreakpoints(List<String> locations) {
        breakpointModel.clear();
        if (locations != null) {
            for (String loc : locations) {
                breakpointModel.addElement(String.valueOf(loc));
            }
        }
    }

    /** Clears every surface back to the idle state. */
    void reset() {
        setState("Not debugging");
        setStack(List.of());
        setLocals(List.of());
        breakpointModel.clear();
        output.setText("");
    }

    // ---- test seams ----

    /** @return the current state label text. */
    String stateText() {
        return stateLabel.getText();
    }

    /** @return the breakpoint list model. */
    ListModel<String> breakpointListModel() {
        return breakpointModel;
    }

    /** @return the debug transcript (test seam). */
    String outputText() {
        return output.getText();
    }

    /** @return how many stack rows are shown (test seam). */
    int stackRowCount() {
        return stackModel.getRowCount();
    }

    /** @return how many local rows are shown (test seam). */
    int localsRowCount() {
        return localsModel.getRowCount();
    }
}
