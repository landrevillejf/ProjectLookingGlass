/**
 * Project Looking Glass
 *
 * $RCSfile: SwingNode.java,v $
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
 *
 * $Revision: 1.16 $
 * $Date: 2007-01-04 22:40:22 $
 * $State: Exp $
 */

package org.jdesktop.lg3d.wg;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.jogamp.vecmath.Vector3f;
import org.jdesktop.lg3d.displayserver.nativewindow.NativeWindowFuzzyEdgePanel;
import org.jdesktop.lg3d.sg.Appearance;
import org.jdesktop.lg3d.sg.ImageComponent2D;
import org.jdesktop.lg3d.sg.PolygonAttributes;
import org.jdesktop.lg3d.sg.Texture2D;
import org.jdesktop.lg3d.sg.TextureAttributes;
import org.jdesktop.lg3d.wg.internal.swingnode.SwingNodeJFrame;
import org.jdesktop.lg3d.wg.internal.swingnode.SwingNodeWindowCapture;
import org.jdesktop.j3d.utils.math.Math3D;
import org.jdesktop.lg3d.sg.TransparencyAttributes;


/**
 * A Node to which a Swing JPanel can be added which enables the Swing
 * component to be rendered in the LG environment.
 *
 * @author Paul
 */
public class SwingNode extends Component3D {
    
    private SwingNodeJFrame hiddenFrame;
    private JPanel panel;

    private float localWidth;

    private float localHeight;
    
    private SwingNodeRenderer comp;

    /**
     * Tracks the resize listener attached to the current panel so that it can
     * be removed when the panel is replaced or the node is disposed. Without
     * this, each call to {@link #setJPanel(JPanel)} leaks a listener on the
     * previous panel.
     */
    private java.awt.event.ComponentListener panelResizeListener;

    /**
     * Debug switch: when the system property {@code lg3d.swingnode.debugHierarchy}
     * is set to {@code true}, {@link #setJPanel(JPanel)} dumps the Swing
     * hierarchy to stdout. The old code always dumped AND mutated user panels
     * by calling {@code setBackground(Color.ORANGE/RED)} on every component,
     * which visibly corrupted the rendered output; that mutation is gone.
     */
    private static final boolean DEBUG_HIERARCHY =
            Boolean.getBoolean("lg3d.swingnode.debugHierarchy");
    
    /**
     * Create a SwingNode with the default geometry
     */
    public SwingNode() {
        this(null);
    }

    /** Creates a new instance of SwingNode
     * 
     * If geometryUpdater is null the default geometry will be used. 
     */
    public SwingNode(SwingNodeRenderer geometryUpdater) {
        hiddenFrame = new SwingNodeJFrame();
        // Never mapped as a real on-screen window: the frame is kept only as a
        // displayable host for Swing layout and input dispatch. Its content is
        // painted into a texture by captureNow() instead (see setJPanel).
        hiddenFrame.setUndecorated(true);
        
        if (geometryUpdater==null)
            comp = new DefaultSwingNodeRenderer();
        else
            comp = geometryUpdater;
        comp.setup(hiddenFrame, this);
        hiddenFrame.addTextureChangedListener(comp);
        
        comp.addInputHandlers(this);

        addChild(comp);   
        this.setCursor(Cursor3D.MEDIUM_CURSOR);
        // Register the hidden frame with the global capture layer so dialogs and
        // popups this node's hosted panel opens (JOptionPane, JFileChooser, ...)
        // are captured into the texture instead of popping onto the host desktop.
        SwingNodeWindowCapture.registerHiddenFrame(hiddenFrame, this);
    }
    
    /**
     * @deprecated use setJPanel
     */
    public void setPanel(JPanel p) {
        setJPanel(p);
    }

    
    private void printHeirarchy(Container c, int depth) {
        // Debug-only helper. The pre-port version of this method also called
        // setBackground(Color.ORANGE/RED) on every component, which corrupted
        // user panels; that mutation has been removed. Now only dumps names.
        if (depth == 0) {
            for (int i=0; i<depth; i++) {
                System.out.print("\t");
            }
            System.out.print(c.getClass().getName());
            System.out.println(" (" + c.isOpaque() + ")");
        }
        for (Component comp : c.getComponents()) {
            for (int i=0; i<depth; i++) {
                System.out.print("\t");
            }
            System.out.print(comp.getClass().getName());
            System.out.println(" (" + comp.isOpaque() + ")");
            
            if (comp instanceof Container) {
                printHeirarchy((Container)comp, depth+1);
            }
        }
    }
    
