/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.wg.switcher;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.Icon;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The Swing view of the application switcher: a translucent strip of window
 * icons painted over the desktop, with a moving highlight. All cycling logic
 * lives in {@link SwitcherController}; this class only renders its state and
 * turns key presses into controller calls, so it holds no behaviour worth
 * unit-testing on its own.
 *
 * <p>Interaction mirrors Alt+Tab: the trigger key opens the strip and steps the
 * highlight (Shift steps back); because a windowed lg3d cannot reliably see the
 * modifier keys being released, the selection is committed automatically a
 * beat after the last key press (or immediately on Enter), and Escape cancels.
 * When lg3d owns the display and does receive the real Alt+Tab, the same
 * binding works unchanged.</p>
 *
 * <p>The strip is added to the host's {@link JLayeredPane#POPUP_LAYER} rather
 * than shown in its own top-level window, so it stays inside the desktop and
 * never escapes it.</p>
 */
public final class SwitcherOverlay extends JPanel {

    /** Per-cell box and gaps, in pixels. */
    private static final int CELL_W = 104;
    private static final int CELL_H = 104;
    private static final int GAP = 8;
    private static final int PAD = 14;
    private static final int ICON_BOX = 48;
    private static final int MAX_CELLS_PER_ROW = 8;

    /** Idle delay before an open session commits itself, in milliseconds. */
    private static final int AUTO_COMMIT_MS = 700;

    private static final String A_ADVANCE = "lg3d.switcher.advance";
    private static final String A_BACK = "lg3d.switcher.back";
    private static final String A_COMMIT = "lg3d.switcher.commit";
    private static final String A_CANCEL = "lg3d.switcher.cancel";

    private final JLayeredPane host;
    private final SwitcherModel model;
    private final SwitcherController controller;
    private final Timer autoCommit;

    private final List<KeyStroke> bound = new ArrayList<>();
    private JRootPane installedRoot;

