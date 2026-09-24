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
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

/**
 * The Paint application window: a conventional {@link JFrame} with a full menu
 * bar (File / Edit / Image / Layer / Select / View / Filter / Help), a tool +
 * options toolbar, the {@link PaintCanvas} in a scroll pane, the {@link LayerPanel}
 * on the right and a {@link StatusBar} at the bottom. It owns the
 * {@link PaintDocument} and {@link PaintState} and wires every action - file I/O,
 * clipboard, transforms, filters, layer structure, zoom and undo/redo - to them.
 *
 * <p>Because it is a plain {@code JFrame}, the desktop's capture layer
 * ({@code SwingNodeWindowCapture} / {@code CapturedFrameHost}) presents it as a
 * decorated 3D window and shows its dialogs as in-scene overlays; this class has
 * no knowledge of the 3D desktop.</p>
 */
public class PaintFrame extends JFrame {

    private final PaintDocument doc;
    private final PaintState state;
    private final PaintCanvas canvas;
    private final LayerPanel layerPanel;
    private final StatusBar status;
    private final Toolbox toolbox;

    private Action undoAction;
    private Action redoAction;
    private JButton fgButton;
    private JButton bgButton;
    private JSpinner strokeSpinner;

    // App-internal image clipboard (selection or whole active layer).
    private BufferedImage clipboard;
    private Point clipboardOffset = new Point(0, 0);

    public PaintFrame(PaintDocument document) {
        super("Paint");
        this.doc = document;
        this.state = new PaintState();
        this.status = new StatusBar();
        this.canvas = new PaintCanvas(doc, state, status);
        this.layerPanel = new LayerPanel(doc);
        this.toolbox = new Toolbox(state, new Toolbox.Listener() {
            public void toolSelected(Tool tool) {
                canvas.applyToolCursor();
                updateActions();
            }
        });

        setJMenuBar(buildMenuBar());
        JPanel content = new JPanel(new BorderLayout());
        content.add(buildToolBar(), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(canvas);
        scroll.getViewport().setBackground(new Color(0x60, 0x60, 0x60));
        content.add(scroll, BorderLayout.CENTER);
        content.add(layerPanel, BorderLayout.EAST);
        content.add(status, BorderLayout.SOUTH);
        setContentPane(content);

        doc.addListener(new PaintDocument.Listener() {
            public void documentChanged() {
                updateActions();
            }
        });
        canvas.addPropertyChangeListener(new PropertyChangeListener() {
            public void propertyChange(PropertyChangeEvent e) {
                if ("state".equals(e.getPropertyName())) {
                    syncSwatches();
                } else if ("selection".equals(e.getPropertyName())) {
                    updateActions();
                }
            }
        });

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1000, 700);
        setLocationRelativeTo(null);
        syncSwatches();
        status.setDocumentSize(doc.getWidth(), doc.getHeight());
        status.setZoom(state.getZoom());
        updateActions();
    }

