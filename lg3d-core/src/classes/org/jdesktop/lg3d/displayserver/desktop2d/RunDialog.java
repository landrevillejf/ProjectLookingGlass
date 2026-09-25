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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.border.EmptyBorder;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.MenuModel;

/**
 * The Alt+F2 "run command" box: a lightweight popup with a single text field,
 * a status line, and up/down recall through the persisted {@link RunHistory}.
 * Pressing Enter resolves the text through {@link RunResolver} and hands the
 * outcome to a {@link Runner} (which the desktop wires to launching an app or
 * an external command); Escape closes it.
 *
 * <p>It is a {@link JPopupMenu} rather than a {@code JDialog} so the whole
 * widget — field, key bindings, submit logic — can be constructed and exercised
 * headless, the same way {@link CalendarPopup} is. All decision-making lives in
 * the pure {@link RunResolver} and {@link RunHistory} seams; this class is only
 * the thin Swing glue between them and the user.</p>
 */
final class RunDialog {

    /** Performs the action a resolved entry asks for. */
    interface Runner {
        void run(RunResolver.Decision decision);
    }

    /** Prompt shown above the field. */
    static final String TITLE = "Run command";

    /** Status text before anything is entered. */
    static final String HINT = "Type an application name or a command";

    private final MenuModel model;
    private final RunHistory history;
    private final RunHistoryStore store;
    private final Runner runner;

    private final JPopupMenu popup;
    private final JTextField field;
    private final JLabel status;

    /** Index into {@link RunHistory#entries()} while recalling; -1 = not recalling. */
    private int historyIndex = -1;

    /**
     * Builds the dialog. {@code model} supplies app-name matching, {@code history}
     * and {@code store} the recalled commands, and {@code runner} what to do with
     * a resolved entry.
     */
    RunDialog(MenuModel model, RunHistory history, RunHistoryStore store, Runner runner) {
        this.model = model;
        this.history = (history == null) ? new RunHistory() : history;
        this.store = store;
        this.runner = runner;

        popup = new JPopupMenu();
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(new EmptyBorder(8, 10, 8, 10));
        panel.setBackground(Color.WHITE);

        JLabel title = new JLabel(TITLE);
        title.setForeground(new Color(0x2F, 0x6F, 0xED));
        panel.add(title, BorderLayout.NORTH);

        field = new JTextField(28);
        field.setBackground(Color.WHITE);
        bindKeys(field);
        panel.add(field, BorderLayout.CENTER);

        status = new JLabel(HINT);
        status.setForeground(Color.GRAY);
        panel.add(status, BorderLayout.SOUTH);

        popup.add(panel);
    }

    /** Wires Enter (run), Escape (close) and Up/Down (recall) on the field. */
    private void bindKeys(JComponent c) {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "run");
        c.getActionMap().put("run", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                submit();
            }
        });
        c.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        c.getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                hide();
            }
        });
        c.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "older");
        c.getActionMap().put("older", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                recallOlder();
            }
        });
        c.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "newer");
        c.getActionMap().put("newer", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                recallNewer();
            }
        });
    }

    /**
     * Resolves the field's text and, unless it matches nothing, records it in the
     * history, persists that history, runs it and closes. A non-match leaves the
     * dialog open with an explanatory status so the user can correct it.
     */
    void submit() {
        String text = field.getText();
        RunResolver.Decision decision = RunResolver.resolve(text, model);
        if (decision.isNotFound()) {
            String trimmed = (text == null) ? "" : text.trim();
            status.setText(trimmed.isEmpty()
                    ? HINT
                    : "Not found: " + trimmed);
            return;
        }
        history.add(text);
        if (store != null) {
            store.save(history);
        }
        historyIndex = -1;
        if (runner != null) {
            runner.run(decision);
        }
        hide();
    }

    /** Moves one step back through the history (older entries). */
    void recallOlder() {
        List<String> entries = history.entries();
        if (entries.isEmpty()) {
            return;
        }
        historyIndex = Math.min(historyIndex + 1, entries.size() - 1);
        field.setText(entries.get(historyIndex));
    }

    /** Moves one step forward through the history (newer entries). */
    void recallNewer() {
        List<String> entries = history.entries();
        if (entries.isEmpty() || historyIndex < 0) {
            return;
        }
        historyIndex--;
        if (historyIndex < 0) {
            field.setText("");
        } else {
            field.setText(entries.get(historyIndex));
        }
    }

    /** Shows the dialog near the top-centre of {@code anchor} and focuses the field. */
    void show(Component anchor) {
        if (anchor == null) {
            return;
        }
        status.setText(HINT);
        field.setText("");
        historyIndex = -1;
        popup.pack();
        int x = Math.max(0, (anchor.getWidth() - popup.getWidth()) / 2);
        int y = Math.max(0, anchor.getHeight() / 4);
        popup.show(anchor, x, y);
        field.requestFocusInWindow();
    }

    /** Hides the dialog. */
    void hide() {
        popup.setVisible(false);
    }

    /** The underlying popup (package-visible for tests and diagnostics). */
    JPopupMenu popup() {
        return popup;
    }

    /** The command field (package-visible for tests). */
    JTextField field() {
        return field;
    }

    /** The status line's current text (package-visible for tests). */
    String statusText() {
        return status.getText();
    }
}
