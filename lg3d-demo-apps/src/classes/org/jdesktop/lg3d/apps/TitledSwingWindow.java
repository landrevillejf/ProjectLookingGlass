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
package org.jdesktop.lg3d.apps;

import javax.swing.JPanel;
import javax.swing.UIManager;
import org.jdesktop.lg3d.sg.Transform3D;
import org.jdesktop.lg3d.sg.TransformGroup;
import org.jdesktop.lg3d.utils.eventaction.Component3DMover;
import org.jdesktop.lg3d.utils.shape.GlassyPanel;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.utils.shape.SimpleAppearance;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Cursor3D;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.SwingNode;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jogamp.vecmath.Color4f;
import org.jogamp.vecmath.Vector3f;

/**
 * Presents a conventional Swing {@link JPanel} as an integrated 3D desktop
 * window: a {@link Frame3D} that hosts the panel on a {@link SwingNode} below a
 * real title bar.
 *
 * <p>Why this exists: a bare {@code SwingNode} fills the whole {@code Frame3D}
 * with one opaque, pickable quad. Because lg3d mouse events do <em>not</em>
 * propagate to ancestor components by default
 * ({@link Component3D#setMouseEventPropagatable}), that quad becomes the event
 * source for the entire window - it swallows the BUTTON1 drag that
 * {@code ZLayeredMovableLayout}'s {@link Component3DMover} needs (so the window
 * cannot be moved) and it covers the auto-attached minimize / maximize / close
 * buttons that {@code Frame3DWindowDecoration} places at the top-right corner
 * (so they are neither visible nor clickable).</p>
 *
 * <p>This helper reserves a title-bar strip at the top of the frame, shifts the
 * Swing content down into the remaining area, and makes the strip a pickable
 * drag handle wired straight to the frame. The result is a window that stays
 * part of the 3D desktop yet behaves like a conventional one: drag the title
 * bar to move it, and the standard window buttons sit in the clear strip.</p>
 */
public final class TitledSwingWindow {

    /** Height of the title-bar strip in physical (world) units. */
    private static final float TITLE_BAR_HEIGHT = 0.012f;
    /** Thickness of the title-bar panel. */
    private static final float TITLE_BAR_DEPTH = 0.004f;

    private TitledSwingWindow() {
    }

    /**
     * Installs the platform (system) Swing look-and-feel so hosted panels
     * render as conventional desktop UIs instead of with the default
     * cross-platform Metal look. Call <em>before</em> constructing the panel so
     * its child components are created with the right UI delegates. A failure
     * leaves the current look-and-feel untouched.
     */
    public static void installNativeLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Keep whatever look-and-feel is already active.
        }
    }

    /**
     * Builds, shows and returns a titled 3D window hosting {@code panel}.
     *
     * @param title    the text shown in the title bar (and the frame name)
     * @param panel    the Swing content, already constructed
     * @param widthPx  the panel width in native pixels
     * @param heightPx the panel height in native pixels
     */
    public static Frame3D show(String title, JPanel panel, int widthPx, int heightPx) {
        Toolkit3D tk = Toolkit3D.getToolkit3D();
        float contentW = tk.widthNativeToPhysical(widthPx);
        float contentH = tk.heightNativeToPhysical(heightPx);

        SwingNode node = new SwingNode();
        node.setJPanel(panel);
        node.setTransparency(0.0f);
        // Shift the Swing quad down so it fills only the content area, leaving
        // the top strip clear for the title bar and the window buttons.
        node.setTranslation(0.0f, -TITLE_BAR_HEIGHT * 0.5f, 0.0f);

        Frame3D frame = new Frame3D();
        frame.setName(title);
        frame.addChild(node);

        Component3D titleBar = buildTitleBar(title, contentW, contentH);
        // Dragging the title bar moves the whole window. The mover targets the
        // frame explicitly, so it works even though events do not propagate.
        titleBar.addListener(new Component3DMover(frame));
        frame.addChild(titleBar);

        // The frame is content + title bar; Frame3DWindowDecoration (attached
        // during changeEnabled) reads this size and lands its min/max/close
        // buttons in the title strip.
        frame.setPreferredSize(
                new Vector3f(contentW, contentH + TITLE_BAR_HEIGHT, 0.01f));
        frame.changeEnabled(true);
        frame.changeVisible(true);
        return frame;
    }

    private static Component3D buildTitleBar(String title, float width, float contentH) {
        Component3D bar = new Component3D();

        SimpleAppearance barApp = new SimpleAppearance(
                0.35f, 0.50f, 0.75f, 0.95f, SimpleAppearance.DISABLE_CULLING);
        bar.addChild(new GlassyPanel(width, TITLE_BAR_HEIGHT, TITLE_BAR_DEPTH, barApp));

        // Title text: GlassyText2D grows upward from its origin (LEFT align), so
        // offset by -textHeight/2 to centre it vertically in the strip, and sit
        // it just in front of the bar panel.
        float textH = TITLE_BAR_HEIGHT * 0.5f;
        GlassyText2D label = new GlassyText2D(
                title, width * 0.6f, textH, new Color4f(0.95f, 0.97f, 1.0f, 1.0f));
        Transform3D t3d = new Transform3D();
        t3d.set(new Vector3f(
                -width * 0.5f + TITLE_BAR_HEIGHT * 0.4f,
                -textH * 0.5f,
                TITLE_BAR_DEPTH * 0.5f + 0.001f));
        TransformGroup tg = new TransformGroup(t3d);
        tg.addChild(label);
        bar.addChild(tg);

        // Centre of the top strip of a frame whose total height is
        // contentH + TITLE_BAR_HEIGHT is y == contentH / 2.
        bar.setTranslation(0.0f, contentH * 0.5f, 0.0f);
        bar.setCursor(Cursor3D.MOVE_CURSOR);
        return bar;
    }
}
