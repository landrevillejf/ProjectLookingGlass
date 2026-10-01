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
package org.jdesktop.lg3d.apps.imageeditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * The image editor's user interface, a compact GIMP-style workspace: a drawing
 * toolbar on top, a layers panel on the right, a filters / undo bar along the
 * bottom, and a scrollable canvas in the centre. One panel serves both the 3D
 * desktop (hosted on a SwingNode inside a Frame3D by the {@code ImageEditor}
 * wrapper) and the 2D/Swing desktop (opened as an MDI internal frame via
 * {@code Desktop2DAppRegistry.PANEL_APPS}).
 *
 * <p>Everything is plain Java 2D over an {@link EditorDocument} of
 * {@link ImageLayer}s, with {@link ToolEngine} drawing primitives,
 * {@link FilterEngine} pixel filters and an {@link UndoStack} of document
 * snapshots. Files are read and written with {@code ImageIO}. No Java 3D, and no
 * file dialog or colour chooser is built until the user asks for one, so the
 * panel constructs and is asserted on headless.</p>
 */
public class ImageEditorPanel extends JPanel {

    /** Preferred width in pixels. */
    public static final int WIDTH_PX = 900;
    /** Preferred height in pixels. */
    public static final int HEIGHT_PX = 620;

    /** Default new-document size. */
    static final int DEFAULT_W = 640;
    static final int DEFAULT_H = 480;

    private EditorDocument document = new EditorDocument(DEFAULT_W, DEFAULT_H);
    private final UndoStack<EditorDocument> undo = new UndoStack<>();

    private ToolEngine.Tool tool = ToolEngine.Tool.BRUSH;
    private Color color = Color.BLACK;
    private float brushWidth = 4f;
    private boolean filled;

    private final Canvas canvas = new Canvas();
    private final DefaultListModel<ImageLayer> layerModel = new DefaultListModel<>();
    private final JList<ImageLayer> layerList = new JList<>(layerModel);
    private final JLabel statusLabel = new JLabel("Ready");
    private final JComboBox<String> toolBox = new JComboBox<>();
    private final JSlider sizeSlider = new JSlider(1, 64, 4);
    private final JCheckBox filledCheck = new JCheckBox("Filled");

    private BufferedImage view;
    private int dragX;
    private int dragY;
    private boolean dragging;

    private Runnable onClose;

    /** Builds the panel with a fresh default document. */
    public ImageEditorPanel() {
        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildCanvasPane(), BorderLayout.CENTER);
        add(buildLayersPane(), BorderLayout.EAST);
        add(buildBottomPane(), BorderLayout.SOUTH);