    // ------------------------------------------------------------------
    // Menu bar
    // ------------------------------------------------------------------

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        bar.add(buildFileMenu());
        bar.add(buildEditMenu());
        bar.add(buildImageMenu());
        bar.add(buildLayerMenu());
        bar.add(buildSelectMenu());
        bar.add(buildViewMenu());
        bar.add(buildFilterMenu());
        bar.add(buildHelpMenu());
        return bar;
    }

    private JMenu buildFileMenu() {
        JMenu m = new JMenu("File");
        m.setMnemonic(KeyEvent.VK_F);
        m.add(item("New", KeyEvent.VK_N, new Runnable() {
            public void run() {
                newDocument();
            }
        }));
        m.add(item("Open...", KeyEvent.VK_O, new Runnable() {
            public void run() {
                openDocument();
            }
        }));
        m.add(item("Save", KeyEvent.VK_S, new Runnable() {
            public void run() {
                saveDocument(false);
            }
        }));
        m.add(item("Save As...", 0, new Runnable() {
            public void run() {
                saveDocument(true);
            }
        }));
        m.addSeparator();
        m.add(item("Exit", 0, new Runnable() {
            public void run() {
                dispose();
            }
        }));
        return m;
    }

    private JMenu buildEditMenu() {
        JMenu m = new JMenu("Edit");
        m.setMnemonic(KeyEvent.VK_E);
        undoAction = new AbstractAction("Undo") {
            public void actionPerformed(ActionEvent e) {
                doc.undo();
            }
        };
        redoAction = new AbstractAction("Redo") {
            public void actionPerformed(ActionEvent e) {
                doc.redo();
            }
        };
        JMenuItem undo = new JMenuItem(undoAction);
        undo.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        JMenuItem redo = new JMenuItem(redoAction);
        redo.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        m.add(undo);
        m.add(redo);
        m.addSeparator();
        m.add(item("Cut", KeyEvent.VK_X, new Runnable() {
            public void run() {
                cut();
            }
        }));
        m.add(item("Copy", KeyEvent.VK_C, new Runnable() {
            public void run() {
                copy();
            }
        }));
        m.add(item("Paste", KeyEvent.VK_V, new Runnable() {
            public void run() {
                paste();
            }
        }));
        m.add(item("Clear", KeyEvent.VK_DELETE, new Runnable() {
            public void run() {
                clear();
            }
        }));
        return m;
    }

    private JMenu buildImageMenu() {
        JMenu m = new JMenu("Image");
        m.add(item("Resize...", 0, new Runnable() {
            public void run() {
                resizeImage();
            }
        }));
        m.add(item("Rotate...", 0, new Runnable() {
            public void run() {
                rotateImage();
            }
        }));
        m.add(item("Flip horizontal", 0, new Runnable() {
            public void run() {
                doc.flip(true);
            }
        }));
        m.add(item("Flip vertical", 0, new Runnable() {
            public void run() {
                doc.flip(false);
            }
        }));
        m.addSeparator();
        m.add(item("Crop to selection", 0, new Runnable() {
            public void run() {
                cropToSelection();
            }
        }));
        return m;
    }

    private JMenu buildLayerMenu() {
        JMenu m = new JMenu("Layer");
        m.add(item("New layer", 0, new Runnable() {
            public void run() {
                doc.addLayerAbove();
            }
        }));
        m.add(item("Duplicate layer", 0, new Runnable() {
            public void run() {
                doc.duplicateLayer();
            }
        }));
        m.add(item("Delete layer", 0, new Runnable() {
            public void run() {
                doc.deleteLayer();
            }
        }));
        m.addSeparator();
        m.add(item("Merge down", 0, new Runnable() {
            public void run() {
                doc.mergeDown();
            }
        }));
        m.add(item("Flatten image", 0, new Runnable() {
            public void run() {
                doc.flatten();
            }
        }));
        m.addSeparator();
        m.add(item("Move layer up", 0, new Runnable() {
            public void run() {
                doc.moveLayerUp();
            }
        }));
        m.add(item("Move layer down", 0, new Runnable() {
            public void run() {
                doc.moveLayerDown();
            }
        }));
        return m;
    }

    private JMenu buildSelectMenu() {
        JMenu m = new JMenu("Select");
        m.add(item("All", KeyEvent.VK_A, new Runnable() {
            public void run() {
                canvas.getContext().setSelection(Selection.rectangle(
                        new Rectangle(0, 0, doc.getWidth(), doc.getHeight())));
            }
        }));
        m.add(item("Deselect", KeyEvent.VK_D, new Runnable() {
            public void run() {
                canvas.getContext().setSelection(null);
            }
        }));
        return m;
    }

    private JMenu buildViewMenu() {
        JMenu m = new JMenu("View");
        m.add(item("Zoom in", KeyEvent.VK_EQUALS, new Runnable() {
            public void run() {
                canvas.zoomBy(1.25);
            }
        }));
        m.add(item("Zoom out", KeyEvent.VK_MINUS, new Runnable() {
            public void run() {
                canvas.zoomBy(1.0 / 1.25);
            }
        }));
        m.add(item("Actual size (100%)", KeyEvent.VK_0, new Runnable() {
            public void run() {
                canvas.setZoom(1.0);
            }
        }));
        return m;
    }

    private JMenu buildFilterMenu() {
        JMenu m = new JMenu("Filter");
        m.add(item("Brightness / Contrast...", 0, new Runnable() {
            public void run() {
                brightnessContrast();
            }
        }));
        m.add(item("Hue / Saturation...", 0, new Runnable() {
            public void run() {
                hueSaturation();
            }
        }));
        m.add(item("Posterize...", 0, new Runnable() {
            public void run() {
                posterize();
            }
        }));
        m.add(item("Threshold...", 0, new Runnable() {
            public void run() {
                threshold();
            }
        }));
        m.add(item("Blur...", 0, new Runnable() {
            public void run() {
                blur();
            }
        }));
        m.addSeparator();
        m.add(item("Grayscale", 0, new Runnable() {
            public void run() {
                doc.applyFilterToActive("Grayscale", ImageOps.grayscale());
            }
        }));
        m.add(item("Invert", KeyEvent.VK_I, new Runnable() {
            public void run() {
                doc.applyFilterToActive("Invert", ImageOps.invert());
            }
        }));
        m.add(item("Sharpen", 0, new Runnable() {
            public void run() {
                doc.applyFilterToActive("Sharpen", ImageOps.sharpen());
            }
        }));
        m.add(item("Emboss", 0, new Runnable() {
            public void run() {
                doc.applyFilterToActive("Emboss", ImageOps.emboss());
            }
        }));
        return m;
    }

    private JMenu buildHelpMenu() {
        JMenu m = new JMenu("Help");
        m.add(item("About Paint", 0, new Runnable() {
            public void run() {
                JOptionPane.showMessageDialog(PaintFrame.this,
                        "Paint - a layered raster draw/paint editor.\n"
                        + "Part of the Project Looking Glass demo apps.",
                        "About Paint", JOptionPane.INFORMATION_MESSAGE);
            }
        }));
        return m;
    }

    private JMenuItem item(String label, int accel, final Runnable action) {
        JMenuItem mi = new JMenuItem(label);
        if (accel != 0) {
            mi.setAccelerator(KeyStroke.getKeyStroke(accel,
                    java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        }
        mi.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
        return mi;
    }

    // ------------------------------------------------------------------
    // Toolbar
    // ------------------------------------------------------------------

    private JToolBar buildToolBar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);

        fgButton = new JButton();
        fgButton.setPreferredSize(new Dimension(28, 28));
        fgButton.setToolTipText("Foreground colour");
        fgButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Color c = JColorChooser.showDialog(PaintFrame.this,
                        "Foreground colour", state.getForeground());
                if (c != null) {
                    state.setForeground(c);
                    syncSwatches();
                    canvas.stateChanged();
                }
            }
        });
        bgButton = new JButton();
        bgButton.setPreferredSize(new Dimension(28, 28));
        bgButton.setToolTipText("Background colour");
        bgButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                Color c = JColorChooser.showDialog(PaintFrame.this,
                        "Background colour", state.getBackground());
                if (c != null) {
                    state.setBackground(c);
                    syncSwatches();
                }
            }
        });
        tb.add(fgButton);
        tb.add(bgButton);
        JButton swap = new JButton("Swap");
        swap.setToolTipText("Swap foreground and background (X)");
        swap.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                state.swapColors();
                syncSwatches();
                canvas.stateChanged();
            }
        });
        tb.add(swap);
        tb.addSeparator();

        tb.add(new JLabel(" Stroke "));
        strokeSpinner = new JSpinner(new SpinnerNumberModel(
                (int) Math.round(state.getStrokeWidth()), 1, 200, 1));
        strokeSpinner.setMaximumSize(new Dimension(60, 28));
        strokeSpinner.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent e) {
                state.setStrokeWidth(((Number) strokeSpinner.getValue()).floatValue());
            }
        });
        tb.add(strokeSpinner);
        tb.addSeparator();

        final JSpinner opacity = new JSpinner(new SpinnerNumberModel(
                100, 1, 100, 5));
        opacity.setMaximumSize(new Dimension(60, 28));
        opacity.setToolTipText("Tool opacity %");
        opacity.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent e) {
                state.setOpacity(((Number) opacity.getValue()).intValue() / 100.0f);
            }
        });
        tb.add(new JLabel(" Opacity "));
        tb.add(opacity);
        tb.addSeparator();

        tb.add(zoomButton("-", 1.0 / 1.25));
        tb.add(zoomButton("+", 1.25));
        JButton actual = new JButton("100%");
        actual.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                canvas.setZoom(1.0);
            }
        });
        tb.add(actual);
        return tb;
    }

    private JButton zoomButton(String label, final double factor) {
        JButton b = new JButton(label);
        b.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                canvas.zoomBy(factor);
            }
        });
        return b;
    }

    private void syncSwatches() {
        if (fgButton != null) {
            fgButton.setBackground(state.getForeground());
        }
        if (bgButton != null) {
            bgButton.setBackground(state.getBackground());
        }
    }

    // ------------------------------------------------------------------
    // File actions
    // ------------------------------------------------------------------

    private void newDocument() {
        NewImageDialog d = new NewImageDialog(this);
        if (d.showDialog()) {
            doc.resetTo(d.getWidth_(), d.getHeight_(), d.getBackgroundColor());
            canvas.getContext().setSelection(null);
        }
    }

    private void openDocument() {
        BufferedImage img = PaintIO.chooseAndRead(this);
        if (img != null) {
            doc.replaceWith(img);
            canvas.getContext().setSelection(null);
        } else if (PaintIO.lastError() != null) {
            JOptionPane.showMessageDialog(this, PaintIO.lastError(),
                    "Open failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveDocument(boolean saveAs) {
        BufferedImage img = doc.getComposite();
        File file = PaintIO.chooseAndWrite(this, img, "paint.png");
        if (file != null) {
            status.setHint("Saved " + file.getName());
        } else if (PaintIO.lastError() != null) {
            JOptionPane.showMessageDialog(this, PaintIO.lastError(),
                    "Save failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------------------------------------------------
    // Clipboard / selection actions
    // ------------------------------------------------------------------

    private void copy() {
        PaintLayer layer = doc.getActiveLayer();
        if (layer == null) {
            return;
        }
        Selection sel = canvas.getContext().getSelection();
        if (sel != null && !sel.isEmpty()) {
            clipboard = sel.extract(layer.getImage());
            Rectangle b = sel.getBounds();
            clipboardOffset = new Point(b.x, b.y);
        } else {
            clipboard = layer.snapshot();
            clipboardOffset = new Point(0, 0);
        }
        updateActions();
    }

    private void cut() {
        copy();
        PaintLayer layer = doc.getActiveLayer();
        if (layer == null || clipboard == null) {
            return;
        }
        BufferedImage before = layer.snapshot();
        Selection sel = canvas.getContext().getSelection();
        if (sel != null && !sel.isEmpty()) {
            sel.clear(layer.getImage());
        } else {
            Graphics2D g = layer.getImage().createGraphics();
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, layer.getWidth(), layer.getHeight());
            g.dispose();
        }
        doc.pushLayerEdit("Cut", layer, before);
    }

    private void paste() {
        if (clipboard == null) {
            return;
        }
        PaintLayer layer = doc.addLayerAbove();
        BufferedImage before = layer.snapshot();
        Graphics2D g = layer.getImage().createGraphics();
        g.drawImage(clipboard, clipboardOffset.x, clipboardOffset.y, null);
        g.dispose();
        doc.pushLayerEdit("Paste", layer, before);
    }

    private void clear() {
        PaintLayer layer = doc.getActiveLayer();
        if (layer == null) {
            return;
        }
        BufferedImage before = layer.snapshot();
        Selection sel = canvas.getContext().getSelection();
        if (sel != null && !sel.isEmpty()) {
            sel.clear(layer.getImage());
        } else {
            Graphics2D g = layer.getImage().createGraphics();
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, layer.getWidth(), layer.getHeight());
            g.dispose();
        }
        doc.pushLayerEdit("Clear", layer, before);
    }

    private void cropToSelection() {
        Selection sel = canvas.getContext().getSelection();
        if (sel == null || sel.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No selection to crop to.",
                    "Crop", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        doc.crop(sel.getBounds());
        canvas.getContext().setSelection(null);
    }

    // ------------------------------------------------------------------
    // Image transforms
    // ------------------------------------------------------------------

    private void resizeImage() {
        ResizeDialog d = new ResizeDialog(this, doc.getWidth(), doc.getHeight());
        if (d.showDialog()) {
            doc.resize(d.getNewWidth(), d.getNewHeight());
        }
    }

    private void rotateImage() {
        RotateDialog d = new RotateDialog(this);
        if (d.showDialog()) {
            doc.rotate(d.getDegrees());
        }
    }

    // ------------------------------------------------------------------
    // Filters with live preview
    // ------------------------------------------------------------------

    private void brightnessContrast() {
        FilterDialog.Param[] params = {
            new FilterDialog.Param("brightness", "Brightness", -100, 100, 0),
            new FilterDialog.Param("contrast", "Contrast %", 0, 300, 100),
        };
        showFilter("Brightness / Contrast", params,
                new FilterDialog.FilterFactory() {
                    public ImageOps.Filter build(Map<String, Integer> v) {
                        return ImageOps.brightnessContrast(
                                v.get("brightness"), v.get("contrast") / 100.0f);
                    }
                });
    }

    private void hueSaturation() {
        FilterDialog.Param[] params = {
            new FilterDialog.Param("hue", "Hue", -180, 180, 0),
            new FilterDialog.Param("sat", "Saturation %", 0, 200, 100),
        };
        showFilter("Hue / Saturation", params,
                new FilterDialog.FilterFactory() {
                    public ImageOps.Filter build(Map<String, Integer> v) {
                        return ImageOps.hueSaturation(v.get("hue"),
                                v.get("sat") / 100.0f);
                    }
                });
    }

    private void posterize() {
        FilterDialog.Param[] params = {
            new FilterDialog.Param("levels", "Levels", 2, 32, 4),
        };
        showFilter("Posterize", params, new FilterDialog.FilterFactory() {
            public ImageOps.Filter build(Map<String, Integer> v) {
                return ImageOps.posterize(v.get("levels"));
            }
        });
    }

    private void threshold() {
        FilterDialog.Param[] params = {
            new FilterDialog.Param("t", "Threshold", 0, 255, 128),
        };
        showFilter("Threshold", params, new FilterDialog.FilterFactory() {
            public ImageOps.Filter build(Map<String, Integer> v) {
                return ImageOps.threshold(v.get("t"));
            }
        });
    }

    private void blur() {
        FilterDialog.Param[] params = {
            new FilterDialog.Param("radius", "Radius", 1, 20, 2),
        };
        showFilter("Blur", params, new FilterDialog.FilterFactory() {
            public ImageOps.Filter build(Map<String, Integer> v) {
                return ImageOps.blur(v.get("radius"));
            }
        });
    }

    private void showFilter(String title, FilterDialog.Param[] params,
            FilterDialog.FilterFactory factory) {
        PaintLayer layer = doc.getActiveLayer();
        BufferedImage source = (layer == null) ? doc.getComposite()
                : layer.getImage();
        FilterDialog d = new FilterDialog(this, title, source, params, factory);
        if (d.showDialog()) {
            doc.applyFilterToActive(title, d.getFilter());
        }
    }

    // ------------------------------------------------------------------

    private void updateActions() {
        if (undoAction != null) {
            undoAction.setEnabled(doc.canUndo());
        }
        if (redoAction != null) {
            redoAction.setEnabled(doc.canRedo());
        }
    }

    /** Exposed for tests / the launcher to reach the canvas if needed. */
    public Component getCanvasComponent() {
        return canvas;
    }
}
