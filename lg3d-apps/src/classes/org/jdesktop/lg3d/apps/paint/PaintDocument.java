/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.apps.paint;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.undo.UndoManager;

/**
 * The Paint document model: an ordered stack of {@link PaintLayer}s over a
 * background colour, a cached composite for fast repaint, structural editing
 * (add/delete/reorder/duplicate/merge/flatten), whole-image transforms
 * (resize/crop/rotate/flip) and a bounded undo history.
 *
 * <p>Layers are stored bottom-up (index 0 is the bottom layer). Tools draw into
 * the {@link #getActiveLayer() active layer}'s image and then call
 * {@link #invalidate()}; {@link #getComposite()} rebuilds the on-screen image on
 * demand, compositing every visible layer with its opacity and blend mode.</p>
 *
 * <p>Undo comes in two flavours (see {@link PaintEdits}): cheap per-layer pixel
 * edits for brush/shape strokes, and whole-document snapshot edits for
 * structural and transform operations that change the layer stack or canvas
 * size.</p>
 */
public class PaintDocument {

    /** Notified whenever the document changes so the UI can repaint/refresh. */
    public interface Listener {
        void documentChanged();
    }

    private static final int UNDO_LIMIT = 30;

    private int width;
    private int height;
    private Color background;
    private final List<PaintLayer> layers = new ArrayList<PaintLayer>();
    private int activeIndex;

    private BufferedImage cachedComposite;
    private boolean compositeDirty = true;

    private final UndoManager undo = new UndoManager();
    private final List<Listener> listeners = new ArrayList<Listener>();
    private int layerSerial;

