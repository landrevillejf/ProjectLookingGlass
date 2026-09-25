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
package org.jdesktop.lg3d.scenemanager.utils.run;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JPanel;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.MenuModel;
import org.jdesktop.lg3d.displayserver.desktop2d.RunHistory;
import org.jdesktop.lg3d.displayserver.desktop2d.RunHistoryStore;
import org.jdesktop.lg3d.displayserver.desktop2d.RunResolver;

/**
 * The Alt+F2 "run command" card for the native 3D desktop: the pure-Swing
 * counterpart of the 2D desktop's {@code RunDialog}, hosted on a
 * {@code SwingNode} on the front-most HUD layer by {@link RunDialog3D}.
 *
 * <p>It reuses the 2D desktop's pure seams rather than re-inventing them:
 * resolution goes through {@link RunResolver} (app-name match, else external
 * command on the PATH, else not-found) and recall through the persisted
 * {@link RunHistory}/{@link RunHistoryStore}, so both desktops share one
 * decision table and one history.</p>
 *
 * <h2>Why keys are dispatched programmatically</h2>
 * <p>A hosted {@code SwingNode} only delivers {@code KeyEvent3D} to its panel
 * while the node holds lg3d focus, which follows the pointer
 * ({@code MouseEnteredEvent3D}) &mdash; but a run dialog opened by Alt+F2 must
 * accept typing wherever the pointer is. {@link RunDialogPlugin} therefore owns
 * the global key listener and feeds each keystroke to {@link #dispatch(KeyEvent)}
 * while the card is up, so the field is driven directly instead of through AWT
 * focus. The field is non-editable for the same reason: it is a display of the
 * text this class maintains, never an AWT focus owner.</p>
 *
 * <p>Being pure Swing with no Java 3D, the whole submit/recall/resolve behaviour
 * is headless-testable; only the {@code SwingNode} host and the global key glue
 * in {@link RunDialogPlugin} need the live desktop.</p>
 */
public class RunDialogPanel extends JPanel {

    /** Prompt shown above the field. */
    public static final String TITLE = "Run command";

    /** Status text before anything is entered. */
    public static final String HINT = "Type an application name or a command";

    /** Fixed card footprint; the hosted SwingNode texture never resizes. */
    static final int CARD_WIDTH_PX = 420;
    static final int CARD_HEIGHT_PX = 116;
    static final int PAD_PX = 14;
    static final int ARC_PX = 18;
    static final int TITLE_H_PX = 22;
    static final int FIELD_H_PX = 32;
    static final int GAP_PX = 8;

    static final Color CARD = new Color(20, 24, 32);
    static final Color CARD_BORDER = new Color(120, 160, 220);
    static final Color FIELD_BG = new Color(38, 44, 56);
    static final Color FIELD_BORDER = new Color(90, 110, 150);
    static final Color TITLE_FG = new Color(120, 170, 255);
    static final Color TEXT = Color.WHITE;
    static final Color TEXT_DIM = new Color(200, 208, 220);

    private final MenuModel model;
    private final RunHistory history;
    private final RunHistoryStore store;
    private final Consumer<RunResolver.Decision> runner;
    private final Runnable onClose;

    /** The command text being edited; painted, not held in a Swing field. */
    private String text = "";

    /** The status line's text. */
    private String statusText = HINT;

    /** Index into {@link RunHistory#entries()} while recalling; -1 = not recalling. */
    private int historyIndex = -1;

    /**
     * Builds the card. {@code model} supplies app-name matching, {@code history}
     * and {@code store} the recalled commands, {@code runner} what to do with a
     * resolved entry and {@code onClose} the hide callback (Escape and a
     * successful submit).
     */
    public RunDialogPanel(MenuModel model, RunHistory history, RunHistoryStore store,
            Consumer<RunResolver.Decision> runner, Runnable onClose) {
        this.model = model;
        this.history = (history == null) ? new RunHistory() : history;
        this.store = store;
        this.runner = runner;
        this.onClose = onClose;

        // Same idiom as the window-switcher card, which is the one proven to
        // render on the HUD: non-opaque, a fixed footprint so the hosted
        // SwingNode texture is a constant size, and everything drawn in
        // paintComponent so the dark translucent card reads on any wallpaper.
        setOpaque(false);
        setPreferredSize(new Dimension(CARD_WIDTH_PX, CARD_HEIGHT_PX));
    }

    /** Resets the card for a new appearance (empty field, hint status). */
    public void reset() {
        text = "";
        statusText = HINT;
        historyIndex = -1;
        repaint();
    }

    /** The current command text. */
    public String text() {
        return text;
    }

    /** The status line's current text. */
    public String statusText() {
        return statusText;
    }

