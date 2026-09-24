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
package org.jdesktop.lg3d.apps.paint;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;

/**
 * Everything a {@link Tool} needs while it works: the {@link PaintDocument},
 * the {@link PaintState} tool settings, the current {@link Selection}, and a
 * {@link Callback} back to {@link PaintCanvas} for repaints, dialogs and
 * selection notifications. One instance is owned by the canvas and handed to
 * every tool gesture.
 */
public class PaintContext {

    /** The canvas-side services a tool may call. */
    public interface Callback {
        /** Requests a repaint of the canvas (composite + overlays). */
        void repaintCanvas();

        /** The component to use as a dialog parent (the frame). */
        Component getDialogParent();

        /** Called after the tool changed the selection, to refresh overlays. */
        void selectionChanged();

        /** Called after a tool changed a PaintState colour (fg swatch etc.). */
        void stateChanged();
    }

    private final PaintDocument document;
    private final PaintState state;
    private final Callback callback;
    private Selection selection;

    public PaintContext(PaintDocument document, PaintState state,
            Callback callback) {
        this.document = document;
        this.state = state;
        this.callback = callback;
    }

    public PaintDocument getDocument() {
        return document;
    }

    public PaintState getState() {
        return state;
    }

    public Callback getCallback() {
        return callback;
    }

    public Selection getSelection() {
        return selection;
    }

    public void setSelection(Selection selection) {
        this.selection = selection;
        if (callback != null) {
            callback.selectionChanged();
        }
    }

    /** Notifies listeners that the existing selection moved or changed shape. */
    public void selectionChanged() {
        if (callback != null) {
            callback.selectionChanged();
        }
    }

    public void repaint() {
        if (callback != null) {
            callback.repaintCanvas();
        }
    }

    public void fireStateChanged() {
        if (callback != null) {
            callback.stateChanged();
        }
    }

    public Component getDialogParent() {
        return (callback == null) ? null : callback.getDialogParent();
    }

    /** The layer the tools currently paint into. */
    public PaintLayer getActiveLayer() {
        return document.getActiveLayer();
    }

    /**
     * A {@link Graphics2D} on the active layer's image, pre-configured from the
     * tool state: foreground colour, stroke width/dash, tool opacity (as an
     * {@link AlphaComposite}) and the antialiasing switch. The caller must
     * dispose the returned graphics. Returns null when there is no active layer.
     */
    public Graphics2D activeLayerGraphics() {
        PaintLayer layer = document.getActiveLayer();
        if (layer == null) {
            return null;
        }
        Graphics2D g = layer.getImage().createGraphics();
        applyState(g);
        return g;
    }

    /** Configures an arbitrary {@link Graphics2D} from the tool state. */
    public void applyState(Graphics2D g) {
        if (state.isAntialias()) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        } else {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
        }
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
        float opacity = state.getOpacity();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                Math.max(0.0f, Math.min(1.0f, opacity))));
        g.setColor(state.getForeground());
        g.setStroke(stroke());
    }

    /** Builds the {@link Stroke} described by the tool state. */
    public Stroke stroke() {
        float[] dash = state.getDash();
        if (dash == null) {
            return new BasicStroke(state.getStrokeWidth(),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        }
        return new BasicStroke(state.getStrokeWidth(),
                BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, dash, 0.0f);
    }

    /**
     * A pixel snapshot of the active layer for a cheap undo edit. Tools capture
     * this on press and pass it to
     * {@link PaintDocument#pushLayerEdit(String, PaintLayer, BufferedImage)} on
     * release. Returns null when there is no active layer.
     */
    public BufferedImage snapshotActiveLayer() {
        PaintLayer layer = document.getActiveLayer();
        return (layer == null) ? null : layer.snapshot();
    }
}