    private final Action advanceAction = new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
            step(controller.advance());
        }
    };
    private final Action backAction = new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
            step(controller.advanceBack());
        }
    };
    private final Action commitAction = new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
            commit();
        }
    };
    private final Action cancelAction = new AbstractAction() {
        @Override
        public void actionPerformed(ActionEvent e) {
            cancel();
        }
    };

    /**
     * @param host  the layered pane (the desktop pane) the strip is painted on
     * @param model the desktop-specific window source
     */
    public SwitcherOverlay(JLayeredPane host, SwitcherModel model) {
        this.host = host;
        this.model = model;
        this.controller = new SwitcherController(model);
        setOpaque(false);
        setFocusable(false);
        setVisible(false);
        this.autoCommit = new Timer(AUTO_COMMIT_MS, e -> commit());
        this.autoCommit.setRepeats(false);
    }

    /** The controller this overlay renders (for diagnostics). */
    public SwitcherController getController() {
        return controller;
    }

    /**
     * Binds the trigger and navigation keys on the host window's root pane. A
     * no-op if the host is not yet inside a window (nothing to bind to).
     */
    public void install() {
        JRootPane root = (host == null) ? null : SwingUtilities.getRootPane(host);
        if (root == null) {
            return;
        }
        InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = root.getActionMap();
        am.put(A_ADVANCE, advanceAction);
        am.put(A_BACK, backAction);
        am.put(A_COMMIT, commitAction);
        am.put(A_CANCEL, cancelAction);

        String spec = model.triggerKeySpec();
        bind(im, spec, A_ADVANCE);
        bind(im, "alt TAB", A_ADVANCE);
        bind(im, withShift(spec), A_BACK);
        bind(im, "shift alt TAB", A_BACK);
        bind(im, "ENTER", A_COMMIT);
        bind(im, "ESCAPE", A_CANCEL);
        installedRoot = root;
    }

    /** Removes the key bindings and hides the strip. Never throws. */
    public void uninstall() {
        autoCommit.stop();
        dismiss();
        if (installedRoot != null) {
            InputMap im =
                    installedRoot.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            ActionMap am = installedRoot.getActionMap();
            for (KeyStroke ks : bound) {
                im.remove(ks);
            }
            am.remove(A_ADVANCE);
            am.remove(A_BACK);
            am.remove(A_COMMIT);
            am.remove(A_CANCEL);
            installedRoot = null;
        }
        bound.clear();
    }

    private void bind(InputMap im, String spec, String actionKey) {
        if (spec == null || spec.isBlank()) {
            return;
        }
        KeyStroke ks = KeyStroke.getKeyStroke(spec);
        if (ks != null) {
            im.put(ks, actionKey);
            bound.add(ks);
        }
    }

    private static String withShift(String spec) {
        return (spec == null || spec.isBlank()) ? null : "shift " + spec;
    }

    /** Shows or refreshes the strip after a successful step; ignores a no-op. */
    private void step(boolean opened) {
        if (!opened) {
            return;
        }
        present();
        autoCommit.restart();
    }

    private void commit() {
        autoCommit.stop();
        controller.commit();
        dismiss();
    }

    private void cancel() {
        autoCommit.stop();
        controller.cancel();
        dismiss();
    }

    /** Adds (if needed), sizes and centres the strip, then repaints it. */
    private void present() {
        if (host == null) {
            return;
        }
        if (getParent() != host) {
            host.add(this, JLayeredPane.POPUP_LAYER);
        }
        List<SwitcherItem> items = controller.items();
        int n = Math.max(1, items.size());
        int cols = Math.min(n, MAX_CELLS_PER_ROW);
        int rows = (n + MAX_CELLS_PER_ROW - 1) / MAX_CELLS_PER_ROW;
        int w = PAD * 2 + cols * CELL_W + (cols - 1) * GAP;
        int h = PAD * 2 + rows * CELL_H + (rows - 1) * GAP;
        int hostW = host.getWidth();
        int hostH = host.getHeight();
        int x = Math.max(0, (hostW - w) / 2);
        int y = Math.max(0, (hostH - h) / 2);
        setBounds(x, y, w, h);
        setVisible(true);
        host.repaint();
        repaint();
    }

    private void dismiss() {
        autoCommit.stop();
        setVisible(false);
        if (host != null && getParent() == host) {
            host.remove(this);
            host.repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        List<SwitcherItem> items = controller.items();
        if (items.isEmpty() || !(g instanceof Graphics2D)) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g2.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, 0.78f));
            g2.setColor(new Color(18, 22, 30));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
            g2.setComposite(AlphaComposite.SrcOver);
            g2.setColor(new Color(110, 150, 210));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);

            int selected = controller.getIndex();
            FontMetrics fm = g2.getFontMetrics();
            for (int i = 0; i < items.size(); i++) {
                int col = i % MAX_CELLS_PER_ROW;
                int row = i / MAX_CELLS_PER_ROW;
                int x = PAD + col * (CELL_W + GAP);
                int y = PAD + row * (CELL_H + GAP);
                paintCell(g2, fm, items.get(i), x, y, i == selected);
            }
        } finally {
            g2.dispose();
        }
    }

    private void paintCell(Graphics2D g2, FontMetrics fm, SwitcherItem item,
                           int x, int y, boolean selected) {
        if (selected) {
            g2.setColor(new Color(70, 120, 200, 160));
            g2.fillRoundRect(x, y, CELL_W, CELL_H, 14, 14);
            g2.setColor(new Color(160, 200, 255));
            g2.drawRoundRect(x, y, CELL_W, CELL_H, 14, 14);
        } else {
            g2.setColor(new Color(255, 255, 255, 28));
            g2.fillRoundRect(x, y, CELL_W, CELL_H, 14, 14);
        }

        // Icon (or thumbnail) centred in the upper part of the cell.
        Image thumb = item.getThumbnail();
        if (thumb != null) {
            g2.drawImage(thumb, x + (CELL_W - ICON_BOX) / 2, y + 12,
                    ICON_BOX, ICON_BOX, null);
        } else {
            Icon icon = item.getIcon();
            if (icon != null) {
                int iw = Math.min(icon.getIconWidth(), ICON_BOX);
                int ih = Math.min(icon.getIconHeight(), ICON_BOX);
                icon.paintIcon(this, g2,
                        x + (CELL_W - iw) / 2, y + 12 + (ICON_BOX - ih) / 2);
            } else {
                g2.setColor(new Color(200, 210, 225, 120));
                g2.fillRect(x + (CELL_W - ICON_BOX) / 2, y + 12,
                        ICON_BOX, ICON_BOX);
            }
        }

        g2.setColor(selected ? Color.WHITE : new Color(210, 218, 228));
        String label = elide(item.getName(), fm, CELL_W - 8);
        int tx = x + (CELL_W - fm.stringWidth(label)) / 2;
        g2.drawString(label, tx, y + 12 + ICON_BOX + fm.getAscent() + 4);
    }

    /** Truncates {@code text} with an ellipsis so it fits {@code maxWidth}. */
    private static String elide(String text, FontMetrics fm, int maxWidth) {
        if (text == null || text.isEmpty() || fm.stringWidth(text) <= maxWidth) {
            return (text == null) ? "" : text;
        }
        String ellipsis = "\u2026";
        int ellipsisW = fm.stringWidth(ellipsis);
        int end = text.length();
        while (end > 0
                && fm.stringWidth(text.substring(0, end)) + ellipsisW > maxWidth) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }
}
