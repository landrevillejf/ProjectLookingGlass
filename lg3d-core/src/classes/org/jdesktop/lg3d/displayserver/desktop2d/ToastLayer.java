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
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;
import javax.swing.JComponent;
import javax.swing.JDesktopPane;
import javax.swing.Timer;

/**
 * The translucent overlay that pops transient notification toasts up in the
 * bottom-right of the desktop, the 2D counterpart of a system notification
 * bubble. It is the view over a {@link ToastQueue}: notifications are pushed
 * here, stack upward from the bottom-right corner, fade on their own once their
 * time-to-live elapses, and dismiss on a click.
 *
 * <p>The overlay spans the whole desktop pane (so it never needs re-laying-out
 * as the stack grows) but paints nothing while idle and, crucially, overrides
 * {@link #contains(int, int)} to claim only the pixels an actual toast covers.
 * Every other click falls through to the application windows below, so a toast
 * lingering for a few seconds never steals input from the desktop.</p>
 *
 * <p>It lives on the desktop pane's {@link JDesktopPane#POPUP_LAYER}, above the
 * application windows, alongside the window-switcher overlay. All the geometry
 * and text layout is in static, clock-free methods so it is unit-testable
 * headless; the live clock is injectable for the same reason.</p>
 */
final class ToastLayer extends JComponent {

    /** Width of a toast card. */
    static final int TOAST_WIDTH = 300;
    /** Height of a toast card. */
    static final int TOAST_HEIGHT = 72;
    /** Vertical gap between stacked toasts. */
    static final int GAP = 8;
    /** Margin from the desktop's right and bottom edges. */
    static final int MARGIN = 14;

    /** Corner arc of a toast card. */
    static final int ARC = 16;
    /** Inner text padding. */
    static final int PAD = 12;
    /** Width of the severity accent band on the card's left edge. */
    static final int ACCENT_BAR = 5;
    /** How often the overlay re-checks the queue for expired toasts. */
    static final int REFRESH_MS = 200;
    /** Most lines a toast message is wrapped to before it is ellipsised. */
    static final int MESSAGE_LINES = 2;

    private static final Color CARD = new Color(20, 24, 32);
    private static final Color CARD_BORDER = new Color(120, 160, 220);
    private static final Color TITLE = Color.WHITE;
    private static final Color BODY = new Color(200, 208, 220);
    private static final float CARD_ALPHA = 0.92f;

    private final ToastQueue queue;
    private final LongSupplier clock;
    private final Timer refreshTimer;

    private JDesktopPane host;
    /** The toasts currently painted, index 0 the newest (bottom-most). */
    private List<Notification> visible = Collections.emptyList();

    /** An overlay over {@code queue} on the wall clock. */
    ToastLayer(ToastQueue queue) {
        this(queue, System::currentTimeMillis);
    }