    /**
     * Feeds one AWT key event to the card. Control keys are handled on
     * {@code KEY_PRESSED}; printable characters on {@code KEY_TYPED}. Returns
     * true when the event was consumed (the caller should not pass it on).
     */
    public boolean dispatch(KeyEvent e) {
        if (e == null) {
            return false;
        }
        if (e.getID() == KeyEvent.KEY_PRESSED) {
            switch (e.getKeyCode()) {
                case KeyEvent.VK_ENTER:
                    submit();
                    return true;
                case KeyEvent.VK_ESCAPE:
                    close();
                    return true;
                case KeyEvent.VK_UP:
                    recallOlder();
                    return true;
                case KeyEvent.VK_DOWN:
                    recallNewer();
                    return true;
                case KeyEvent.VK_BACK_SPACE:
                    backspace();
                    return true;
                default:
                    return false;
            }
        }
        if (e.getID() == KeyEvent.KEY_TYPED) {
            char c = e.getKeyChar();
            if (c != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(c)) {
                append(c);
                return true;
            }
        }
        return false;
    }

    /** Appends one character to the command text. */
    public void append(char c) {
        text = text + c;
        historyIndex = -1;
        repaint();
    }

    /** Removes the last character of the command text. */
    public void backspace() {
        if (!text.isEmpty()) {
            text = text.substring(0, text.length() - 1);
        }
        historyIndex = -1;
        repaint();
    }

    /**
     * Resolves the field's text and, unless it matches nothing, records it in the
     * history, persists that history, runs it and closes. A non-match leaves the
     * card open with an explanatory status so the user can correct it. Returns
     * true when the entry ran.
     */
    public boolean submit() {
        RunResolver.Decision decision = RunResolver.resolve(text, model);
        if (decision.isNotFound()) {
            String trimmed = text.trim();
            statusText = trimmed.isEmpty() ? HINT : "Not found: " + trimmed;
            repaint();
            return false;
        }
        history.add(text);
        if (store != null) {
            store.save(history);
        }
        historyIndex = -1;
        if (runner != null) {
            runner.accept(decision);
        }
        close();
        return true;
    }

    /** Moves one step back through the history (older entries). */
    public void recallOlder() {
        List<String> entries = history.entries();
        if (entries.isEmpty()) {
            return;
        }
        historyIndex = Math.min(historyIndex + 1, entries.size() - 1);
        text = entries.get(historyIndex);
        repaint();
    }

    /** Moves one step forward through the history (newer entries). */
    public void recallNewer() {
        List<String> entries = history.entries();
        if (entries.isEmpty() || historyIndex < 0) {
            return;
        }
        historyIndex--;
        text = (historyIndex < 0) ? "" : entries.get(historyIndex);
        repaint();
    }

    /** Invokes the hide callback (Escape or a successful submit). */
    public void close() {
        if (onClose != null) {
            onClose.run();
        }
    }

    // ------------------------------------------------------------------ paint

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font font = resolveFont();
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            int w = getWidth();
            int h = getHeight();

            Composite saved = g2.getComposite();
            g2.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, 0.90f));
            g2.setColor(CARD);
            g2.fillRoundRect(0, 0, w, h, ARC_PX, ARC_PX);
            g2.setComposite(saved);
            g2.setColor(CARD_BORDER);
            g2.drawRoundRect(0, 0, w - 1, h - 1, ARC_PX, ARC_PX);

            int x = PAD_PX;
            int y = PAD_PX;
            g2.setColor(TITLE_FG);
            g2.drawString(TITLE, x, y + fm.getAscent());
            y += TITLE_H_PX;

            int fieldW = w - 2 * PAD_PX;
            g2.setColor(FIELD_BG);
            g2.fillRoundRect(x, y, fieldW, FIELD_H_PX, 8, 8);
            g2.setColor(FIELD_BORDER);
            g2.drawRoundRect(x, y, fieldW, FIELD_H_PX, 8, 8);
            g2.setColor(TEXT);
            String shown = ellipsize(text, fm, fieldW - 12);
            int textY = y + (FIELD_H_PX - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(shown, x + 6, textY);
            int caretX = x + 6 + fm.stringWidth(shown);
            g2.drawLine(caretX + 2, y + 6, caretX + 2, y + FIELD_H_PX - 6);
            y += FIELD_H_PX + GAP_PX;

            g2.setColor(TEXT_DIM);
            g2.drawString(ellipsize(statusText, fm, w - 2 * PAD_PX), x,
                    y + fm.getAscent());
        } finally {
            g2.dispose();
        }
    }

    /** Truncates {@code text} with an ellipsis so it fits {@code maxWidth}. */
    static String ellipsize(String text, FontMetrics fm, int maxWidth) {
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
     * The component font, defensively: a peer-less panel in a headless test
     * returns null from {@link #getFont()}. Falls back to a plain dialog font.
     */
    private Font resolveFont() {
        Font font = getFont();
        return (font != null) ? font : new Font(Font.DIALOG, Font.PLAIN, 13);
    }
}