    /**
     * Set the swing JPanel that this SwingNode will render. Any resize
     * listener attached to a previously-set panel is removed first, so
     * repeated calls do not leak listeners.
     */
    public void setJPanel(JPanel p) {
        // Detach the resize listener from the previous panel (fixes leak on
        // repeated setJPanel calls).
        if (panel != null && panelResizeListener != null) {
            panel.removeComponentListener(panelResizeListener);
        }
        if (panel != null) {
            HOSTED_PANELS.remove(panel);
        }
        panelResizeListener = null;

        this.panel = p;
        comp.setPanel(p);
        
        hiddenFrame.setContentPane(panel);
        hiddenFrame.pack();

        if (DEBUG_HIERARCHY) {
            printHeirarchy(hiddenFrame, 0);
        }
        
        // The pre-port code called hiddenFrame.setVisible(true) here. That only
        // stayed off-screen because the custom lg3d AWT peer toolkit
        // (lg.use3dtoolkit=true) intercepted the frame and vectored its image
        // into the texture. That toolkit is excluded from this build, so making
        // the frame visible popped a real window onto the host desktop while the
        // 3D quad stayed blank. We now render the panel ourselves (captureNow)
        // and leave the frame displayable-but-hidden for input dispatch.
        final Toolkit3D toolkit3d = Toolkit3D.getToolkit3D();
        localWidth = toolkit3d.widthNativeToPhysical(panel.getWidth());
        localHeight = toolkit3d.heightNativeToPhysical(panel.getHeight());
        
        HOSTED_PANELS.put(panel, this);
        installCaptureSupport(panel);
        
        final JPanel capturedPanel = panel;
        panelResizeListener = new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent event) {
                localWidth = toolkit3d.widthNativeToPhysical(capturedPanel.getWidth());
                localHeight = toolkit3d.heightNativeToPhysical(capturedPanel.getHeight());
                markDirty(SwingNode.this);
            }
        };
        panel.addComponentListener(panelResizeListener);
        
        // Capture the freshly laid-out panel on the EDT.
        if (SwingUtilities.isEventDispatchThread()) {
            markDirty(this);
        } else {
            SwingUtilities.invokeLater(new Runnable() {
                public void run() { markDirty(SwingNode.this); }
            });
        }
    }
    
    public JPanel getJPanel() {
        return panel;
    }

    /**
     * Releases the resources held by this SwingNode: removes the resize
     * listener from the current panel and disposes the offscreen JFrame that
     * hosts it. After calling this the SwingNode should not be reused.
     */
    public void dispose() {
        if (panel != null && panelResizeListener != null) {
            panel.removeComponentListener(panelResizeListener);
        }
        if (panel != null) {
            HOSTED_PANELS.remove(panel);
        }
        synchronized (DIRTY_NODES) {
            DIRTY_NODES.remove(this);
        }
        panelResizeListener = null;
        panel = null;
        swingTexture = null;
        imageComponent = null;
        p2Image = null;
        texWidth = -1;
        texHeight = -1;
        SwingNodeWindowCapture.releaseNode(this);
        if (hiddenFrame != null) {
            SwingNodeWindowCapture.unregisterHiddenFrame(hiddenFrame);
            hiddenFrame.setVisible(false);
            hiddenFrame.dispose();
        }
    }

    /**
     * Requests that this node's texture be re-captured on the next capture pass.
     * Called by {@link SwingNodeWindowCapture} when a captured overlay window
     * opens, closes or repaints, so the change is reflected in the 3D scene.
     */
    public void requestRecapture() {
        markDirty(this);
    }

    /**
     * Sets the transparency of the default renderer's appearance.
     * {@code 0.0f} is fully opaque, {@code 1.0f} is fully transparent.
     * Has no effect when this SwingNode was constructed with a custom
     * {@link SwingNodeRenderer}; subclasses that want dynamic transparency
     * should expose their own setter.
     */
    public void setTransparency(float transparency) {
        if (comp instanceof DefaultSwingNodeRenderer) {
            ((DefaultSwingNodeRenderer) comp).setTransparency(transparency);
        }
    }
           
    /**
     * Returns the width of the panel in 3D space
     */
    public float getLocalWidth() {
        return localWidth;
    }

    /**
     * Return the heigth of the panel in 3D space
     */
    public float getLocalHeight() {
        return localHeight;
    }
 
    // ------------------------------------------------------------------
    // Offscreen Swing rendering (replaces the excluded lg3d AWT peer toolkit)
    // ------------------------------------------------------------------

    private BufferedImage p2Image;            // power-of-two image holding the painted panel
    private ImageComponent2D imageComponent;  // live-updatable image on the texture
    private Texture2D swingTexture;           // texture handed to the renderer
    private int texWidth = -1;                // current pow2 texture width
    private int texHeight = -1;               // current pow2 texture height
    private final List<TextureListener> textureListeners =
            new ArrayList<TextureListener>();

    /**
     * Observer of the node's rendered texture. Unlike the internal
     * {@code SwingNodeJFrame.TextureChangedListener} (a single slot already
     * used by the {@link SwingNodeRenderer}), any number of listeners can be
     * registered via {@link SwingNode#addTextureListener}. Useful to share the
     * live Swing content with another surface, e.g. a taskbar thumbnail.
     */
    public interface TextureListener {
        /**
         * Called (on the EDT) whenever the texture object is recreated, i.e.
         * on the first capture and on every resize. Content-only repaints keep
         * the same {@code Texture2D} object and update it in place, so holders
         * of the texture see them without a callback.
         */
        void textureChanged(Texture2D texture);
    }

    public void addTextureListener(TextureListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        synchronized (textureListeners) {
            textureListeners.add(listener);
        }
        Texture2D tex = swingTexture;
        if (tex != null) {
            listener.textureChanged(tex);
        }
    }

    public void removeTextureListener(TextureListener listener) {
        synchronized (textureListeners) {
            textureListeners.remove(listener);
        }
    }

    private void fireTextureChanged(Texture2D texture) {
        List<TextureListener> snapshot;
        synchronized (textureListeners) {
            if (textureListeners.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<TextureListener>(textureListeners);
        }
        for (TextureListener listener : snapshot) {
            listener.textureChanged(texture);
        }
    }

    /**
     * Paints the hosted panel into a power-of-two texture and pushes it to the
     * renderer. Mirrors the texture layout the old {@code awtpeer.PeerBase}
     * produced (pow2 RGB image, yUp, panel drawn at the origin) so the existing
     * {@link SwingNodeRenderer#textureChanged(Texture2D)} contract - and any
     * custom renderer - is unchanged. Runs on the EDT.
     */
    void captureNow() {
        final JPanel p = panel;
        if (p == null) {
            return;
        }
        int w = p.getWidth();
        int h = p.getHeight();
        if (w <= 0 || h <= 0) {
            Dimension ps = p.getPreferredSize();
            w = ps.width;
            h = ps.height;
            if (w <= 0 || h <= 0) {
                return;
            }
            p.setSize(w, h);
            p.doLayout();
        }

        final Toolkit3D toolkit3d = Toolkit3D.getToolkit3D();
        localWidth = toolkit3d.widthNativeToPhysical(w);
        localHeight = toolkit3d.heightNativeToPhysical(h);

        int p2w = powerOfTwo(w);
        int p2h = powerOfTwo(h);

        boolean recreated = false;
        if (swingTexture == null || p2w != texWidth || p2h != texHeight) {
            // RGBA, not RGB: the renderer samples this texture with
            // TextureAttributes.REPLACE, so the fragment alpha is taken straight
            // from the image. An alpha-less RGB texture yields alpha ~0 under
            // Jogamp, so the quad blends away to (almost) nothing.
            p2Image = new BufferedImage(p2w, p2h, BufferedImage.TYPE_INT_ARGB);
            imageComponent = new ImageComponent2D(
                    ImageComponent2D.FORMAT_RGBA, p2w, p2h, false, true);
            imageComponent.setCapability(ImageComponent2D.ALLOW_IMAGE_WRITE);
            swingTexture = new Texture2D(
                    Texture2D.BASE_LEVEL, Texture2D.RGBA, p2w, p2h);
            swingTexture.setMinFilter(Texture2D.BASE_LEVEL_LINEAR);
            swingTexture.setMagFilter(Texture2D.BASE_LEVEL_LINEAR);
            swingTexture.setImage(0, imageComponent);
            texWidth = p2w;
            texHeight = p2h;
            recreated = true;
        }

        Graphics2D g = p2Image.createGraphics();
        try {
            // Lay down a fully opaque backdrop first: every pixel - including the
            // power-of-two padding that linear filtering can touch at the panel
            // edges - must be alpha 255, or REPLACE punches a transparent hole.
            // Use the panel's own background so dark panels get no light fringe.
            Color bg = p.getBackground();
            g.setColor(bg != null ? bg : Color.WHITE);
            g.fillRect(0, 0, p2w, p2h);
            g.setClip(0, 0, w, h);
            p.paint(g);

            // Paint any captured top-level windows on top of the hosted panel:
            // JOptionPane / JFileChooser dialogs and popups (centred overlays),
            // or a conventional app's JFrame (full-bleed, see CapturedFrameHost).
            // Each is drawn at its overlay origin, clipped to its own bounds and
            // read at its live size so a not-yet-laid-out dialog self-corrects on
            // the next capture pass.
            List<Window> overlays = SwingNodeWindowCapture.getCaptured(this);
            for (Window ow : overlays) {
                if (!ow.isDisplayable()) {
                    continue;
                }
                int ow_w = ow.getWidth();
                int ow_h = ow.getHeight();
                if (ow_w <= 0 || ow_h <= 0) {
                    continue;
                }
                Point origin = SwingNodeWindowCapture.getOverlayOrigin(this, ow, w, h);
                Graphics2D og = (Graphics2D) g.create();
                try {
                    og.translate(origin.x, origin.y);
                    og.setClip(0, 0, ow_w, ow_h);
                    ow.paint(og);
                } catch (Throwable t) {
                    // One failing overlay must not abort the whole capture.
                    t.printStackTrace();
                } finally {
                    og.dispose();
                }
            }
        } finally {
            g.dispose();
        }
        imageComponent.set(p2Image);

        if (recreated) {
            // New texture object: let the renderer bind it and re-fit geometry.
            comp.textureChanged(swingTexture);
            fireTextureChanged(swingTexture);
        }
    }

    private static int powerOfTwo(int value) {
        if (value < 1) {
            return 1;
        }
        int pow = 1;
        while (pow < value) {
            pow <<= 1;
        }
        return pow;
    }

    // ---- shared, cross-node capture scheduling -------------------------

    /** Coalescing delay (ms) between a repaint and the texture re-capture. */
    private static final int CAPTURE_DELAY_MS = 30;

    /** Panels currently hosted by a SwingNode, mapped to their node. */
    private static final Map<JPanel, SwingNode> HOSTED_PANELS =
            Collections.synchronizedMap(new WeakHashMap<JPanel, SwingNode>());

    /** Nodes whose panel has repainted and need a texture re-capture. */
    private static final Set<SwingNode> DIRTY_NODES =
            Collections.synchronizedSet(new HashSet<SwingNode>());

    private static Timer captureTimer;
    private static boolean repaintManagerInstalled;

    private static void installCaptureSupport(JPanel p) {
        synchronized (SwingNode.class) {
            if (!repaintManagerInstalled) {
                RepaintManager current = RepaintManager.currentManager(p);
                if (!(current instanceof SwingNodeRepaintManager)) {
                    RepaintManager.setCurrentManager(new SwingNodeRepaintManager());
                }
                repaintManagerInstalled = true;
            }
            if (captureTimer == null) {
                captureTimer = new Timer(CAPTURE_DELAY_MS,
                        e -> captureDirtyNodes());
                captureTimer.setRepeats(true);
            }
        }
    }

    private static void markDirty(SwingNode node) {
        synchronized (DIRTY_NODES) {
            DIRTY_NODES.add(node);
        }
        Timer t;
        synchronized (SwingNode.class) {
            t = captureTimer;
        }
        if (t != null && !t.isRunning()) {
            t.start();
        }
    }

    private static void captureDirtyNodes() {
        List<SwingNode> batch;
        synchronized (DIRTY_NODES) {
            if (DIRTY_NODES.isEmpty()) {
                captureTimer.stop();
                return;
            }
            batch = new ArrayList<SwingNode>(DIRTY_NODES);
            DIRTY_NODES.clear();
        }
        for (SwingNode node : batch) {
            try {
                node.captureNow();
            } catch (Throwable t) {
                // One failing widget must not stall the shared capture timer.
                t.printStackTrace();
            }
        }
        synchronized (DIRTY_NODES) {
            if (DIRTY_NODES.isEmpty() && captureTimer != null) {
                captureTimer.stop();
            }
        }
    }

    /**
     * Flags the owning {@link SwingNode} whenever a hosted panel - or any of
     * its descendants - is repainted, so its texture is re-captured. All real
     * work is delegated to the standard {@link RepaintManager}.
     */
    private static class SwingNodeRepaintManager extends RepaintManager {
        @Override
        public void addDirtyRegion(JComponent c, int x, int y, int w, int h) {
            super.addDirtyRegion(c, x, y, w, h);
            SwingNode owner = findOwner(c);
            if (owner != null) {
                markDirty(owner);
            }
        }

        private SwingNode findOwner(Component c) {
            for (Component p = c; p != null; p = p.getParent()) {
                SwingNode node = HOSTED_PANELS.get(p);
                if (node != null) {
                    return node;
                }
                // A repaint inside a captured dialog / popup bubbles up to its
                // top-level Window, which the capture layer maps back to the node
                // presenting it; without this the overlay would never refresh.
                if (p instanceof Window) {
                    node = SwingNodeWindowCapture.getNodeForWindow((Window) p);
                    if (node != null) {
                        return node;
                    }
                }
            }
            return null;
        }
    }

    class DefaultSwingNodeRenderer extends SwingNodeRenderer {
               
        private Appearance swingAppearance;
        private NativeWindowFuzzyEdgePanel body;
        private TransparencyAttributes transparencyAttributes;
        
        public DefaultSwingNodeRenderer() {
            width3D = 0.08f;
            height3D = 0.06f;

            swingAppearance = new Appearance();
            TextureAttributes texAttr = new TextureAttributes();
            texAttr.setTextureMode(TextureAttributes.REPLACE);
            swingAppearance.setTextureAttributes(texAttr);
            swingAppearance.setCapability(Appearance.ALLOW_TEXTURE_WRITE);
	    swingAppearance.setPolygonAttributes(
		new PolygonAttributes(
		    PolygonAttributes.POLYGON_FILL,
		    PolygonAttributes.CULL_NONE,
		    0.0f, false, 0.0f
		    ));            
            
            // BLENDED (alpha blending) rather than FASTEST (screen-door): this is
            // the same configuration SimpleAppearance uses for native windows -
            // the one proven to render an image quad opaque under Jogamp Java3D
            // 1.7.2. Default is fully opaque (0.0); callers opt into translucency
            // via SwingNode.setTransparency (widgets use ~0.12).
            transparencyAttributes =
                    new TransparencyAttributes(
                            TransparencyAttributes.BLENDED, 0.0f,
                            TransparencyAttributes.BLEND_SRC_ALPHA,
                            TransparencyAttributes.BLEND_ONE_MINUS_SRC_ALPHA);
            transparencyAttributes.setCapability(TransparencyAttributes.ALLOW_VALUE_WRITE);
            swingAppearance.setTransparencyAttributes(transparencyAttributes);

            
            body = new NativeWindowFuzzyEdgePanel(width3D, height3D, swingAppearance);
            addChild(body);
        }

        /**
         * Sets the transparency of the rendered panel. {@code 0.0f} is fully
         * opaque, {@code 1.0f} is fully transparent. Safe to call after the
         * scene graph is live thanks to {@code ALLOW_VALUE_WRITE}.
         */
        public void setTransparency(float transparency) {
            if (transparencyAttributes != null) {
                transparencyAttributes.setTransparency(transparency);
            }
        }
        
        public void textureChanged(Texture2D texture) {
            int swingImageWidth = panel.getWidth();
            int swingImageHeight = panel.getHeight();
            float p2width = texture.getWidth();
            float p2height = texture.getHeight();
            Toolkit3D toolkit3d = Toolkit3D.getToolkit3D();
            float localWidth = toolkit3d.widthNativeToPhysical(panel.getWidth());
            float localHeight = toolkit3d.heightNativeToPhysical(panel.getHeight());
            
            swingAppearance.setTexture(texture);
            if (localWidth!=width3D || localHeight!=height3D) {
                width3D=localWidth;
                height3D=localHeight;
                body.setSize(width3D, height3D,
                        p2width/(float)swingImageWidth, 
                        p2height/(float)swingImageHeight);
                setPreferredSize(new Vector3f(width3D, height3D, 0f));   
            }
        } 
    }
}
