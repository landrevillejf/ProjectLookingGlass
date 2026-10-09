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
package org.jdesktop.lg3d.apps.imagestudio;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.ImageReadParam;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileFilter;

/**
 * The 2D/Swing counterpart of {@link ImageStudioFrame3D}: the same JAI image
 * editor rendered as a plain Swing panel so the 2D desktop can host it as an
 * MDI frame (registered in {@code Desktop2DAppRegistry.PANEL_APPS} under the
 * shared start-menu descriptor's {@code ImageStudioApp} command). It loads no
 * Java 3D and reuses the AWT-free engine verbatim: the {@link EditorModel}
 * (bounded undo/redo plus continuous slider edits), the {@link JaiProcessor}
 * operations and the shared {@link OpCatalog}.
 *
 * <pre>
 *   +-----------+----------------------------------+-------------+
 *   | category  |                                  | Histogram   |
 *   | tabs +    |        canvas (fit-to-view)      | (RGB plot)  |
 *   | op grid   |                                  |             |
 *   | slider    |                                  |             |
 *   | U/R/Reset |                                  |             |
 *   +-----------+----------------------------------+-------------+
 *   | [Open][Save][Save As]  Pictures > thumb thumb ...           |
 *   | status line                                                 |
 *   +-------------------------------------------------------------+
 * </pre>
 *
 * <p>Parameterized operations follow the 3D toolbar's arming semantics: a
 * click arms the op ({@link EditorModel#beginContinuousEdit} captures the
 * baseline), the slider then drives absolute {@link EditorModel#preview}
 * ticks, and the edit commits as one undo entry when another op runs.</p>
 */
public class ImageStudioPanel extends JPanel implements EditorModel.Listener {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 960;
    public static final int HEIGHT_PX = 640;

    /** Slider resolution: the float op ranges are mapped onto 0..1000. */
    private static final int SLIDER_STEPS = 1000;

    private static final Color CANVAS_BG = new Color(0x1a1f2a);
    private static final Color HIST_BG = new Color(0x141821);
    private static final Color[] RGB_COLORS = {
        new Color(230, 80, 80, 150),
        new Color(90, 200, 90, 150),
        new Color(90, 130, 230, 150),
    };
    private static final Color MONO_COLOR = new Color(210, 220, 235, 150);

    /** Histograms are computed on a copy downsampled to this max edge. */
    private static final int HIST_MAX_DIM = 256;
    private static final int THUMB_PX = 56;
    private static final int MAX_THUMBS = 24;

    private static final List<String> EXTS = Arrays.asList(
            "png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff");

    private static final FileFilter IMAGE_FILTER = new FileFilter() {
        public boolean accept(File f) {
            return f.isDirectory() || isImageName(f.getName());
        }

        public String getDescription() {
            return "Images (png, jpeg, gif, bmp, tiff)";
        }
    };