        refreshLayers();
        refreshView();
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private Component buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(action("New", this::newDocument));
        bar.add(action("Open...", this::openFile));
        bar.add(action("Save...", this::saveFile));
        bar.addSeparator();
        bar.add(new JLabel(" Tool:"));
        for (ToolEngine.Tool t : ToolEngine.Tool.values()) {
            toolBox.addItem(label(t));
        }
        toolBox.setSelectedIndex(0);
        toolBox.addActionListener(e -> tool = toolAt(toolBox.getSelectedIndex()));
        bar.add(toolBox);
        bar.add(action("Colour...", this::chooseColour));
        bar.addSeparator();
        bar.add(new JLabel(" Size:"));
        sizeSlider.setPreferredSize(new Dimension(110, 24));
        sizeSlider.addChangeListener(e -> brushWidth = sizeSlider.getValue());
        bar.add(sizeSlider);
        filledCheck.addActionListener(e -> filled = filledCheck.isSelected());
        bar.add(filledCheck);
        return bar;
    }

    private Component buildCanvasPane() {
        JScrollPane scroll = new JScrollPane(canvas);
        scroll.setBorder(BorderFactory.createTitledBorder("Canvas"));
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                onPress(e.getX(), e.getY());
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                onRelease(e.getX(), e.getY());
            }
        });
        canvas.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                onDrag(e.getX(), e.getY());
            }
        });
        return scroll;
    }

    private Component buildLayersPane() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Layers"));
        panel.setPreferredSize(new Dimension(190, 100));
        layerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        layerList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int i = layerList.getSelectedIndex();
                if (i >= 0) {
                    document.setActiveIndex(i);
                }
            }
        });
        panel.add(new JScrollPane(layerList), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 2));
        buttons.add(action("Add", this::addLayer));
        buttons.add(action("Up", () -> moveLayer(1)));
        buttons.add(action("Down", () -> moveLayer(-1)));
        buttons.add(action("Del", this::deleteLayer));
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private Component buildBottomPane() {
        JPanel panel = new JPanel(new BorderLayout(6, 4));

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        filters.add(new JLabel("Filter:"));
        filters.add(filterButton("Greyscale", () -> applyFilter("grayscale")));
        filters.add(filterButton("Invert", () -> applyFilter("invert")));
        filters.add(filterButton("Sepia", () -> applyFilter("sepia")));
        filters.add(filterButton("Blur", () -> applyFilter("blur")));
        filters.add(filterButton("Sharpen", () -> applyFilter("sharpen")));
        filters.add(filterButton("Edges", () -> applyFilter("edges")));
        filters.add(filterButton("Brighter", () -> applyFilter("brighter")));
        filters.add(filterButton("Darker", () -> applyFilter("darker")));

        JPanel edit = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        edit.add(action("Undo", this::undoEdit));
        edit.add(action("Redo", this::redoEdit));
        JButton close = new JButton("Close");
        close.addActionListener(e -> {
            if (onClose != null) {
                onClose.run();
            }
        });
        edit.add(close);

        JPanel row = new JPanel(new BorderLayout());
        row.add(filters, BorderLayout.WEST);
        row.add(edit, BorderLayout.EAST);
        panel.add(row, BorderLayout.NORTH);

        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        panel.add(statusLabel, BorderLayout.SOUTH);
        return panel;
    }

    private JButton action(String label, Runnable handler) {
        JButton button = new JButton(label);
        button.addActionListener(e -> handler.run());
        return button;
    }

    private JButton filterButton(String label, Runnable handler) {
        return action(label, handler);
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    private void onPress(int x, int y) {
        ImageLayer layer = document.getActiveLayer();
        if (layer == null) {
            return;
        }
        dragX = x;
        dragY = y;
        dragging = true;
        BufferedImage img = layer.getImage();
        if (tool == ToolEngine.Tool.FILL) {
            undo.push(document.copy());
            ToolEngine.fill(img, color);
            refreshView();
            setStatus("Filled layer " + layer.getName());
            dragging = false;
        } else if (tool == ToolEngine.Tool.TEXT) {
            String text = (String) JOptionPane.showInputDialog(this, "Text:",
                    "Add Text", JOptionPane.PLAIN_MESSAGE);
            if (text != null && !text.isBlank()) {
                undo.push(document.copy());
                ToolEngine.drawText(img, color, text, x, y, brushWidth * 4f);
                refreshView();
                setStatus("Added text");
            }
            dragging = false;
        } else if (tool == ToolEngine.Tool.BRUSH || tool == ToolEngine.Tool.ERASER) {
            undo.push(document.copy());
        } else {
            // LINE / RECT / ELLIPSE: snapshot now, draw the final shape on release.
            undo.push(document.copy());
        }
    }

    private void onDrag(int x, int y) {
        if (!dragging) {
            return;
        }
        ImageLayer layer = document.getActiveLayer();
        if (layer == null) {
            return;
        }
        BufferedImage img = layer.getImage();
        if (tool == ToolEngine.Tool.BRUSH) {
            ToolEngine.drawLine(img, color, dragX, dragY, x, y, brushWidth);
            dragX = x;
            dragY = y;
            refreshView();
        } else if (tool == ToolEngine.Tool.ERASER) {
            ToolEngine.erase(img, dragX, dragY, x, y, brushWidth);
            dragX = x;
            dragY = y;
            refreshView();
        }
    }

    private void onRelease(int x, int y) {
        if (!dragging) {
            return;
        }
        dragging = false;
        ImageLayer layer = document.getActiveLayer();
        if (layer == null) {
            return;
        }
        BufferedImage img = layer.getImage();
        switch (tool) {
            case LINE:
                ToolEngine.drawLine(img, color, dragX, dragY, x, y, brushWidth);
                break;
            case RECT:
                ToolEngine.drawRect(img, color, dragX, dragY, x - dragX, y - dragY,
                        brushWidth, filled);
                break;
            case ELLIPSE:
                ToolEngine.drawEllipse(img, color, dragX, dragY, x - dragX, y - dragY,
                        brushWidth, filled);
                break;
            default:
                return; // BRUSH / ERASER / FILL / TEXT already committed.
        }
        refreshView();
        setStatus("Drew " + label(tool).toLowerCase());
    }

    /** Applies a named filter to the active layer, with an undo snapshot. */
    void applyFilter(String name) {
        ImageLayer layer = document.getActiveLayer();
        if (layer == null) {
            return;
        }
        undo.push(document.copy());
        BufferedImage src = layer.getImage();
        BufferedImage out;
        switch (name) {
            case "grayscale":
                out = FilterEngine.grayscale(src);
                break;
            case "invert":
                out = FilterEngine.invert(src);
                break;
            case "sepia":
                out = FilterEngine.sepia(src);
                break;
            case "blur":
                out = FilterEngine.blur(src, 1);
                break;
            case "sharpen":
                out = FilterEngine.sharpen(src);
                break;
            case "edges":
                out = FilterEngine.edges(src);
                break;
            case "brighter":
                out = FilterEngine.brightness(src, 1.2f);
                break;
            case "darker":
                out = FilterEngine.brightness(src, 0.8f);
                break;
            default:
                undo.undo(document); // nothing pushed usefully
                return;
        }
        replaceActiveImage(out);
        refreshView();
        setStatus("Applied " + name);
    }

    /** Swaps the active layer's pixels for {@code out} in place. */
    private void replaceActiveImage(BufferedImage out) {
        ImageLayer layer = document.getActiveLayer();
        if (layer == null) {
            return;
        }
        BufferedImage target = layer.getImage();
        Graphics2D g = target.createGraphics();
        g.setComposite(java.awt.AlphaComposite.Src);
        g.drawImage(out, 0, 0, null);
        g.dispose();
    }

    private void undoEdit() {
        EditorDocument restored = undo.undo(document);
        if (restored != null) {
            document = restored;
            refreshLayers();
            refreshView();
            setStatus("Undo");
        }
    }

    private void redoEdit() {
        EditorDocument restored = undo.redo(document);
        if (restored != null) {
            document = restored;
            refreshLayers();
            refreshView();
            setStatus("Redo");
        }
    }

    // ------------------------------------------------------------------
    // Layers
    // ------------------------------------------------------------------

    private void addLayer() {
        undo.push(document.copy());
        document.addLayer("Layer " + (document.layerCount() + 1));
        refreshLayers();
        refreshView();
        setStatus("Added a layer");
    }

    private void deleteLayer() {
        int i = layerList.getSelectedIndex();
        if (i < 0) {
            i = document.getActiveIndex();
        }
        if (document.layerCount() <= 1) {
            setStatus("The last layer cannot be removed.");
            return;
        }
        undo.push(document.copy());
        document.removeLayer(i);
        refreshLayers();
        refreshView();
        setStatus("Removed a layer");
    }

    private void moveLayer(int direction) {
        int i = layerList.getSelectedIndex();
        if (i < 0) {
            i = document.getActiveIndex();
        }
        boolean moved = (direction > 0) ? document.moveUp(i) : document.moveDown(i);
        if (moved) {
            refreshLayers();
            refreshView();
        }
    }

    private void refreshLayers() {
        layerModel.clear();
        List<ImageLayer> layers = document.layers();
        // Show top-most first, which reads naturally in a layers panel.
        for (int i = layers.size() - 1; i >= 0; i--) {
            layerModel.addElement(layers.get(i));
        }
        int active = document.getActiveIndex();
        int row = layers.size() - 1 - active;
        if (row >= 0 && row < layerModel.size()) {
            layerList.setSelectedIndex(row);
        }
    }

    // ------------------------------------------------------------------
    // File I/O and document
    // ------------------------------------------------------------------

    private void newDocument() {
        undo.push(document.copy());
        document = new EditorDocument(DEFAULT_W, DEFAULT_H);
        undo.clear();
        refreshLayers();
        refreshView();
        setStatus("New " + DEFAULT_W + "x" + DEFAULT_H + " document");
    }

    private void openFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Images", "png", "jpg", "jpeg", "gif", "bmp", "wbmp"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadImage(chooser.getSelectedFile());
        }
    }

    /** Reads an image file into a fresh single-layer document. */
    void loadImage(File file) {
        if (file == null) {
            return;
        }
        try {
            BufferedImage img = ImageIO.read(file);
            if (img == null) {
                setStatus("Unsupported image: " + file.getName());
                return;
            }
            undo.push(document.copy());
            document = new EditorDocument(img.getWidth(), img.getHeight());
            Graphics2D g = document.getActiveLayer().getImage().createGraphics();
            g.setComposite(java.awt.AlphaComposite.Src);
            g.drawImage(img, 0, 0, null);
            g.dispose();
            undo.clear();
            refreshLayers();
            refreshView();
            setStatus("Opened " + file.getName());
        } catch (IOException | RuntimeException ex) {
            setStatus("Could not open " + file.getName() + " (" + ex.getMessage() + ")");
        }
    }

    private void saveFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("PNG image", "png"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".png")) {
                file = new File(file.getAbsolutePath() + ".png");
            }
            saveImage(file);
        }
    }

    /** Writes the flattened composite to {@code file} as PNG. */
    boolean saveImage(File file) {
        if (file == null) {
            return false;
        }
        try {
            ImageIO.write(document.composite(), "png", file);
            setStatus("Saved " + file.getName());
            return true;
        } catch (IOException | RuntimeException ex) {
            setStatus("Could not save " + file.getName() + " (" + ex.getMessage() + ")");
            return false;
        }
    }

    private void chooseColour() {
        Color picked = JColorChooser.showDialog(this, "Choose Colour", color);
        if (picked != null) {
            color = picked;
            setStatus("Colour " + Integer.toHexString(picked.getRGB() & 0xffffff));
        }
    }

    private void refreshView() {
        view = document.composite();
        canvas.setPreferredSize(new Dimension(view.getWidth(), view.getHeight()));
        canvas.revalidate();
        canvas.repaint();
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private static String label(ToolEngine.Tool t) {
        switch (t) {
            case RECT:
                return "Rectangle";
            case ELLIPSE:
                return "Ellipse";
            default:
                return t.name().charAt(0) + t.name().substring(1).toLowerCase();
        }
    }

    private static ToolEngine.Tool toolAt(int index) {
        ToolEngine.Tool[] values = ToolEngine.Tool.values();
        return (index >= 0 && index < values.length) ? values[index] : values[0];
    }

    /** Wires the panel's Close button (used by both desktop hosts). */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    // ------------------------------------------------------------------
    // Test hooks
    // ------------------------------------------------------------------

    /** The current document model. */
    EditorDocument document() {
        return document;
    }

    /** The current tool. */
    ToolEngine.Tool tool() {
        return tool;
    }

    /** Selects the current tool (package-private for tests). */
    void setTool(ToolEngine.Tool tool) {
        this.tool = (tool == null) ? ToolEngine.Tool.BRUSH : tool;
    }

    /** The current status text. */
    String statusText() {
        return statusLabel.getText();
    }

    /** The undo history. */
    UndoStack<EditorDocument> undoStack() {
        return undo;
    }

    /** Paints the cached composite into the canvas. */
    private final class Canvas extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (view != null) {
                g.drawImage(view, 0, 0, null);
            }
        }
    }
}
