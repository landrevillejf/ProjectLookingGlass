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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.Icon;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JDesktopPane;
import javax.swing.KeyStroke;
import javax.swing.Timer;

/**
 * The translucent overlay the 2D desktop's window switcher paints while the
 * user cycles between open application windows, plus the key bindings that
 * drive it.
 *
 * <p>The trigger is deliberately <em>not</em> Alt+Tab: the 2D desktop is an
 * ordinary window under the host window manager (GNOME/Mutter, Xwayland, ...),
 * which grabs Alt+Tab, Super+Tab and the Ctrl+Alt+arrow workspace keys before
 * they ever reach this JVM, so the switcher would never see them. Alt+grave
 * ({@code Alt+`}) is the conventional in-application window-cycle shortcut, is
 * not reserved by the common host window managers, and does not clash with any
 * Swing or MDI default, so it reliably arrives here.</p>
 *
 * <p>{@code Alt+`} opens the switcher and steps forward through the
 * most-recently-used windows; {@code Alt+Shift+`} steps backward. Because
 * detecting the release of the Alt modifier is unreliable across platforms, the
 * selection is committed by a short idle timer that fires once the user stops
 * pressing the trigger, exactly like the classic behaviour &mdash; there is no
 * separate confirm key, and no global binding is placed on Escape or Enter
 * (which would hijack dialogs and text fields).</p>
 *
 * <p>The overlay lives on the desktop pane's {@link JDesktopPane#POPUP_LAYER},
 * so it floats above every application window but below nothing that matters,
 * and paints nothing while idle.</p>
 */
final class WindowCyclerOverlay extends JComponent {

    /** Key-spec that steps forward (and opens the switcher on first press). */
    static final String NEXT_SPEC = "alt BACK_QUOTE";
    /** Key-spec that steps backward. */
    static final String PREV_SPEC = "alt shift BACK_QUOTE";

    private static final String NEXT_ACTION = "lg.windowCycler.next";
    private static final String PREV_ACTION = "lg.windowCycler.prev";

    /** Idle delay before an open selection commits itself, in milliseconds. */
    private static final int COMMIT_DELAY_MS = 600;

    private static final int ROW_HEIGHT_PX = 34;
    private static final int PAD_PX = 12;
    private static final int ICON_PX = 20;
    private static final int ARC_PX = 18;
    private static final int MAX_WIDTH_PX = 420;

    private static final Color CARD = new Color(20, 24, 32);
    private static final Color CARD_BORDER = new Color(120, 160, 220);
    private static final Color SELECT_FILL = new Color(70, 120, 200);
    private static final Color TEXT = Color.WHITE;
    private static final Color TEXT_DIM = new Color(200, 208, 220);

    /**
     * The desktop-specific seam: how to enumerate the open windows and how to
     * bring one forward. Faked in headless tests so the overlay needs neither a
     * live desktop nor a real window.
     */
    interface WindowSource {
        /** The windows currently on the desktop, front-most first. */
        List<Desktop2DWindow> presentWindows();

        /** Brings {@code window} forward and gives it the focus. */
        void focus(Desktop2DWindow window);
    }

    private final WindowCycler<Desktop2DWindow> cycler = new WindowCycler<>();
    private final WindowSource source;
    private final Timer commitTimer;
    private JDesktopPane host;

