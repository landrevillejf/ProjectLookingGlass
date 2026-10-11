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
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.JComponent;
import javax.swing.JInternalFrame;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;

/**
 * Renders the 2D desktop's user-settable surface opacity, the engine behind the
 * four {@link TransparencySurface} settings.
 *
 * <p>Fading a Swing component is not a matter of turning down its background
 * alpha. A window hosts third-party panels that fill themselves opaque, and the
 * taskbar paints over a wallpaper it does not own, so the shell - which is the
 * only party that knows what is behind - has to do the fading. It does so by
 * letting the component paint into a transparent scratch image and blitting that
 * image back with {@link AlphaComposite}: the component keeps painting exactly
 * as it always did, every pixel it produces keeps its own contrast, and the
 * wallpaper shows through the gap left by the missing alpha. That works with any
 * look-and-feel and needs no change inside the components being faded.</p>
 *
 * <p>The engine caches the four opacities in memory, refreshed from
 * {@link DesktopConfig} on the EDT at shell start-up and on every live apply
 * ({@link #refresh()}). Paint code reads the cache, never the preferences: a
 * headless JVM, a running 3D desktop or a unit test that does not isolate
 * {@code java.util.prefs} all keep the default - fully solid - instead of
 * touching the preference store from a paint pass.</p>
 *
 * <p>At the default of 100 percent nothing is composited at all; callers are
 * expected to short-circuit on {@link #isSolid(JComponent)}, which keeps the
 * legacy paint path byte-identical and costs nothing for users who never open
 * the setting.</p>
 */
public final class DesktopTransparency {

    /**
     * Scratch images keyed by component. Weak references so a closed window or
     * a discarded widget releases its buffer with it; the key is the component
     * itself, so a resized buffer is simply replaced on the next paint.
     */
    private static final WeakHashMap<JComponent, BufferedImage> SCRATCH =
            new WeakHashMap<>();

    /** Live opacities, one per surface. Read on the paint path, EDT-only writes. */
    private static final Map<TransparencySurface, Integer> OPACITY = new OpacityMap();

    /** Holds the defaults so an un-refreshed engine paints the solid legacy look. */
    private static final class OpacityMap
            extends EnumMap<TransparencySurface, Integer> {
        OpacityMap() {
            super(TransparencySurface.class);
            for (TransparencySurface s : TransparencySurface.values()) {
                put(s, DesktopConfig.DEFAULT_SURFACE_OPACITY);
            }
        }
    }

    private DesktopTransparency() {
    }

    // ------------------------------------------------------------------
    // Pure conversions (unit-testable headless, no Swing or prefs required)
    // ------------------------------------------------------------------

    /**
     * The alpha a surface painted at {@code opacityPercent} is blitted at:
     * {@code 100} is solid ({@code 1f}), {@code 0} invisible. Out-of-range input
     * is clamped rather than rejected, because the value can come from a stored
     * preference written by an older build.
     */
    public static float alphaFor(int opacityPercent) {
        return Math.max(0f, Math.min(100, opacityPercent)) / 100f;
    }

    /**
     * Whether {@code opacityPercent} means "paint nothing special" - the
     * pixel-identical legacy path, which every paint hook checks first.
     */
    public static boolean isSolid(int opacityPercent) {
        return opacityPercent >= DesktopConfig.MAX_SURFACE_OPACITY;
    }

    /**
     * {@code c} with its alpha scaled by {@code alpha}, keeping the colour
     * itself intact: {@code fade(Color.RED, 0.5f)} is still red, at half alpha.
     * Null passes through so paint hooks can chain it over an absent colour.
     */
    public static Color fade(Color c, float alpha) {
        if (c == null) {
            return null;
        }
        return new Color(c.getRed(), c.getGreen(), c.getBlue(),
                Math.round(c.getAlpha() * Math.max(0f, Math.min(1f, alpha))));
    }

    // ------------------------------------------------------------------
    // Live cache
    // ------------------------------------------------------------------

    /**
     * This surface's opacity as the shell is currently painting it, from the
     * in-memory cache. The value paint code reads; see {@link #refresh()} for
     * how it is kept in step with the persisted configuration.
     */
    public static int opacityOf(TransparencySurface surface) {
        return OPACITY.get(surface);
    }

    /** {@link #opacityOf(TransparencySurface)} turned into a blit alpha. */
    public static float alphaOf(TransparencySurface surface) {
        return alphaFor(opacityOf(surface));
    }

