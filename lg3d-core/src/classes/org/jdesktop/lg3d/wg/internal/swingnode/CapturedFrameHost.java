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
package org.jdesktop.lg3d.wg.internal.swingnode;

import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.swing.JFrame;
import javax.swing.JPanel;
import org.jdesktop.lg3d.scenemanager.utils.decoration.Frame3DWindowDecoration;
import org.jdesktop.lg3d.sg.Shape3D;
import org.jdesktop.lg3d.sg.Texture2D;
import org.jdesktop.lg3d.sg.Transform3D;
import org.jdesktop.lg3d.sg.TransformGroup;
import org.jdesktop.lg3d.utils.action.ActionBoolean;
import org.jdesktop.lg3d.utils.eventadapter.Component3DParkedEventAdapter;
import org.jdesktop.lg3d.utils.shape.FuzzyEdgePanel;
import org.jdesktop.lg3d.utils.shape.GlassyPanel;
import org.jdesktop.lg3d.utils.shape.GlassyText2D;
import org.jdesktop.lg3d.utils.shape.RectShadow;
import org.jdesktop.lg3d.utils.shape.SimpleAppearance;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Cursor3D;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.SwingNode;
import org.jdesktop.lg3d.wg.Thumbnail;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Color4f;
import org.jogamp.vecmath.Vector3f;

/**
 * Presents a conventional Swing application's own {@link JFrame} as an integrated
 * lg3d desktop window, with ZERO changes to the application. When the global
 * {@link SwingNodeWindowCapture} hook sees a new {@code JFrame} open, it calls
 * {@link #present(JFrame)}: this builds a {@link Frame3D} + {@link SwingNode}
 * whose texture is the frame's live content, gives it the standard window chrome
 * (title bar with the frame's title, spine titles, minimize/maximize/close via
 * {@link Frame3DWindowDecoration}, a taskbar thumbnail), and keeps the two in
 * sync (resize, title, close).
 *
 * <p>Mechanics: the {@code SwingNode} hosts an empty transparent {@code JPanel}
 * sized to the frame, and the real frame is registered as a <em>full-bleed</em>
 * capture, so {@code SwingNode.captureNow()} paints the frame as the entire
 * texture and the node's input forwarding routes clicks/keys straight to the
 * frame's real components. The real frame is parked off-screen by the capture
 * layer, so no host window is ever visible.
 *
 * <p>Closing either side closes the other: the decoration's close button calls
 * {@code Frame3D.changeEnabled(false)}, which this host observes (via a
 * {@code Frame3D} subclass) to dispose the real frame; and the app disposing its
 * own frame removes the 3D window.
 *
 * <p>The window chrome helpers mirror {@code org.jdesktop.lg3d.apps.TitledSwingWindow}
 * (in the demo-apps module, which core cannot depend on), so they are replicated
 * here against the same core shape/appearance utilities.
 *
 * <p>This class is an implementation detail and must not be instantiated by
 * users.
 */
public final class CapturedFrameHost {

    private static final Logger logger =
            Logger.getLogger("lg.wg.swingnode");

    /** Height of the title-bar strip in physical (world) units. */
    private static final float TITLE_BAR_HEIGHT = 0.012f;
    /** Thickness of the title-bar panel. */
    private static final float TITLE_BAR_DEPTH = 0.004f;
    /** Glyph height of the vertical edge ("spine") titles. */
    private static final float SPINE_GLYPH_HEIGHT = 0.006f;
    /** Lift that keeps the spine quad just outside the backdrop side face. */
    private static final float SPINE_MARGIN = 0.0002f;
    /** Taskbar thumbnail scale (matches DefaultThumbnail / native windows). */
    private static final float THUMBNAIL_SCALE = 0.13f;
    /** Fuzzy border of the thumbnail body (pre-scaled). */
    private static final float THUMBNAIL_DECO = 0.005f * THUMBNAIL_SCALE;
    /** Thickness of the thumbnail glass slab, pre-scaled. */
    private static final float THUMBNAIL_DEPTH = 0.02f * THUMBNAIL_SCALE;

    private CapturedFrameHost() {
    }

