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
package org.jdesktop.lg3d.widgets.hud;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.jdesktop.lg3d.displayserver.desktop2d.Notification;
import org.jdesktop.lg3d.displayserver.desktop2d.ToastQueue;
import org.jdesktop.lg3d.scenemanager.utils.hud.DesktopHudLayer;
import org.jdesktop.lg3d.scenemanager.utils.hud.NotificationService;
import org.jdesktop.lg3d.wg.Container3D;

/**
 * The 3D desktop's notification toast stack: a pool of up to
 * {@link ToastQueue#DEFAULT_MAX_VISIBLE} {@link ToastCard3D} cards anchored in the
 * bottom-right of the front-most {@link DesktopHudLayer}, newest at the bottom and
 * stacked upward. It is the native counterpart of the 2D/Swing desktop's
 * {@code ToastLayer}.
 *
 * <p>Cards are fixed-size and pooled, so the stack never resizes a live texture:
 * a refresh simply reassigns notifications to cards, toggles their visibility and
 * repositions them. A repeating EDT {@link Timer} polls
 * {@link NotificationService#visibleToasts()} (which evicts expired toasts, giving
 * the auto-fade), and a service listener triggers an immediate refresh when a
 * notification is posted or dismissed from any thread.</p>
 */
public class ToastOverlay3D extends Container3D {
    private static final Logger logger = Logger.getLogger("lg.hud.toast");

    /** Poll interval; also the granularity of the toast auto-fade. */
    static final int REFRESH_MS = 200;

    /** Bottom-right anchor margins and inter-card gap, in world units. */
    static final float RIGHT_MARGIN = 0.02f;
    static final float BOTTOM_MARGIN = 0.06f;   // clears the taskbar strip
    static final float GAP = 0.008f;

    private final NotificationService service;
    private final DesktopHudLayer layer;
    private final ToastCard3D[] cards;
    private final Timer timer;
    private final Runnable serviceListener = this::refreshAsync;

    public ToastOverlay3D(NotificationService service, DesktopHudLayer layer) {
        this.service = service;
        this.layer = layer;
        setName("ToastOverlay3D");

        int max = ToastQueue.DEFAULT_MAX_VISIBLE;
        this.cards = new ToastCard3D[max];
        for (int i = 0; i < max; i++) {
            cards[i] = new ToastCard3D();
            addChild(cards[i]);
        }
        // A toast stack must never block clicks to the desktop behind it.
        setMouseEventEnabled(false);

        this.timer = new Timer(REFRESH_MS, e -> refresh());
        this.timer.setRepeats(true);
    }

    /** Begins polling and listening for notifications. */
    public void start() {
        service.addListener(serviceListener);
        timer.start();
        refresh();
    }

    /** Stops polling and listening; the cards stay hidden. */
    public void stop() {
        timer.stop();
        service.removeListener(serviceListener);
    }

    /** Releases the pooled cards' SwingNode resources. */
    public void dispose() {
        stop();
        for (ToastCard3D card : cards) {
            if (card != null) {
                card.dispose();
            }
        }
    }

    /** Schedules a refresh on the EDT (safe to call from any thread). */
    void refreshAsync() {
        if (SwingUtilities.isEventDispatchThread()) {
            refresh();
        } else {
            SwingUtilities.invokeLater(this::refresh);
        }
    }

    /** Reconciles the card pool with the currently visible toasts. Runs on the EDT. */
    void refresh() {
        try {
            List<Notification> visible = service.visibleToasts();
            float screenW = (layer != null) ? layer.screenWidth() : 0f;
            float screenH = (layer != null) ? layer.screenHeight() : 0f;
            for (int i = 0; i < cards.length; i++) {
                ToastCard3D card = cards[i];
                if (i < visible.size()) {
                    card.setNotification(visible.get(i));
                    float cw = card.cardWidth();
                    float ch = card.cardHeight();
                    if (cw > 0f && ch > 0f && screenW > 0f && screenH > 0f) {
                        float[] pos = cardPosition(i, cw, ch, screenW, screenH,
                                RIGHT_MARGIN, BOTTOM_MARGIN, GAP);
                        card.setTranslation(pos[0], pos[1], 0f);
                    }
                    card.setVisible(true);
                } else {
                    card.setVisible(false);
                }
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, "toast refresh failed", t);
        }
    }

    /**
     * Pure bottom-right stacking layout: the world position of card {@code index}
     * (0 = newest, at the bottom) for a card of size {@code (cardW, cardH)} on a
     * screen of size {@code (screenW, screenH)}. The scene origin is the screen
     * centre with +x right and +y up, so the anchor is the bottom-right corner
     * inset by {@code rightMargin}/{@code bottomMargin}, and each older card is
     * lifted by {@code cardH + gap}. Returns {@code {x, y}}.
     */
    static float[] cardPosition(int index, float cardW, float cardH,
            float screenW, float screenH,
            float rightMargin, float bottomMargin, float gap) {
        int i = Math.max(0, index);
        float x = screenW * 0.5f - rightMargin - cardW * 0.5f;
        float y = -screenH * 0.5f + bottomMargin + cardH * 0.5f + i * (cardH + gap);
        return new float[] { x, y };
    }
}