    /**
     * Whether every surface is solid, i.e. whether any painting has to be
     * composited at all. The desktop pane and the taskbar check this first so
     * the default configuration keeps the stock paint path - and the stock
     * pixels - untouched.
     */
    public static boolean allSolid() {
        for (TransparencySurface s : TransparencySurface.values()) {
            if (!isSolid(opacityOf(s))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes one surface into the paint cache without touching the preference
     * store, so a headless test or a preview can paint a faded desktop that is
     * never persisted. Production code goes through
     * {@link #applyOpacity(TransparencySurface, int)}, which stores the value
     * first; anything applied here is overwritten by the next {@link #refresh()}.
     */
    public static void setOpacityFor(TransparencySurface surface, int percent) {
        OPACITY.put(surface, DesktopConfig.clampOpacity(percent));
    }

    /**
     * Pulls the four persisted opacities into the paint cache. Must run on the
     * EDT; called by the 2D shell when it starts and from
     * {@link Desktop2D#applyDesktopConfig()}, which is what makes a control
     * center slider take effect without a restart. Reading
     * {@link DesktopConfig} is the only preferences access in this class, and it
     * deliberately never happens during a paint.
     */
    public static void refresh() {
        DesktopConfig cfg = DesktopConfig.get();
        for (TransparencySurface s : TransparencySurface.values()) {
            OPACITY.put(s, s.opacityOf(cfg));
        }
    }

    /**
     * Stores one surface's opacity in the persisted configuration and repaints
     * the shell so it shows immediately. The control center's single write path
     * for transparency: the save is what makes the choice survive a restart, and
     * {@link Desktop2D#applyDesktopConfig()} is a no-op when the 2D desktop is
     * not running, so the same call from a 3D session just stores the value for
     * the next 2D start-up.
     *
     * @param surface which surface to fade
     * @param percent opacity as a percent of solid, clamped by
     *                {@link DesktopConfig#MIN_SURFACE_OPACITY}
     */
    public static void applyOpacity(TransparencySurface surface, int percent) {
        DesktopConfig cfg = DesktopConfig.get();
        surface.setOpacityOf(cfg, percent);
        cfg.save();
        Desktop2D.applyDesktopConfig();
    }

    // ------------------------------------------------------------------
    // Faded painting
    // ------------------------------------------------------------------

    /**
     * Whether {@code child} is a desktop component that asks to be faded with one
     * of the user's settings, i.e. whether its parent has to route it through
     * {@link #paintFaded(Graphics2D, JComponent, float)} instead of painting it
     * the stock way. The opacity short-circuit lives here, so a surface left at
     * the default never reaches the compositing code.
     */
    public static boolean isFaded(Component child) {
        if (!(child instanceof TranslucentSurface)) {
            return false;
        }
        return !isSolid(opacityOf(((TranslucentSurface) child).transparencySurface()));
    }

    /**
     * Paints {@code child} into {@code g} at {@code alpha} instead of letting it
     * paint directly. The child is rendered whole into a transparent scratch
     * image - at its own bounds, offset by its location in the parent - and that
     * image is blitted back with {@link AlphaComposite#SRC_OVER}, so what shows
     * through the difference is whatever the parent had already painted.
     *
     * <p>Must run on the EDT, like all painting. The caller's clip and rendering
     * hints are carried into the scratch image so the child cannot tell it is
     * being composited; antialiasing in particular stays as the parent set it.</p>
     *
     * @param g     the parent's graphics, in the parent's coordinate space
     * @param child the component to fade; skipped when it is not showing or, for
     *              an internal frame, when it is iconified - a minimised frame is
     *              painted by its desktop icon, not here
     * @param alpha blit alpha in {@code 0..1}. {@code >= 1} paints the child
     *              straight onto {@code g}, so the caller may use this method
     *              unconditionally; {@code <= 0} paints nothing at all
     */
    public static void paintFaded(Graphics2D g, JComponent child, float alpha) {
        if (!child.isShowing() || isIconified(child)) {
            return;
        }
        int w = child.getWidth();
        int h = child.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (alpha >= 1f) {
            // Solid: the stock Swing child-paint idiom - graphics moved into
            // the child's own coordinate space, then a plain paint().
            Graphics2D gt = (Graphics2D) g.create();
            try {
                gt.translate(child.getX(), child.getY());
                child.paint(gt);
            } finally {
                gt.dispose();
            }
            return;
        }
        if (alpha <= 0f) {
            return;
        }
        BufferedImage buf = scratchFor(child, w, h);
        Graphics2D g2 = buf.createGraphics();
        try {
            // Clear first: a scratch image reused across paints must not carry
            // the previous frame's pixels into the new composite.
            g2.setComposite(AlphaComposite.Clear);
            g2.fillRect(0, 0, w, h);
            g2.setComposite(AlphaComposite.SrcOver);
            g2.setClip(g.getClip());
            copyHints(g, g2);
            child.paint(g2);
        } finally {
            g2.dispose();
        }
        Composite saved = g.getComposite();
        try {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g.drawImage(buf, child.getX(), child.getY(), null);
        } finally {
            g.setComposite(saved);
        }
    }

    /** A scratch buffer of {@code w}x{@code h} for {@code child}, reused if it fits. */
    private static BufferedImage scratchFor(JComponent child, int w, int h) {
        BufferedImage buf = SCRATCH.get(child);
        if (buf == null || buf.getWidth() != w || buf.getHeight() != h) {
            buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            SCRATCH.put(child, buf);
        }
        return buf;
    }

    /** Carries the caller's rendering hints into the scratch image. */
    private static void copyHints(Graphics2D from, Graphics2D to) {
        to.addRenderingHints(from.getRenderingHints());
    }

    /**
     * Whether {@code child} is a minimised window, which the desktop manager
     * paints through its {@code DesktopIcon} rather than in place; fading it
     * here would either paint nothing or draw the frame over its own icon.
     */
    private static boolean isIconified(Component child) {
        return child instanceof JInternalFrame
                && ((JInternalFrame) child).isIcon();
    }
}