    WindowCyclerOverlay(WindowSource source) {
        this.source = source;
        setOpaque(false);
        setVisible(false);
        this.commitTimer = new Timer(COMMIT_DELAY_MS, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                commitSelection();
            }
        });
        this.commitTimer.setRepeats(false);
    }

    /** The window's own MRU tracker, so {@code Desktop2D} can feed it events. */
    WindowCycler<Desktop2DWindow> cycler() {
        return cycler;
    }

    /**
     * Installs the overlay on {@code desktop}'s popup layer and binds the
     * trigger keys there, so they fire no matter which child window holds the
     * focus. Safe to call once from the desktop constructor.
     */
    void install(JDesktopPane desktop) {
        this.host = desktop;
        setBounds(0, 0, desktop.getWidth(), desktop.getHeight());
        desktop.add(this, JDesktopPane.POPUP_LAYER);
        bindKeys(desktop);
    }

    /** Removes the overlay and its key bindings from the desktop pane. */
    void uninstall() {
        commitTimer.stop();
        if (host != null) {
            unbindKeys(host);
            host.remove(this);
            host.revalidate();
            host.repaint();
            host = null;
        }
    }

    private void bindKeys(JComponent target) {
        InputMap ancestor =
                target.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        InputMap focused = target.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actions = target.getActionMap();
        KeyStroke next = KeyStroke.getKeyStroke(NEXT_SPEC);
        KeyStroke prev = KeyStroke.getKeyStroke(PREV_SPEC);
        ancestor.put(next, NEXT_ACTION);
        ancestor.put(prev, PREV_ACTION);
        focused.put(next, NEXT_ACTION);
        focused.put(prev, PREV_ACTION);
        actions.put(NEXT_ACTION, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                trigger(true);
            }
        });
        actions.put(PREV_ACTION, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                trigger(false);
            }
        });
    }

    private void unbindKeys(JComponent target) {
        InputMap ancestor =
                target.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        InputMap focused = target.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actions = target.getActionMap();
        ancestor.remove(KeyStroke.getKeyStroke(NEXT_SPEC));
        ancestor.remove(KeyStroke.getKeyStroke(PREV_SPEC));
        focused.remove(KeyStroke.getKeyStroke(NEXT_SPEC));
        focused.remove(KeyStroke.getKeyStroke(PREV_SPEC));
        actions.remove(NEXT_ACTION);
        actions.remove(PREV_ACTION);
    }

    /**
     * One trigger press: opens the switcher if it is idle (giving up when there
     * are fewer than two windows), then steps the highlight forward or backward
     * and restarts the idle-commit timer.
     */
    void trigger(boolean forward) {
        if (!cycler.isActive() && !cycler.open(source.presentWindows())) {
            return;
        }
        if (forward) {
            cycler.advance();
        } else {
            cycler.advanceBack();
        }
        restartCommitTimer();
        refresh();
    }

    /** Commits the highlighted window (if any) and hides the overlay. */
    void commitSelection() {
        commitTimer.stop();
        Desktop2DWindow window = cycler.commit();
        if (window != null) {
            source.focus(window);
        }
        refresh();
    }

    /** Abandons the current selection and hides the overlay. */
    void cancelSelection() {
        commitTimer.stop();
        cycler.cancel();
        refresh();
    }

    private void restartCommitTimer() {
        commitTimer.restart();
    }

    /** Re-sizes to the host, syncs visibility to the session and repaints. */
    private void refresh() {
        if (host != null) {
            setBounds(0, 0, host.getWidth(), host.getHeight());
        }
        setVisible(cycler.isActive());
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (!cycler.isActive()) {
            return;
        }
        List<Desktop2DWindow> items = cycler.items();
        if (items.isEmpty()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font font = resolveFont();
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();

            int cardWidth = cardWidth(items, fm);
            int cardHeight = PAD_PX * 2 + items.size() * ROW_HEIGHT_PX;
            int x = Math.max(0, (getWidth() - cardWidth) / 2);
            int y = Math.max(0, getHeight() / 5);

            Composite saved = g2.getComposite();
            g2.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, 0.82f));
            g2.setColor(CARD);
            g2.fillRoundRect(x, y, cardWidth, cardHeight, ARC_PX, ARC_PX);
            g2.setComposite(saved);
            g2.setColor(CARD_BORDER);
            g2.drawRoundRect(x, y, cardWidth, cardHeight, ARC_PX, ARC_PX);

            int selected = cycler.selectedIndex();
            for (int i = 0; i < items.size(); i++) {
                int rowY = y + PAD_PX + i * ROW_HEIGHT_PX;
                if (i == selected) {
                    g2.setColor(SELECT_FILL);
                    g2.fillRoundRect(x + PAD_PX / 2, rowY,
                            cardWidth - PAD_PX, ROW_HEIGHT_PX - 4, 10, 10);
                }
                paintRow(g2, items.get(i), x + PAD_PX, rowY, fm, i == selected);
            }
        } finally {
            g2.dispose();
        }
    }

    private void paintRow(Graphics2D g2, Desktop2DWindow window, int x, int y,
                          FontMetrics fm, boolean selected) {
        int iconY = y + (ROW_HEIGHT_PX - ICON_PX) / 2;
        Icon icon = window.getFrameIcon();
        if (icon != null) {
            icon.paintIcon(this, g2, x, iconY);
        }
        int textX = x + ICON_PX + 8;
        g2.setColor(selected ? TEXT : TEXT_DIM);
        String name = window.getAppName();
        int textY = y + (ROW_HEIGHT_PX - fm.getHeight()) / 2 + fm.getAscent();
        g2.drawString(ellipsize(name, fm, maxTextWidth()), textX, textY);
    }

    private int cardWidth(List<Desktop2DWindow> items, FontMetrics fm) {
        int widest = 0;
        for (Desktop2DWindow window : items) {
            widest = Math.max(widest,
                    fm.stringWidth(ellipsize(window.getAppName(), fm,
                            maxTextWidth())));
        }
        return Math.min(MAX_WIDTH_PX,
                PAD_PX * 2 + ICON_PX + 8 + widest + PAD_PX);
    }

    private int maxTextWidth() {
        return MAX_WIDTH_PX - PAD_PX * 2 - ICON_PX - 8;
    }

    private static String ellipsize(String text, FontMetrics fm, int maxWidth) {
        if (text == null || fm.stringWidth(text) <= maxWidth) {
            return (text == null) ? "" : text;
        }
        String ellipsis = "\u2026";
        int ellipsisWidth = fm.stringWidth(ellipsis);
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (fm.stringWidth(sb.toString() + c) + ellipsisWidth > maxWidth) {
                break;
            }
            sb.append(c);
        }
        return sb + ellipsis;
    }

    /**
     * The component font, defensively: a peer-less overlay in a headless test
     * returns null from {@link #getFont()}, which would NPE the font-metrics
     * calls in {@link #paintComponent}. Falls back to a plain dialog font.
     */
    private Font resolveFont() {
        Font font = getFont();
        return (font != null) ? font : new Font(Font.DIALOG, Font.PLAIN, 13);
    }

    /** The trigger key code, exposed for documentation and diagnostics. */
    static int triggerKeyCode() {
        return KeyEvent.VK_BACK_QUOTE;
    }
}
