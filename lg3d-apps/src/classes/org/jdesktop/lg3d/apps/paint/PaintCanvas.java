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
package org.jdesktop.lg3d.apps.paint;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.Point2D;
import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

/**
 * The drawing surface. It paints the document composite at the current zoom, then
 * overlays the selection and the active tool's live preview in document space, and
 * routes mouse gestures (translated from screen to document coordinates) to the
 * active {@link Tool}. Ctrl+wheel zooms about the pointer; the middle button or a
 * held Space pans the enclosing viewport. It is the {@link PaintContext.Callback}
 * for its tools and a {@link PaintDocument.Listener}, so document edits repaint it
 * and keep its preferred size (and the scroll pane) in step with the canvas size.
 */
public class PaintCanvas extends JPanel
        implements PaintContext.Callback, PaintDocument.Listener,
        MouseListener, MouseMotionListener, MouseWheelListener, KeyListener {

    private final PaintDocument document;
    private final PaintState state;
    private final StatusBar status;
    private final PaintContext context;

    private boolean panning;
    private Point panStart;
    private Point viewStart;
    private boolean spaceDown;

    public PaintCanvas(PaintDocument document, PaintState state,
            StatusBar status) {
        this.document = document;
        this.state = state;
        this.status = status;
        this.context = new PaintContext(document, state, this);
        setBackground(new Color(0x60, 0x60, 0x60));
        setOpaque(true);
        setFocusable(true);
        addMouseListener(this);
        addMouseMotionListener(this);
        addMouseWheelListener(this);
        addKeyListener(this);
        document.addListener(this);
        updatePreferredSize();
        applyToolCursor();
    }

    public PaintContext getContext() {
        return context;
    }

    public PaintDocument getDocument() {
        return document;
    }

    public PaintState getState() {
        return state;
    }

    // ------------------------------------------------------------------
    // Document listener
    // ------------------------------------------------------------------

    public void documentChanged() {
        updatePreferredSize();
        if (status != null) {
            status.setDocumentSize(document.getWidth(), document.getHeight());
            status.setZoom(state.getZoom());
        }
        repaint();
    }

    private void updatePreferredSize() {
        double z = state.getZoom();
        int w = (int) Math.round(document.getWidth() * z);
        int h = (int) Math.round(document.getHeight() * z);
        setPreferredSize(new Dimension(Math.max(1, w), Math.max(1, h)));
        revalidate();
    }

    // ------------------------------------------------------------------
    // Zoom
    // ------------------------------------------------------------------

    public double getZoom() {
        return state.getZoom();
    }

    public void setZoom(double zoom) {
        double old = state.getZoom();
        state.setZoom(zoom);
        if (Math.abs(old - state.getZoom()) < 1e-9) {
            return;
        }
        updatePreferredSize();
        if (status != null) {
            status.setZoom(state.getZoom());
        }
        repaint();
    }

    public void zoomBy(double factor) {
        setZoom(state.getZoom() * factor);
    }

    /** Called when the active tool changed, to swap the cursor and hint. */
    public void applyToolCursor() {
        Tool tool = state.getTool();
        Cursor c = (tool == null) ? null : tool.getCursor();
        setCursor(c != null ? c : Cursor.getDefaultCursor());
        if (status != null) {
            status.setHint(tool == null ? " " : tool.getHint());
        }
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0;
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());

        double z = state.getZoom();
        int w = (int) Math.round(document.getWidth() * z);
        int h = (int) Math.round(document.getHeight() * z);

        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                z >= 2.0 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                        : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(document.getComposite(), 0, 0, w, h, null);

        // Overlays are drawn in document space so tools need no zoom maths.
        Graphics2D og = (Graphics2D) g.create();
        og.scale(z, z);
        og.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        Selection sel = context.getSelection();
        if (sel != null && !sel.isEmpty()) {
            drawSelection(og, sel, z);
        }
        Tool tool = state.getTool();
        if (tool != null) {
            tool.preview(og, context);
        }
        og.dispose();

        // Canvas border in screen space.
        g.setColor(Color.DARK_GRAY);
        g.setStroke(new BasicStroke(1.0f));
        g.drawRect(0, 0, w, h);
    }

    private void drawSelection(Graphics2D g, Selection sel, double zoom) {
        Rectangle b = sel.getBounds();
        float dash = (float) (4.0 / zoom);
        g.setStroke(new BasicStroke((float) (1.0 / zoom), BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 1.0f, new float[] { dash, dash }, 0.0f));
        g.setColor(Color.WHITE);
        g.drawRect(b.x, b.y, b.width, b.height);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke((float) (1.0 / zoom), BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 1.0f, new float[] { dash, dash }, dash));
        g.drawRect(b.x, b.y, b.width, b.height);
    }

    // ------------------------------------------------------------------
    // Coordinate conversion
    // ------------------------------------------------------------------

    private Point2D toDoc(Point p) {
        double z = state.getZoom();
        return new Point2D.Double(p.getX() / z, p.getY() / z);
    }

    private JViewport viewport() {
        return (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
    }

    // ------------------------------------------------------------------
    // PaintContext.Callback
    // ------------------------------------------------------------------

    public void repaintCanvas() {
        repaint();
    }

    public java.awt.Component getDialogParent() {
        return SwingUtilities.getWindowAncestor(this);
    }

    public void selectionChanged() {
        repaint();
        firePropertyChange("selection", null, context.getSelection());
    }

    public void stateChanged() {
        firePropertyChange("state", null, this);
        repaint();
    }

    // ------------------------------------------------------------------
    // Mouse
    // ------------------------------------------------------------------

    public void mousePressed(MouseEvent e) {
        requestFocusInWindow();
        if (e.getButton() == MouseEvent.BUTTON2 || spaceDown) {
            panning = true;
            panStart = e.getPoint();
            JViewport vp = viewport();
            viewStart = (vp == null) ? new Point() : vp.getViewPosition();
            setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            return;
        }
        if (e.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        Tool tool = state.getTool();
        if (tool != null) {
            tool.press(toDoc(e.getPoint()), context);
        }
    }

    public void mouseDragged(MouseEvent e) {
        updateCoords(e.getPoint());
        if (panning) {
            JViewport vp = viewport();
            if (vp != null) {
                int dx = panStart.x - e.getX();
                int dy = panStart.y - e.getY();
                vp.setViewPosition(new Point(
                        Math.max(0, viewStart.x + dx),
                        Math.max(0, viewStart.y + dy)));
            }
            return;
        }
        if (e.getButton() == MouseEvent.BUTTON1 || (e.getModifiersEx()
                & MouseEvent.BUTTON1_DOWN_MASK) != 0) {
            Tool tool = state.getTool();
            if (tool != null) {
                tool.drag(toDoc(e.getPoint()), context);
            }
        }
    }

    public void mouseReleased(MouseEvent e) {
        if (panning && (e.getButton() == MouseEvent.BUTTON2 || spaceDown)) {
            panning = false;
            applyToolCursor();
            return;
        }
        if (e.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        Tool tool = state.getTool();
        if (tool != null) {
            tool.release(toDoc(e.getPoint()), context);
        }
    }

    public void mouseMoved(MouseEvent e) {
        updateCoords(e.getPoint());
    }

    private void updateCoords(Point p) {
        if (status == null) {
            return;
        }
        Point2D d = toDoc(p);
        status.setCoords((int) Math.round(d.getX()), (int) Math.round(d.getY()));
    }

    public void mouseWheelMoved(MouseWheelEvent e) {
        if (e.isControlDown()) {
            double factor = (e.getWheelRotation() < 0) ? 1.1 : 1.0 / 1.1;
            zoomBy(factor);
        } else {
            JViewport vp = viewport();
            if (vp != null) {
                Point pos = vp.getViewPosition();
                int amount = e.getWheelRotation() * 16;
                vp.setViewPosition(new Point(pos.x, Math.max(0, pos.y + amount)));
            }
        }
    }

    public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2 && state.getTool() instanceof PolygonTool) {
            ((PolygonTool) state.getTool()).finish(context);
        }
    }

    public void mouseEntered(MouseEvent e) {
    }

    public void mouseExited(MouseEvent e) {
        if (status != null) {
            status.clearCoords();
        }
    }

    // ------------------------------------------------------------------
    // Keyboard (Space = pan, Enter = finish polygon, Escape = cancel/clear)
    // ------------------------------------------------------------------

    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_SPACE) {
            spaceDown = true;
            setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        } else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
            if (state.getTool() instanceof PolygonTool) {
                ((PolygonTool) state.getTool()).finish(context);
            }
        } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
            if (state.getTool() instanceof PolygonTool) {
                ((PolygonTool) state.getTool()).cancel(context);
            } else if (context.getSelection() != null) {
                context.setSelection(null);
            }
        }
    }

    public void keyReleased(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_SPACE) {
            spaceDown = false;
            panning = false;
            applyToolCursor();
        }
    }

    public void keyTyped(KeyEvent e) {
    }
}