    private PaintDocument(int width, int height, Color background) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.background = (background == null) ? Color.WHITE : background;
        undo.setLimit(UNDO_LIMIT);
    }

    /**
     * Creates a document with a single opaque "Background" layer filled with
     * {@code bg}.
     */
    public static PaintDocument create(int width, int height, Color bg) {
        PaintDocument doc = new PaintDocument(width, height, bg);
        PaintLayer base = new PaintLayer("Background", doc.width, doc.height);
        Graphics2D g = base.getImage().createGraphics();
        g.setColor(doc.background);
        g.fillRect(0, 0, doc.width, doc.height);
        g.dispose();
        doc.layers.add(base);
        doc.activeIndex = 0;
        doc.layerSerial = 1;
        doc.invalidate();
        return doc;
    }

    /** Creates a document from an existing image as its background layer. */
    public static PaintDocument fromImage(BufferedImage src) {
        PaintDocument doc = new PaintDocument(src.getWidth(), src.getHeight(),
                Color.WHITE);
        PaintLayer base = new PaintLayer("Background", src.getWidth(),
                src.getHeight());
        Graphics2D g = base.getImage().createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        doc.layers.add(base);
        doc.activeIndex = 0;
        doc.layerSerial = 1;
        doc.invalidate();
        return doc;
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void fireChanged() {
        invalidate();
        for (Listener l : new ArrayList<Listener>(listeners)) {
            l.documentChanged();
        }
    }

    // ------------------------------------------------------------------
    // Basic accessors
    // ------------------------------------------------------------------

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Color getBackground() {
        return background;
    }

    public void setBackground(Color bg) {
        this.background = (bg == null) ? Color.WHITE : bg;
        invalidate();
    }

    public List<PaintLayer> getLayers() {
        return layers;
    }

    public PaintLayer getLayer(int index) {
        return layers.get(index);
    }

    public int getLayerCount() {
        return layers.size();
    }

    public int getActiveIndex() {
        return activeIndex;
    }

    public void setActiveIndex(int index) {
        if (index >= 0 && index < layers.size()) {
            activeIndex = index;
        }
    }

    public PaintLayer getActiveLayer() {
        if (layers.isEmpty()) {
            return null;
        }
        return layers.get(activeIndex);
    }

    public void invalidate() {
        compositeDirty = true;
    }

    /**
     * Re-composites and notifies listeners after a non-undoable attribute change
     * (layer visibility, opacity or blend mode). Not recorded in the undo history.
     */
    public void notifyChanged() {
        fireChanged();
    }

    // ------------------------------------------------------------------
    // Compositing
    // ------------------------------------------------------------------

    /**
     * Returns the composited image (background + every visible layer). The
     * result is cached until the next {@link #invalidate()}; callers must treat
     * it as read-only.
     */
    public BufferedImage getComposite() {
        if (!compositeDirty && cachedComposite != null) {
            return cachedComposite;
        }
        BufferedImage out = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setColor(background);
        g.fillRect(0, 0, width, height);
        g.dispose();
        for (PaintLayer layer : layers) {
            if (layer.isVisible()) {
                compositeLayer(out, layer);
            }
        }
        cachedComposite = out;
        compositeDirty = false;
        return out;
    }

    private void compositeLayer(BufferedImage base, PaintLayer layer) {
        float alpha = layer.getOpacity();
        if (alpha <= 0.0f) {
            return;
        }
        BufferedImage src = layer.getImage();
        if (layer.getBlend() == PaintLayer.BlendMode.NORMAL) {
            Graphics2D g = base.createGraphics();
            g.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, alpha));
            g.drawImage(src, 0, 0, null);
            g.dispose();
            return;
        }
        // Blended modes are composited per pixel.
        int w = Math.min(base.getWidth(), src.getWidth());
        int h = Math.min(base.getHeight(), src.getHeight());
        int[] dst = base.getRGB(0, 0, w, h, null, 0, w);
        int[] srcPx = src.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < dst.length; i++) {
            dst[i] = blendPixel(dst[i], srcPx[i], alpha, layer.getBlend());
        }
        base.setRGB(0, 0, w, h, dst, 0, w);
    }

    private static int blendPixel(int d, int s, float opacity,
            PaintLayer.BlendMode mode) {
        int sa = (s >>> 24) & 0xFF;
        if (sa == 0) {
            return d;
        }
        float sA = (sa / 255.0f) * opacity;
        float dA = ((d >>> 24) & 0xFF) / 255.0f;
        float outA = sA + dA * (1.0f - sA);
        float sr = ((s >> 16) & 0xFF) / 255.0f;
        float sg = ((s >> 8) & 0xFF) / 255.0f;
        float sb = (s & 0xFF) / 255.0f;
        float dr = ((d >> 16) & 0xFF) / 255.0f;
        float dg = ((d >> 8) & 0xFF) / 255.0f;
        float db = (d & 0xFF) / 255.0f;
        float br = blendChannel(dr, sr, mode);
        float bg = blendChannel(dg, sg, mode);
        float bb = blendChannel(db, sb, mode);
        float or = (br * sA + dr * dA * (1 - sA));
        float og = (bg * sA + dg * dA * (1 - sA));
        float ob = (bb * sA + db * dA * (1 - sA));
        if (outA > 0.0f) {
            or /= outA;
            og /= outA;
            ob /= outA;
        }
        int a = clamp255(Math.round(outA * 255));
        int r = clamp255(Math.round(or * 255));
        int gg = clamp255(Math.round(og * 255));
        int b = clamp255(Math.round(ob * 255));
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    private static float blendChannel(float d, float s,
            PaintLayer.BlendMode mode) {
        switch (mode) {
            case MULTIPLY:
                return d * s;
            case SCREEN:
                return 1.0f - (1.0f - d) * (1.0f - s);
            case SOFT_LIGHT:
                if (s < 0.5f) {
                    return d - (1.0f - 2.0f * s) * d * (1.0f - d);
                }
                return d + (2.0f * s - 1.0f) * ((float) Math.sqrt(d) - d);
            case NORMAL:
            default:
                return s;
        }
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ------------------------------------------------------------------
    // Undo
    // ------------------------------------------------------------------

    public UndoManager getUndoManager() {
        return undo;
    }

    public boolean canUndo() {
        return undo.canUndo();
    }

    public boolean canRedo() {
        return undo.canRedo();
    }

    public void undo() {
        if (undo.canUndo()) {
            undo.undo();
            fireChanged();
        }
    }

    public void redo() {
        if (undo.canRedo()) {
            undo.redo();
            fireChanged();
        }
    }

    /** Records a per-layer pixel edit from a pre-change snapshot. */
    public void pushLayerEdit(String name, PaintLayer layer,
            BufferedImage before) {
        BufferedImage after = layer.snapshot();
        undo.addEdit(new PaintEdits.LayerEdit(this, layer, before, after, name));
        fireChanged();
    }

    /** An immutable deep copy of the whole document, for snapshot edits. */
    public static final class Snapshot {
        final int width;
        final int height;
        final Color background;
        final int activeIndex;
        final List<PaintLayer> layers;

        Snapshot(int width, int height, Color background, int activeIndex,
                List<PaintLayer> layers) {
            this.width = width;
            this.height = height;
            this.background = background;
            this.activeIndex = activeIndex;
            this.layers = layers;
        }
    }

    public Snapshot captureSnapshot() {
        List<PaintLayer> copy = new ArrayList<PaintLayer>(layers.size());
        for (PaintLayer l : layers) {
            copy.add(l.copy());
        }
        return new Snapshot(width, height, background, activeIndex, copy);
    }

    void restoreSnapshot(Snapshot s) {
        this.width = s.width;
        this.height = s.height;
        this.background = s.background;
        this.layers.clear();
        for (PaintLayer l : s.layers) {
            this.layers.add(l.copy());
        }
        this.activeIndex = Math.max(0,
                Math.min(s.activeIndex, this.layers.size() - 1));
        invalidate();
    }

    /** Records a whole-document structural/transform edit. */
    public void pushSnapshotEdit(String name, Snapshot before) {
        Snapshot after = captureSnapshot();
        undo.addEdit(new PaintEdits.SnapshotEdit(this, before, after, name));
        fireChanged();
    }

    // ------------------------------------------------------------------
    // Structural operations (each is an undoable snapshot edit)
    // ------------------------------------------------------------------

    public PaintLayer addLayerAbove() {
        Snapshot before = captureSnapshot();
        PaintLayer l = new PaintLayer(nextLayerName(), width, height);
        layers.add(activeIndex + 1, l);
        activeIndex = activeIndex + 1;
        pushSnapshotEdit("New layer", before);
        return l;
    }

    public void deleteLayer() {
        if (layers.size() <= 1) {
            return;
        }
        Snapshot before = captureSnapshot();
        layers.remove(activeIndex);
        activeIndex = Math.max(0, Math.min(activeIndex, layers.size() - 1));
        pushSnapshotEdit("Delete layer", before);
    }

    public void duplicateLayer() {
        Snapshot before = captureSnapshot();
        PaintLayer dup = getActiveLayer().copy(
                getActiveLayer().getName() + " copy");
        layers.add(activeIndex + 1, dup);
        activeIndex = activeIndex + 1;
        pushSnapshotEdit("Duplicate layer", before);
    }

    public void moveLayerUp() {
        if (activeIndex >= layers.size() - 1) {
            return;
        }
        Snapshot before = captureSnapshot();
        PaintLayer l = layers.remove(activeIndex);
        layers.add(activeIndex + 1, l);
        activeIndex++;
        pushSnapshotEdit("Move layer up", before);
    }

    public void moveLayerDown() {
        if (activeIndex <= 0) {
            return;
        }
        Snapshot before = captureSnapshot();
        PaintLayer l = layers.remove(activeIndex);
        layers.add(activeIndex - 1, l);
        activeIndex--;
        pushSnapshotEdit("Move layer down", before);
    }

    /** Merges the active layer into the one directly below it. */
    public void mergeDown() {
        if (activeIndex <= 0) {
            return;
        }
        Snapshot before = captureSnapshot();
        PaintLayer top = layers.get(activeIndex);
        PaintLayer bottom = layers.get(activeIndex - 1);
        Graphics2D g = bottom.getImage().createGraphics();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                top.getOpacity()));
        g.drawImage(top.getImage(), 0, 0, null);
        g.dispose();
        layers.remove(activeIndex);
        activeIndex--;
        pushSnapshotEdit("Merge down", before);
    }

    /** Flattens every visible layer into one opaque background layer. */
    public void flatten() {
        Snapshot before = captureSnapshot();
        BufferedImage flat = getComposite();
        layers.clear();
        PaintLayer base = new PaintLayer("Background", width, height);
        Graphics2D g = base.getImage().createGraphics();
        g.drawImage(flat, 0, 0, null);
        g.dispose();
        layers.add(base);
        activeIndex = 0;
        pushSnapshotEdit("Flatten", before);
    }

    private String nextLayerName() {
        return "Layer " + (++layerSerial);
    }

    // ------------------------------------------------------------------
    // Whole-image transforms
    // ------------------------------------------------------------------

    /** Scales the canvas and every layer to the new size. */
    public void resize(int newW, int newH) {
        newW = Math.max(1, newW);
        newH = Math.max(1, newH);
        Snapshot before = captureSnapshot();
        for (PaintLayer l : layers) {
            BufferedImage scaled = ImageOps.scale(l.getImage(), newW, newH);
            l.restore(scaled);
        }
        this.width = newW;
        this.height = newH;
        pushSnapshotEdit("Resize", before);
    }

    /** Crops the canvas and every layer to {@code rect}. */
    public void crop(Rectangle rect) {
        rect = rect.intersection(new Rectangle(0, 0, width, height));
        if (rect.width <= 0 || rect.height <= 0) {
            return;
        }
        Snapshot before = captureSnapshot();
        for (PaintLayer l : layers) {
            BufferedImage cropped = ImageOps.crop(l.getImage(), rect);
            l.restore(cropped);
        }
        this.width = rect.width;
        this.height = rect.height;
        pushSnapshotEdit("Crop", before);
    }

    /** Rotates the whole image by an arbitrary angle (bounding box grows). */
    public void rotate(double degrees) {
        Snapshot before = captureSnapshot();
        int[] newSize = new int[2];
        List<BufferedImage> rotated = new ArrayList<BufferedImage>();
        for (PaintLayer l : layers) {
            BufferedImage r = ImageOps.rotate(l.getImage(), degrees, newSize);
            rotated.add(r);
        }
        this.width = newSize[0];
        this.height = newSize[1];
        for (int i = 0; i < layers.size(); i++) {
            PaintLayer l = layers.get(i);
            BufferedImage canvas = PaintLayer.newImage(width, height);
            Graphics2D g = canvas.createGraphics();
            g.drawImage(rotated.get(i), 0, 0, null);
            g.dispose();
            l.restore(canvas);
        }
        pushSnapshotEdit("Rotate", before);
    }

    public void flip(boolean horizontal) {
        Snapshot before = captureSnapshot();
        for (PaintLayer l : layers) {
            l.restore(ImageOps.flip(l.getImage(), horizontal));
        }
        pushSnapshotEdit(horizontal ? "Flip horizontal" : "Flip vertical",
                before);
    }

    /** Applies an in-place pixel filter to the active layer only. */
    public void applyFilterToActive(String name, ImageOps.Filter filter) {
        PaintLayer l = getActiveLayer();
        if (l == null) {
            return;
        }
        BufferedImage before = l.snapshot();
        BufferedImage out = filter.apply(l.getImage());
        l.restore(out);
        pushLayerEdit(name, l, before);
    }

    /** Applies an in-place pixel filter to every layer. */
    public void applyFilterToAll(String name, ImageOps.Filter filter) {
        Snapshot before = captureSnapshot();
        for (PaintLayer l : layers) {
            l.restore(filter.apply(l.getImage()));
        }
        pushSnapshotEdit(name, before);
    }

    /** Replaces the whole document with a freshly loaded image. */
    public void replaceWith(BufferedImage img) {
        Snapshot before = captureSnapshot();
        this.width = img.getWidth();
        this.height = img.getHeight();
        this.layers.clear();
        PaintLayer base = new PaintLayer("Background", width, height);
        Graphics2D g = base.getImage().createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(img, 0, 0, null);
        g.dispose();
        this.layers.add(base);
        this.activeIndex = 0;
        this.layerSerial = 1;
        pushSnapshotEdit("Open image", before);
    }

    /**
     * Resets the document to a fresh blank canvas of the given size and
     * background, clearing the layer stack and discarding the undo history. Used
     * by File &rarr; New so the canvas and layer panel keep their document
     * reference.
     */
    public void resetTo(int newW, int newH, Color bg) {
        this.width = Math.max(1, newW);
        this.height = Math.max(1, newH);
        this.background = (bg == null) ? Color.WHITE : bg;
        this.layers.clear();
        PaintLayer base = new PaintLayer("Background", width, height);
        Graphics2D g = base.getImage().createGraphics();
        g.setColor(background);
        g.fillRect(0, 0, width, height);
        g.dispose();
        this.layers.add(base);
        this.activeIndex = 0;
        this.layerSerial = 1;
        this.undo.discardAllEdits();
        fireChanged();
    }
}