    private final EditorModel model = new EditorModel();
    private final CanvasView canvas = new CanvasView();
    private final HistogramView histogram = new HistogramView();
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel sliderLabel = new JLabel("Parameter");
    private final JLabel sliderValue = new JLabel(" ");
    private final JSlider slider = new JSlider(0, SLIDER_STEPS, 0);
    private final JButton undoButton = new JButton("Undo");
    private final JButton redoButton = new JButton("Redo");
    private final JPanel filmstrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));

    /** The armed parameterized op, or null; committed on the next action. */
    private OpCatalog.OpDef armed;
    private boolean armedActive;
    /** Guards programmatic slider moves from re-entering the preview. */
    private boolean sliderSync;

    public ImageStudioPanel() {
        super(new BorderLayout(4, 4));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        setBorder(new EmptyBorder(6, 6, 6, 6));

        add(buildControls(), BorderLayout.WEST);
        add(canvas, BorderLayout.CENTER);
        add(histogramBox(), BorderLayout.EAST);
        add(buildBottom(), BorderLayout.SOUTH);

        slider.setEnabled(false);
        slider.addChangeListener(e -> onSliderMoved());

        model.addListener(this);
        // Start with a usable placeholder so operations work before any open.
        model.newImage(720, 480);
        rebuildFilmstrip();
    }

    // ------------------------------------------------------------------
    // UI assembly
    // ------------------------------------------------------------------

    private JComponent buildControls() {
        JPanel controls = new JPanel(new BorderLayout(0, 4));
        controls.setPreferredSize(new Dimension(250, 0));
        controls.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 2));

        JTabbedPane tabs = new JTabbedPane(SwingConstants.TOP);
        for (int c = 0; c < OpCatalog.CATEGORIES.length; c++) {
            tabs.addTab(OpCatalog.CATEGORIES[c], buildOpGrid(c));
        }
        tabs.addChangeListener(e -> {
            // Leaving a tab commits any armed slider edit, like the 3D toolbar.
            commitArmed();
            disarmSlider();
        });
        controls.add(new JScrollPane(tabs,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);

        Box south = new Box(BoxLayout.Y_AXIS);
        sliderLabel.setHorizontalAlignment(SwingConstants.CENTER);
        sliderValue.setHorizontalAlignment(SwingConstants.CENTER);
        south.add(sliderLabel);
        south.add(slider);
        south.add(sliderValue);
        south.add(Box.createVerticalStrut(4));
        JPanel actions = new JPanel(new GridLayout(1, 3, 4, 0));
        undoButton.addActionListener(e -> {
            commitArmed();
            disarmSlider();
            model.undo();
        });
        redoButton.addActionListener(e -> {
            commitArmed();
            disarmSlider();
            model.redo();
        });
        JButton reset = new JButton("Reset");
        reset.addActionListener(e -> {
            commitArmed();
            disarmSlider();
            model.reset();
        });
        actions.add(undoButton);
        actions.add(redoButton);
        actions.add(reset);
        south.add(actions);
        controls.add(south, BorderLayout.SOUTH);
        return controls;
    }

    private JPanel buildOpGrid(int category) {
        OpCatalog.OpDef[] ops = OpCatalog.CATALOG[category];
        JPanel grid = new JPanel(new GridLayout(0, 2, 4, 4));
        grid.setBorder(new EmptyBorder(4, 4, 4, 4));
        for (final OpCatalog.OpDef op : ops) {
            JButton b = new JButton(op.label);
            b.setFocusPainted(false);
            b.addActionListener(e -> runOp(op));
            grid.add(b);
        }
        return grid;
    }

    private JComponent histogramBox() {
        JPanel box = new JPanel(new BorderLayout());
        box.setPreferredSize(new Dimension(190, 0));
        JLabel title = new JLabel("Histogram", SwingConstants.CENTER);
        title.setBorder(new EmptyBorder(2, 0, 4, 0));
        box.add(title, BorderLayout.NORTH);
        box.add(histogram, BorderLayout.CENTER);
        return box;
    }

    private JComponent buildBottom() {
        Box bottom = new Box(BoxLayout.Y_AXIS);

        JPanel stripRow = new JPanel(new BorderLayout(4, 0));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton open = new JButton("Open");
        open.addActionListener(e -> openDialog());
        JButton save = new JButton("Save");
        save.addActionListener(e -> {
            commitArmed();
            save();
        });
        JButton saveAs = new JButton("Save As");
        saveAs.addActionListener(e -> saveAsDialog());
        buttons.add(open);
        buttons.add(save);
        buttons.add(saveAs);
        stripRow.add(buttons, BorderLayout.WEST);
        filmstrip.setPreferredSize(new Dimension(0, THUMB_PX + 12));
        stripRow.add(new JScrollPane(filmstrip,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED), BorderLayout.CENTER);
        bottom.add(stripRow);

        statusLabel.setBorder(new EmptyBorder(3, 2, 0, 2));
        bottom.add(statusLabel);
        return bottom;
    }

    // ------------------------------------------------------------------
    // Operation arming (mirrors Toolbar3D semantics)
    // ------------------------------------------------------------------

    /** Runs a catalogued operation by its button label (test/interaction seam). */
    void runOpLabel(String label) {
        runOp(OpCatalog.forLabel(label));
    }

    private void runOp(OpCatalog.OpDef op) {
        if (op == null) {
            return;
        }
        if (op.param) {
            armOp(op);
        } else {
            commitArmed();
            disarmSlider();
            model.apply(OpCatalog.opFor(op.code, 0f), op.label);
        }
    }

    private void armOp(OpCatalog.OpDef op) {
        commitArmed();
        armed = op;
        sliderLabel.setText(op.label);
        int t = Math.round((op.init - op.min) / (op.max - op.min) * SLIDER_STEPS);
        sliderSync = true;
        slider.setEnabled(true);
        slider.setValue(Math.max(0, Math.min(SLIDER_STEPS, t)));
        sliderSync = false;
        updateSliderValueText(op.init);
        armedActive = true;
        model.beginContinuousEdit();
        model.preview(OpCatalog.opFor(op.code, op.init), op.label);
    }

    private void onSliderMoved() {
        if (sliderSync || armed == null) {
            return;
        }
        float v = sliderValue();
        if (!armedActive) {
            model.beginContinuousEdit();
            armedActive = true;
        }
        updateSliderValueText(v);
        model.preview(OpCatalog.opFor(armed.code, v), armed.label);
    }

    /** Drives the armed slider programmatically (test seam), 0..1000. */
    void setSliderPosition(int steps) {
        slider.setValue(Math.max(0, Math.min(SLIDER_STEPS, steps)));
    }

    private float sliderValue() {
        if (armed == null) {
            return 0f;
        }
        return armed.min + (armed.max - armed.min) * slider.getValue() / (float) SLIDER_STEPS;
    }

    private void updateSliderValueText(float v) {
        if (armed == null) {
            return;
        }
        sliderValue.setText(OpCatalog.formatValue(armed.fmt, armed.intFmt, v));
    }

    private void commitArmed() {
        if (armedActive) {
            model.endContinuousEdit();
            armedActive = false;
        }
    }

    private void disarmSlider() {
        armed = null;
        sliderSync = true;
        slider.setEnabled(false);
        sliderSync = false;
        sliderLabel.setText("Parameter");
        sliderValue.setText(" ");
    }

    // ------------------------------------------------------------------
    // File handling (mirrors FileStrip3D)
    // ------------------------------------------------------------------

    private void openDialog() {
        commitArmed();
        disarmSlider();
        try {
            JFileChooser fc = new JFileChooser(dirFile(lastDir()));
            fc.setDialogTitle("Open Image");
            fc.setFileFilter(IMAGE_FILTER);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION
                    && fc.getSelectedFile() != null) {
                loadPath(fc.getSelectedFile().toPath());
            }
        } catch (Throwable t) {
            model.setStatus("Open dialog unavailable: " + t.getClass().getSimpleName());
        }
    }

    private void saveAsDialog() {
        commitArmed();
        try {
            JFileChooser fc = new JFileChooser(dirFile(lastDir()));
            fc.setDialogTitle("Save Image As");
            fc.setSelectedFile(new File(dirFile(lastDir()), suggestName()));
            fc.setFileFilter(IMAGE_FILTER);
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION
                    && fc.getSelectedFile() != null) {
                doSave(fc.getSelectedFile().toPath());
            }
        } catch (Throwable t) {
            model.setStatus("Save dialog unavailable: " + t.getClass().getSimpleName());
        }
    }

    private void save() {
        Path p = model.getPath();
        if (p == null) {
            p = exportPath();
        }
        doSave(p);
    }

    private void doSave(Path path) {
        if (model.getCurrent() == null) {
            model.setStatus("Nothing to save");
            return;
        }
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            String fmt = JaiProcessor.formatForPath(path);
            boolean ok = JaiProcessor.save(model.getCurrent(), path, fmt);
            if (ok) {
                model.setPath(path);
                model.setStatus("Saved " + path.getFileName());
            } else {
                model.setStatus("No writer available for " + fmt);
            }
        } catch (Throwable t) {
            model.setStatus("Save failed: " + t.getClass().getSimpleName());
        }
    }

    private void loadPath(Path p) {
        commitArmed();
        disarmSlider();
        try {
            BufferedImage img = JaiProcessor.load(p);
            model.setImage(img, p);
        } catch (Throwable t) {
            model.setStatus("Could not open " + p.getFileName()
                    + ": " + t.getClass().getSimpleName());
        }
    }

    private void rebuildFilmstrip() {
        filmstrip.removeAll();
        List<Path> files = scanPictures();
        if (files.isEmpty()) {
            JLabel hint = new JLabel("No images found in ~/Pictures");
            hint.setForeground(Color.GRAY);
            filmstrip.add(hint);
            return;
        }
        int shown = 0;
        for (final Path p : files) {
            if (shown++ >= MAX_THUMBS) {
                break;
            }
            BufferedImage thumb = readThumb(p.toFile(), THUMB_PX);
            JButton cell;
            if (thumb != null) {
                cell = new JButton(new ImageIcon(thumb));
            } else {
                cell = new JButton(stripExt(p.getFileName().toString()));
            }
            cell.setToolTipText(p.getFileName().toString());
            cell.setFocusPainted(false);
            cell.setMargin(new Insets(1, 1, 1, 1));
            cell.addActionListener(e -> loadPath(p));
            filmstrip.add(cell);
        }
        filmstrip.revalidate();
        filmstrip.repaint();
    }

    private static List<Path> scanPictures() {
        List<Path> out = new ArrayList<Path>();
        Path dir = Paths.get(System.getProperty("user.home"), "Pictures");
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && isImageName(p.getFileName().toString())) {
                    out.add(p);
                }
            }
        } catch (Throwable t) {
            // ignore scan failures; an empty strip is acceptable
        }
        Collections.sort(out);
        return out;
    }

    /** Decode a subsampled thumbnail so full-resolution data is never loaded. */
    private static BufferedImage readThumb(File f, int target) {
        ImageInputStream iis = null;
        ImageReader reader = null;
        try {
            iis = ImageIO.createImageInputStream(f);
            if (iis == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            reader = readers.next();
            reader.setInput(iis, true, true);
            int w = reader.getWidth(0);
            int h = reader.getHeight(0);
            int sub = Math.max(1, Math.max(w, h) / target);
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceSubsampling(sub, sub, 0, 0);
            return reader.read(0, param);
        } catch (Throwable t) {
            return null;
        } finally {
            if (reader != null) {
                reader.dispose();
            }
            if (iis != null) {
                try {
                    iis.close();
                } catch (Exception ignore) {
                    // nothing useful to do on close failure
                }
            }
        }
    }

    private Path lastDir() {
        Path cur = model.getPath();
        if (cur != null && cur.getParent() != null) {
            return cur.getParent();
        }
        Path pics = Paths.get(System.getProperty("user.home"), "Pictures");
        return Files.isDirectory(pics) ? pics
                : Paths.get(System.getProperty("user.home"));
    }

    private Path exportPath() {
        Path dir = Paths.get(System.getProperty("user.home"),
                "Pictures", "lg3d-imagestudio");
        String base = (model.getPath() != null)
                ? stripExt(model.getPath().getFileName().toString())
                : "image";
        return dir.resolve(base + "-" + System.currentTimeMillis() + ".png");
    }

    private String suggestName() {
        if (model.getPath() != null) {
            return model.getPath().getFileName().toString();
        }
        return "image-" + System.currentTimeMillis() + ".png";
    }

    private static File dirFile(Path dir) {
        File f = (dir != null) ? dir.toFile() : null;
        return (f != null && f.isDirectory()) ? f
                : new File(System.getProperty("user.home"));
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    private static boolean isImageName(String name) {
        String lower = name.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            return false;
        }
        return EXTS.contains(lower.substring(dot + 1));
    }

    // ------------------------------------------------------------------
    // Model listener + test seams
    // ------------------------------------------------------------------

    /** EditorModel.Listener: refresh every view that mirrors the model. */
    public void modelChanged(EditorModel m) {
        statusLabel.setText(m.getStatus());
        undoButton.setEnabled(m.canUndo());
        redoButton.setEnabled(m.canRedo());
        canvas.repaint();
        histogram.repaint();
    }

    /** The reused editing model (test seam). */
    EditorModel model() {
        return model;
    }

    /** The status line text (test seam). */
    String statusText() {
        return statusLabel.getText();
    }

    // ------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------

    /** The image canvas: the current image drawn fit-to-view, centred. */
    private final class CanvasView extends JComponent {
        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setColor(CANVAS_BG);
            g.fillRect(0, 0, getWidth(), getHeight());
            BufferedImage img = model.getCurrent();
            if (img == null) {
                return;
            }
            double s = Math.min((getWidth() - 8.0) / img.getWidth(),
                    (getHeight() - 8.0) / img.getHeight());
            int w = Math.max(1, (int) Math.round(img.getWidth() * s));
            int h = Math.max(1, (int) Math.round(img.getHeight() * s));
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
        }
    }

    /** The 256-bin RGB histogram, redrawn from the model after every change. */
    private final class HistogramView extends JComponent {
        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setColor(HIST_BG);
            g.fillRect(0, 0, getWidth(), getHeight());
            BufferedImage img = model.getCurrent();
            if (img == null) {
                return;
            }
            javax.media.jai.Histogram h = null;
            try {
                h = JaiProcessor.histogram(downsample(img));
            } catch (Throwable t) {
                h = null;
            }
            if (h == null) {
                return;
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int numBands = h.getNumBands();
            double logMax = 1.0;
            for (int b = 0; b < numBands; b++) {
                int[] bins = h.getBins(b);
                for (int bin : bins) {
                    double lv = Math.log(1.0 + bin);
                    if (lv > logMax) {
                        logMax = lv;
                    }
                }
            }
            if (numBands == 1) {
                drawBand(g, h.getBins(0), logMax, MONO_COLOR);
            } else {
                for (int k = 0; k < 3 && k < numBands; k++) {
                    drawBand(g, h.getBins(k), logMax, RGB_COLORS[k]);
                }
            }
        }

        private void drawBand(Graphics2D g, int[] bins, double logMax, Color color) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0 || bins == null || bins.length == 0) {
                return;
            }
            g.setColor(color);
            for (int i = 0; i < bins.length; i++) {
                int x = i * w / bins.length;
                int bh = (int) Math.round(Math.log(1.0 + bins[i]) / logMax * (h - 4));
                g.drawLine(x, h - 2, x, h - 2 - bh);
            }
        }

        /** Shrinks large images so the histogram stays cheap during drags. */
        private BufferedImage downsample(BufferedImage img) {
            int max = Math.max(img.getWidth(), img.getHeight());
            if (max <= HIST_MAX_DIM) {
                return img;
            }
            float factor = HIST_MAX_DIM / (float) max;
            int w = Math.max(1, Math.round(img.getWidth() * factor));
            int h = Math.max(1, Math.round(img.getHeight() * factor));
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            try {
                g.drawImage(img, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }
            return out;
        }
    }
}
