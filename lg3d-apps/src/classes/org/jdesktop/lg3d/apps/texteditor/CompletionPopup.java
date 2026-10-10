/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/**
 * The inline code-completion popup (Espresso Phase 6): an IDE-style candidate
 * list painted <em>inside</em> the editor at the caret, in addition to the
 * bottom {@link CompletionPanel} strip. It is a plain, borderless
 * {@code JPanel} added to the current document's {@link JScrollPane}
 * {@link JLayeredPane} &mdash; the same pane the editor text lives in &mdash;
 * never a {@code JWindow} or {@code JPopupMenu}, so it composites into the 3D
 * desktop's offscreen {@code SwingNode} capture like every other Espresso
 * surface.
 *
 * <p>Positioning reads the caret's visual rectangle
 * ({@link EditorTab#caretVisualRectangle()}) and drops the box just below and
 * left of it, clamped to the visible viewport and flipped above when it would
 * overflow the bottom. The show / hide rules themselves are delegated to the
 * pure {@link CompletionTrigger}; this class only renders a decision.</p>
 *
 * <p>Keyboard routing installs a {@link KeyAdapter} on the editor's text
 * component that consumes Up/Down (move selection), Enter/Tab (accept, reusing
 * the strip's {@link EditorTab#insertCompletion(String)} path) and Escape
 * (dismiss) <em>only while the popup is showing</em>; every other keystroke
 * falls through untouched, so normal editing (and the editor's own Tab indent /
 * Enter newline bindings) is never hijacked. EDT-only.</p>
 */
final class CompletionPopup {

    /** Maximum rows shown before the list scrolls. */
    private static final int VISIBLE_ROWS = 8;

    /** Candidate cap, matching the strip / engine. */
    private static final int MAX_CANDIDATES = 50;

    private final JPanel panel = new JPanel(new BorderLayout());
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);

    private EditorTab host;
    private boolean showing;
    private Consumer<String> onAccept = s -> { };

    CompletionPopup() {
        panel.setName("completionPopup");
        panel.setBorder(BorderFactory.createLineBorder(new Color(150, 150, 150)));
        panel.setVisible(false);

        list.setName("completionPopupList");
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(VISIBLE_ROWS);
        list.setFixedCellWidth(220);

        JScrollPane scroller = new JScrollPane(list,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroller.setBorder(BorderFactory.createEmptyBorder());
        panel.add(scroller, BorderLayout.CENTER);
    }

    /** Host hook: invoked with the accepted candidate text. */
    void setOnAccept(Consumer<String> handler) {
        this.onAccept = (handler != null) ? handler : s -> { };
    }

    /** @return the borderless panel that the host reparents into a layered pane. */
    JPanel panel() {
        return panel;
    }

    /** @return true while the popup is painted over an editor. */
    boolean isShowing() {
        return showing;
    }

    /**
     * Attaches the popup to the enclosing {@link JRootPane}'s layered pane (a
     * lightweight surface that composites into the editor's offscreen capture),
     * fills the candidate list and positions it at the caret. A tab change
     * re-parents the single popup instance. No-op until the editor is in a
     * window: when there is no layered pane, or the caret has no visual rectangle
     * yet (offscreen / not laid out), the model is still populated but the box
     * stays hidden rather than appearing at a bogus location.
     *
     * @param tab        the editor tab to overlay
     * @param candidates the candidates to show (already capped by the caller)
     */
    void show(EditorTab tab, List<String> candidates) {
        if (tab == null || candidates == null || candidates.isEmpty()) {
            hide();
            return;
        }
        model.clear();
        int n = Math.min(candidates.size(), MAX_CANDIDATES);
        for (int i = 0; i < n; i++) {
            String c = candidates.get(i);
            if (c != null) {
                model.addElement(c);
            }
        }
        if (model.isEmpty()) {
            hide();
            return;
        }
        list.setSelectedIndex(0);

        JRootPane root = SwingUtilities.getRootPane(tab);
        JLayeredPane layered = (root != null) ? root.getLayeredPane() : null;
        Rectangle caret = tab.caretVisualRectangle();
        if (layered == null || caret == null) {
            // Not in a window yet, or the view is not laid out: populate but hide.
            panel.setVisible(false);
            showing = false;
            host = tab;
            return;
        }
        if (panel.getParent() != layered) {
            detach();
            layered.add(panel, JLayeredPane.POPUP_LAYER);
        }
        host = tab;

        Rectangle inLayered = SwingUtilities.convertRectangle(
                tab.textPane(), caret, layered);
        layoutWithin(layered, inLayered);
        panel.setVisible(true);
        layered.repaint();
        showing = true;
    }

    /** Sizes the box and places it below-left of the caret, flipped/clamped. */
    private void layoutWithin(JLayeredPane layered, Rectangle caret) {
        int rows = Math.min(model.size(), VISIBLE_ROWS);
        int cellH = Math.max(list.getFont().getSize() + 6, 16);
        int width = list.getFixedCellWidth() + 12;
        int height = rows * cellH + 8;
        int x = caret.x;
        int y = caret.y + caret.height + 1;
        int boundsW = layered.getWidth();
        int boundsH = layered.getHeight();
        if (x + width > boundsW) {
            x = Math.max(0, boundsW - width);
        }
        if (y + height > boundsH) {
            y = Math.max(0, caret.y - height - 1); // flip above the caret
        }
        panel.setBounds(x, y, width, height);
    }

    /** Hides the popup and detaches it from any layered pane. */
    void hide() {
        panel.setVisible(false);
        detach();
        showing = false;
    }

    /** Removes the panel from whatever layered pane currently holds it. */
    private void detach() {
        Container parent = panel.getParent();
        if (parent != null) {
            parent.remove(panel);
        }
    }

    /**
     * Installs the routing key listener on {@code tab}'s text component. The
     * listener is added once per tab and no-ops unless this popup is showing for
     * that tab, so the editor's own keys are untouched while it is hidden.
     */
    void installOn(EditorTab tab) {
        if (tab == null) {
            return;
        }
        JTextComponent pane = tab.textPane();
        pane.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!showing || tab != host) {
                    return;
                }
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_UP -> {
                        move(-1);
                        e.consume();
                    }
                    case KeyEvent.VK_DOWN -> {
                        move(+1);
                        e.consume();
                    }
                    case KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                        accept();
                        e.consume();
                    }
                    case KeyEvent.VK_ESCAPE -> {
                        hide();
                        e.consume();
                    }
                    default -> {
                        // Home/End/page keys move the caret off the run: let the
                        // host's caret listener hide the popup; pass through here.
                    }
                }
            }
        });
    }

    /** Moves the selection by {@code delta}, clamped, wrapping never. */
    void move(int delta) {
        int size = model.size();
        if (size == 0) {
            return;
        }
        int cur = list.getSelectedIndex();
        if (cur < 0) {
            cur = 0;
        }
        int next = Math.max(0, Math.min(cur + delta, size - 1));
        list.setSelectedIndex(next);
        list.ensureIndexIsVisible(next);
    }

    /** @return the selected candidate text, or null when empty. */
    String selected() {
        return list.getSelectedValue();
    }

    /** Accepts the selection through the host hook (insert at caret). */
    void accept() {
        String value = list.getSelectedValue();
        hide();
        if (value != null) {
            onAccept.accept(value);
        }
    }

    // -- test seams ------------------------------------------------------------

    /** @return the candidate at {@code index} (test seam). */
    String rowAt(int index) {
        return model.get(index);
    }

    /** @return the number of candidates shown (test seam). */
    int rowCount() {
        return model.size();
    }
}