    /**
     * @param queue the transient toasts this layer paints
     * @param clock supplies "now" for expiry; injectable so tests are
     *              deterministic
     */
    ToastLayer(ToastQueue queue, LongSupplier clock) {
        this.queue = queue;
        this.clock = (clock == null) ? System::currentTimeMillis : clock;
        setOpaque(false);
        setVisible(false);
        this.refreshTimer = new Timer(REFRESH_MS, e -> refresh());
        this.refreshTimer.setRepeats(true);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dismissAt(e.getX(), e.getY());
            }
        });
    }

    /**
     * Installs the overlay on {@code desktop}'s popup layer, above the
     * application windows. Safe to call once from the desktop constructor.
     */
    void install(JDesktopPane desktop) {
        this.host = desktop;
        setBounds(0, 0, desktop.getWidth(), desktop.getHeight());
        desktop.add(this, JDesktopPane.POPUP_LAYER);
    }

    /** Removes the overlay and stops its refresh timer. */
    void uninstall() {
        refreshTimer.stop();
        if (host != null) {
            host.remove(this);
            host.revalidate();
            host.repaint();
            host = null;
        }
    }

    /**
     * Queues {@code notification} as a toast and starts tracking expiry. A null
     * notification is ignored.
     */
    void show(Notification notification) {
        if (notification == null) {
            return;
        }
        queue.push(notification, clock.getAsLong());
        refreshTimer.start();
        refresh();
    }

    /** Drops expired toasts, syncs visibility to what remains and repaints. */
    private void refresh() {
        visible = queue.visible(clock.getAsLong());
        boolean any = !visible.isEmpty();
        if (host != null) {
            setBounds(0, 0, host.getWidth(), host.getHeight());
        }
        setVisible(any);
        if (any) {
            repaint();
        } else {
            refreshTimer.stop();
        }
    }

    private void dismissAt(int x, int y) {
        int index = toastIndexAt(x, y);
        if (index >= 0) {
            queue.dismiss(visible.get(index).id());
            refresh();
        }
    }

    private int toastIndexAt(int x, int y) {
        int width = getWidth();
        int height = getHeight();
        for (int i = 0; i < visible.size(); i++) {
            if (toastBounds(width, height, i).contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Claims only the pixels a visible toast covers, so clicks anywhere else on
     * this full-pane overlay fall through to the windows below.
     */
    @Override
    public boolean contains(int x, int y) {
        if (!isVisible() || visible.isEmpty()) {
            return false;
        }
        return toastIndexAt(x, y) >= 0;
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (visible.isEmpty()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            render(g2, visible, getWidth(), getHeight(), resolveFont());
        } finally {
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------
    // Static, clock-free geometry and rendering (unit-testable headless)
    // ------------------------------------------------------------------

    /**
     * The card rectangle for the toast at stack position {@code index} (0 is the
     * newest, drawn bottom-most), stacked upward from the bottom-right corner.
     * Clamped so a card never starts off the top-left of the pane.
     */
    static Rectangle toastBounds(int hostWidth, int hostHeight, int index) {
        int x = Math.max(0, hostWidth - MARGIN - TOAST_WIDTH);
        int y = hostHeight - MARGIN - TOAST_HEIGHT - index * (TOAST_HEIGHT + GAP);
        return new Rectangle(x, Math.max(0, y), TOAST_WIDTH, TOAST_HEIGHT);
    }

    /**
     * Paints {@code toasts} into {@code g2} for a pane of the given size, index
     * 0 (the newest) last so it lands on top. Pure: no field or peer access, so
     * a test can drive it straight into a {@link java.awt.image.BufferedImage}.
     */
    static void render(Graphics2D g2, List<Notification> toasts, int hostWidth,
                       int hostHeight, Font base) {
        if (toasts == null || toasts.isEmpty()) {
            return;
        }
        Font font = (base != null) ? base : new Font(Font.DIALOG, Font.PLAIN, 13);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font titleFont = font.deriveFont(Font.BOLD, font.getSize2D() + 1f);
        for (int i = toasts.size() - 1; i >= 0; i--) {
            paintToast(g2, toasts.get(i), toastBounds(hostWidth, hostHeight, i),
                    font, titleFont);
        }
    }

    private static void paintToast(Graphics2D g2, Notification n, Rectangle b,
                                   Font bodyFont, Font titleFont) {
        Composite saved = g2.getComposite();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                CARD_ALPHA));
        // Accent-filled card, then the dark body inset from the left so a thin
        // severity band shows along the left edge.
        g2.setColor(NotificationColors.accentFor(n.kind()));
        g2.fillRoundRect(b.x, b.y, b.width, b.height, ARC, ARC);
        g2.setColor(CARD);
        g2.fillRoundRect(b.x + ACCENT_BAR, b.y, b.width - ACCENT_BAR, b.height,
                ARC, ARC);
        g2.setComposite(saved);
        g2.setColor(CARD_BORDER);
        g2.drawRoundRect(b.x, b.y, b.width, b.height, ARC, ARC);

        int textX = b.x + ACCENT_BAR + PAD;
        int textWidth = b.width - ACCENT_BAR - PAD * 2;

        g2.setFont(titleFont);
        FontMetrics tfm = g2.getFontMetrics(titleFont);
        g2.setColor(TITLE);
        int titleY = b.y + PAD + tfm.getAscent();
        g2.drawString(ellipsize(n.title(), tfm, textWidth), textX, titleY);

        String message = n.message();
        if (message != null) {
            g2.setFont(bodyFont);
            FontMetrics bfm = g2.getFontMetrics(bodyFont);
            g2.setColor(BODY);
            int lineY = titleY + tfm.getDescent() + bfm.getAscent() + 2;
            for (String line : wrap(message, bfm, textWidth, MESSAGE_LINES)) {
                g2.drawString(line, textX, lineY);
                lineY += bfm.getHeight();
            }
        }
    }

    /**
     * Shortens {@code text} with a trailing ellipsis so it fits {@code maxWidth}
     * pixels. Null becomes empty; text that already fits is returned unchanged.
     */
    static String ellipsize(String text, FontMetrics fm, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (maxWidth <= 0 || fm.stringWidth(text) <= maxWidth) {
            return text;
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
     * Greedily wraps {@code text} on whitespace into at most {@code maxLines}
     * lines no wider than {@code maxWidth} pixels, ellipsising whatever spills
     * past the last line. Blank text or a non-positive line cap yields an empty
     * list.
     */
    static List<String> wrap(String text, FontMetrics fm, int maxWidth,
                             int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank() || maxLines < 1) {
            return lines;
        }
        String[] words = text.trim().split("\\s+");
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (lines.size() == maxLines - 1) {
                // Final line: fold in every remaining word, ellipsised.
                StringBuilder rest = new StringBuilder(line);
                for (int j = i; j < words.length; j++) {
                    if (rest.length() > 0) {
                        rest.append(' ');
                    }
                    rest.append(words[j]);
                }
                lines.add(ellipsize(rest.toString(), fm, maxWidth));
                return lines;
            }
            String word = words[i];
            String candidate = (line.length() == 0) ? word : line + " " + word;
            if (fm.stringWidth(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
            } else {
                if (line.length() > 0) {
                    lines.add(ellipsize(line.toString(), fm, maxWidth));
                    line.setLength(0);
                }
                line.append(word);
            }
        }
        if (line.length() > 0) {
            lines.add(ellipsize(line.toString(), fm, maxWidth));
        }
        return lines;
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
}
