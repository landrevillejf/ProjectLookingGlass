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
import javax.swing.JPanel;
import org.jogamp.vecmath.Vector3f;
import org.jdesktop.lg3d.displayserver.nativewindow.NativeWindowFuzzyEdgePanel;
import org.jdesktop.lg3d.sg.Appearance;
import org.jdesktop.lg3d.sg.PolygonAttributes;
import org.jdesktop.lg3d.sg.Texture2D;
import org.jdesktop.lg3d.sg.TextureAttributes;
import org.jdesktop.lg3d.wg.internal.swingnode.SwingNodeJFrame;
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
        
        if (geometryUpdater==null)
            comp = new DefaultSwingNodeRenderer();
        else
            comp = geometryUpdater;
        comp.setup(hiddenFrame, this);
        hiddenFrame.addTextureChangedListener(comp);
        
        comp.addInputHandlers(this);

        addChild(comp);   
        this.setCursor(Cursor3D.MEDIUM_CURSOR);
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
        panelResizeListener = null;

        this.panel = p;
        comp.setPanel(p);
        
        hiddenFrame.setContentPane(panel);
        hiddenFrame.pack();

        if (DEBUG_HIERARCHY) {
            printHeirarchy(hiddenFrame, 0);
        }
        
        hiddenFrame.setVisible(true);
        final Toolkit3D toolkit3d = Toolkit3D.getToolkit3D();
        localWidth = toolkit3d.widthNativeToPhysical(panel.getWidth());
        localHeight = toolkit3d.heightNativeToPhysical(panel.getHeight());
        
        final JPanel capturedPanel = panel;
        panelResizeListener = new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent event) {
                localWidth = toolkit3d.widthNativeToPhysical(capturedPanel.getWidth());
                localHeight = toolkit3d.heightNativeToPhysical(capturedPanel.getHeight());
            }
        };
        panel.addComponentListener(panelResizeListener);
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
        panelResizeListener = null;
        panel = null;
        if (hiddenFrame != null) {
            hiddenFrame.setVisible(false);
            hiddenFrame.dispose();
        }
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
            
            transparencyAttributes =
                    new TransparencyAttributes(TransparencyAttributes.FASTEST, 0.8f);
            transparencyAttributes.setCapability(TransparencyAttributes.ALLOW_VALUE_WRITE);
            swingAppearance.setTransparencyAttributes(transparencyAttributes);
//            Material mat = new Material(new Color3f(1f,0f,0f), new Color3f(1f,0f,0f), new Color3f(1f,0f,0f), new Color3f(1f,0f,0f), 64f);
//            swingAppearance.setMaterial(mat);

            
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
