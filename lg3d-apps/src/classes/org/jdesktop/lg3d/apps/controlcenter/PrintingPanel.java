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
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import org.jdesktop.lg3d.displayserver.desktop2d.PrinterStatus;

/**
 * Printing panel: lists the CUPS printer queues, marks the system default, and
 * offers the two management actions that do not need a full spooler UI - make a
 * queue the default and submit a test page. The list is a {@link JList} (never
 * a combo box) so the panel keeps working when hosted offscreen in a
 * {@code SwingNode} on the 3D desktop; the same panel serves the 2D desktop.
 *
 * <p>All discovery and mutation go through the {@link PrinterStatus} seam, which
 * degrades to "no printers" on a host without CUPS rather than failing.</p>
 */
public class PrintingPanel implements ControlPanel {

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final DefaultListModel<String> names = new DefaultListModel<>();
    private final JList<String> printerList = new JList<>(names);
    private final JLabel statusLabel = new JLabel(" ");

    /** The queues behind the current list rows, parallel to {@link #names}. */
    private final List<PrinterStatus.Printer> current = new ArrayList<>();

    public PrintingPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        printerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        printerList.setVisibleRowCount(8);
        JScrollPane scroll = new JScrollPane(printerList);
        scroll.setPreferredSize(new Dimension(320, 180));

        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> reload());
        JButton setDefault = new JButton("Set Default");
        setDefault.addActionListener(e -> setDefault());
        JButton testPage = new JButton("Print Test Page");
        testPage.addActionListener(e -> printTestPage());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(refresh);
        buttons.add(setDefault);
        buttons.add(testPage);

        root.add(scroll, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        root.add(statusLabel, BorderLayout.NORTH);

        reload();
    }

    @Override
    public String displayName() {
        return "Printing";
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

    /** Re-reads the CUPS queues and the default, refreshing the list. */
    private void reload() {
        current.clear();
        names.clear();
        List<PrinterStatus.Printer> printers = PrinterStatus.read();
        String def = PrinterStatus.defaultPrinter();
        for (PrinterStatus.Printer p : printers) {
            current.add(p);
            String suffix = p.name().equals(def) ? "  [default]" : "";
            names.addElement(PrinterStatus.label(p) + suffix);
        }
        if (!names.isEmpty()) {
            printerList.setSelectedIndex(0);
            statusLabel.setText(printers.size() + " printer(s); default: "
                    + (def.isEmpty() ? "(none)" : def));
        } else {
            statusLabel.setText("No printers found (is CUPS running?)");
        }
    }

    /** The queue behind the selected row, or null when nothing is selected. */
    private PrinterStatus.Printer selected() {
        int i = printerList.getSelectedIndex();
        return (i >= 0 && i < current.size()) ? current.get(i) : null;
    }

    private void setDefault() {
        PrinterStatus.Printer p = selected();
        if (p == null) {
            statusLabel.setText("Select a printer first.");
            return;
        }
        boolean ok = PrinterStatus.setDefault(p.name());
        statusLabel.setText(ok
                ? "Default printer set to " + p.name()
                : "Could not set the default printer (is CUPS running?)");
        reload();
    }

    private void printTestPage() {
        PrinterStatus.Printer p = selected();
        if (p == null) {
            statusLabel.setText("Select a printer first.");
            return;
        }
        boolean ok = PrinterStatus.printTestPage(p.name());
        statusLabel.setText(ok
                ? "Test page sent to " + p.name()
                : "Could not print to " + p.name() + " (is CUPS running?)");
    }
}