    /**
     * Builds and shows a desktop window presenting {@code f}. Must be called on
     * the EDT (the capture hook dispatches there). Idempotency is guaranteed by
     * the caller ({@link SwingNodeWindowCapture} only calls this once per frame).
     */
    public static void present(final JFrame f) {
        if (f == null) {
            return;
        }
        final Toolkit3D tk = Toolkit3D.getToolkit3D();
        Dimension size = contentSize(f);
        int widthPx = Math.max(1, size.width);
        int heightPx = Math.max(1, size.height);
        float contentW = tk.widthNativeToPhysical(widthPx);
        float contentH = tk.heightNativeToPhysical(heightPx);

        // Empty transparent host panel sized to the frame. The real frame is
        // painted full-bleed into the node's texture by the capture layer, so the
        // panel itself only fixes the texture size and the 3D quad dimensions.
        final JPanel hostPanel = new JPanel(null);
        hostPanel.setOpaque(false);
        hostPanel.setSize(widthPx, heightPx);
        hostPanel.setPreferredSize(new Dimension(widthPx, heightPx));

        final SwingNode node = new SwingNode();
        node.setJPanel(hostPanel);
        node.setTransparency(0.0f);
        // Shift the Swing quad down so it fills only the content area, leaving
        // the top strip clear for the title bar and the window buttons.
        node.setTranslation(0.0f, -TITLE_BAR_HEIGHT * 0.5f, 0.0f);

        // Paint the real frame as the entire texture and route input to it.
        SwingNodeWindowCapture.registerFullBleed(node, f);

        final AtomicBoolean disposed = new AtomicBoolean(false);
        final String title = title(f);

        // Subclass Frame3D only to observe the close: the standard decoration's
        // close button calls changeEnabled(false), which funnels through
        // setEnabledInternal(false). Dispose the real frame there so the app can
        // clean up, exactly as if the user had closed its own window.
        final Frame3D frame = new Frame3D() {
            protected void setEnabledInternal(boolean enabled) {
                super.setEnabledInternal(enabled);
                if (!enabled && disposed.compareAndSet(false, true)) {
                    try {
                        f.dispose();
                    } catch (Throwable ignore) {
                        // The app may already be tearing the frame down.
                    }
                    node.dispose();
                }
            }
        };
        frame.setName(title);
        frame.addChild(node);

        Component3D titleBar = buildTitleBar(title, contentW, contentH);
        // Make the title bar the window's gesture handle (propagatable) so the
        // frame-level move/rotate/flip listeners still receive drags picked on
        // it, while the non-propagatable Swing quad keeps its own input.
        titleBar.setMouseEventPropagatable(true);
        frame.addChild(titleBar);
        frame.addChild(buildSpineTitle(title, contentW, contentH, -1));
        frame.addChild(buildSpineTitle(title, contentW, contentH, +1));

        // Click-to-unpark parity with native windows: while parked on the
        // bookshelf the Swing quad must be propagatable so a click reaches the
        // frame and unparks it; on unpark it returns to non-propagatable.
        frame.addListener(new Component3DParkedEventAdapter(
            new ActionBoolean() {
                public void performAction(LgEventSource source, boolean parked) {
                    node.setMouseEventPropagatable(parked);
                }
            }));

        frame.setThumbnail(buildThumbnail(node, contentW, contentH));
        frame.setPreferredSize(
                new Vector3f(contentW, contentH + TITLE_BAR_HEIGHT, 0.01f));
        frame.changeEnabled(true);
        frame.changeVisible(true);

        // ---- live sync from the real JFrame ----------------------------
        f.addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) {
                if (disposed.get()) {
                    return;
                }
                Dimension d = contentSize(f);
                int w = Math.max(1, d.width);
                int h = Math.max(1, d.height);
                hostPanel.setSize(w, h);
                hostPanel.setPreferredSize(new Dimension(w, h));
                hostPanel.revalidate();
                float cw = tk.widthNativeToPhysical(w);
                float ch = tk.heightNativeToPhysical(h);
                frame.setPreferredSize(
                        new Vector3f(cw, ch + TITLE_BAR_HEIGHT, 0.01f));
                node.requestRecapture();
            }
        });
        f.addPropertyChangeListener("title", new PropertyChangeListener() {
            public void propertyChange(PropertyChangeEvent e) {
                if (!disposed.get()) {
                    frame.setName(title(f));
                }
            }
        });
        f.addWindowListener(new WindowAdapter() {
            public void windowClosed(WindowEvent e) {
                if (disposed.compareAndSet(false, true)) {
                    frame.changeEnabled(false);
                    node.dispose();
                }
            }
        });

        logger.fine("Presented captured JFrame as a desktop window: " + title);
    }

    private static String title(JFrame f) {
        String t = f.getTitle();
        return (t == null || t.trim().isEmpty()) ? "Application" : t;
    }

    /**
     * The size of the frame's <em>content</em> (its root pane), not its outer
     * bounds. The capture layer paints the root pane into the texture and hides
     * the real window, so the 3D quad must match the content area; using the
     * outer frame size would leave an OS-decoration-sized blank strip.
     */
    private static Dimension contentSize(JFrame f) {
        java.awt.Container rp = f.getRootPane();
        if (rp != null && rp.getWidth() > 0 && rp.getHeight() > 0) {
            return rp.getSize();
        }
        return f.getSize();
    }

    // ------------------------------------------------------------------
    // Window chrome (mirrors org.jdesktop.lg3d.apps.TitledSwingWindow)
    // ------------------------------------------------------------------

    private static Thumbnail buildThumbnail(
            SwingNode node, float contentW, float contentH) {
        final float width = contentW * THUMBNAIL_SCALE;
        final float height = contentH * THUMBNAIL_SCALE;

        Thumbnail thumbnail = new Thumbnail();

        GlassyPanel deco = new GlassyPanel(
                width + THUMBNAIL_DECO * 2,
                height + THUMBNAIL_DECO * 2,
                THUMBNAIL_DEPTH,
                new SimpleAppearance(
                        0.6f, 1.0f, 0.6f, 1.0f, SimpleAppearance.DISABLE_CULLING));

        Shape3D shadow = new RectShadow(
                width + THUMBNAIL_DECO * 2,
                height + THUMBNAIL_DECO * 2,
                0.001f * 2, 0.0015f * 2, 0.002f * 2, 0.001f * 2,
                0.001f,
                -THUMBNAIL_DEPTH,
                0.3f);

        final SimpleAppearance bodyApp = new SimpleAppearance(
                1.0f, 1.0f, 1.0f, 1.0f,
                SimpleAppearance.ENABLE_TEXTURE | SimpleAppearance.DISABLE_CULLING);
        FuzzyEdgePanel body = new FuzzyEdgePanel(width, height, THUMBNAIL_DECO, bodyApp);

        Component3D thumbBody = new Component3D();
        thumbBody.addChild(deco);
        thumbBody.addChild(shadow);
        thumbBody.addChild(body);
        thumbnail.addChild(thumbBody);
        thumbnail.setPreferredSize(new Vector3f(
                width + THUMBNAIL_DECO * 2,
                height + THUMBNAIL_DECO * 2,
                THUMBNAIL_DEPTH));

        node.addTextureListener(new SwingNode.TextureListener() {
            public void textureChanged(Texture2D texture) {
                bodyApp.setTexture(texture);
            }
        });
        return thumbnail;
    }

    private static Component3D buildTitleBar(String title, float width, float contentH) {
        Component3D bar = new Component3D();

        SimpleAppearance barApp = new SimpleAppearance(
                0.35f, 0.50f, 0.75f, 0.95f, SimpleAppearance.DISABLE_CULLING);
        bar.addChild(new GlassyPanel(width, TITLE_BAR_HEIGHT, TITLE_BAR_DEPTH, barApp));

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

        bar.setTranslation(0.0f, contentH * 0.5f, 0.0f);
        bar.setCursor(Cursor3D.MOVE_CURSOR);
        return bar;
    }

    private static Component3D buildSpineTitle(
            String title, float contentW, float contentH, int side) {
        Component3D spine = new Component3D();
        GlassyText2D label = new GlassyText2D(
                title, contentH * 0.95f, SPINE_GLYPH_HEIGHT,
                new Color4f(0.95f, 0.97f, 1.0f, 1.0f),
                GlassyText2D.LightDirection.TOP_RIGHT,
                GlassyText2D.Alignment.LEFT,
                1.8f, true);
        spine.addChild(label);
        spine.setRotationAxis(0.0f, 1.0f, 0.0f);
        spine.setRotationAngle((float)Math.toRadians(90.0f * side));

        float x = side
                * (contentW * 0.5f + Frame3DWindowDecoration.DECO_WIDTH + SPINE_MARGIN);
        float y = contentH * 0.5f;
        float z = -Frame3DWindowDecoration.BODY_DEPTH * 1.5f
                + side * SPINE_GLYPH_HEIGHT * 0.5f;
        spine.setTranslation(x, y, z);
        spine.setPickable(false);
        return spine;
    }
}
