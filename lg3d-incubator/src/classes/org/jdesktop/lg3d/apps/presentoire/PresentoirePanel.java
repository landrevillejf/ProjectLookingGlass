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
package org.jdesktop.lg3d.apps.presentoire;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.jdesktop.lg3d.apps.presentoire.format.DeckCodecs;
import org.jdesktop.lg3d.apps.presentoire.format.DeckFormat;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;

/**
 * A plain-Swing slide-deck viewer: the 2D/Swing desktop face of Presentoire.
 *
 * <p>The panel is an idiomatic document viewer, not a port of the 3D scene: a
 * toolbar (Open, Save As, first / previous / slide&nbsp;n&nbsp;of&nbsp;N / next /
 * last, zoom out / percent / zoom in / fit width), a scrollable canvas that
 * centres the rendered slide when it is smaller than the viewport, and a status
 * line. Slides are rasterised by the AWT-free {@link SlideRenderer} from the very
 * same {@link PresentoireModel} the native-3D {@link Presentoire} renders, so the
 * panel touches no Java&nbsp;3D and runs on a machine without the 3D desktop, and
 * the two desktops can never disagree about a deck's content.</p>
 *
 * <p>Open and Save As go through {@link DeckCodecs}, so the panel reads and
 * writes Presentoire's own {@code .zip} as well as PowerPoint {@code .pptx},
 * legacy {@code .ppt}, LibreOffice {@code .odp} and Markdown {@code .md}; the
 * format is sniffed from a file's content on open (Markdown, having no magic
 * bytes, by its extension) and taken from the chosen extension on save, and the
 * status line names it. Open also accepts several {@code .md} files at once - one
 * slide each - through {@link #openFiles(File[])}. Export is a 2D-panel feature
 * only; the 3D {@link Presentoire} face stays view-only.</p>
 *
 * <p>It is registered in {@code Desktop2DAppRegistry.PANEL_APPS} against the
 * {@link Presentoire} main class, so the one shared start-menu descriptor opens
 * this panel as an MDI internal frame in the 2D/Swing desktop, while the 3D
 * desktop keeps building the {@code Frame3D}. The panel opens on the built-in demo
 * deck and replaces it via a standard {@link JFileChooser} - the SwingNode window
 * capture layer presents that chooser inside the 3D desktop too, so the 2006
 * original's hardcoded modal {@code FileChooser} hack is gone.</p>
 *
 * <p>Per the hosted-panel palette rule, no widget here is given a hand-picked
 * colour: the toolbar, canvas, scroll viewport and status line all wear the active
 * look-and-feel's own colours, and the status line carries meaning by text. The
 * only painted colours are inside the rendered slide bitmap, which is document
 * content (a white page and the deck's own ink), exactly as a PDF page is.</p>
 */
public class PresentoirePanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 760;
    public static final int HEIGHT_PX = 620;

    /** Zoom limits, as multiples of the {@link SlideRenderer#BASE_SIDE_PX} render. */
    private static final float MIN_ZOOM = 0.20f;
    private static final float MAX_ZOOM = 4.0f;
    private static final float ZOOM_STEP = 1.25f;

    private PresentoireModel model = PresentoireModel.demo();
    private DeckFormat sourceFormat;
    private int currentSlide;
    private float zoom = 1.0f;

    private final JButton openButton = new JButton("Open...");
    private final JButton saveButton = new JButton("Save As...");
    private final JButton firstButton = new JButton("|<");
    private final JButton prevButton = new JButton("<");
    private final JButton nextButton = new JButton(">");
    private final JButton lastButton = new JButton(">|");
    private final JButton zoomOutButton = new JButton("-");
    private final JButton zoomInButton = new JButton("+");
    private final JButton fitButton = new JButton("Fit width");

    private final JTextField slideField = new JTextField(4);
    private final JLabel slideCountLabel = new JLabel("of 0");
    private final JLabel zoomLabel = new JLabel("100%");

    private final JLabel slideView = new JLabel();
    private final CenteredSlide slideHolder = new CenteredSlide(slideView);
    private final JScrollPane scroll = new JScrollPane(slideHolder);

    private final JLabel statusLabel = new JLabel(" ");

    public PresentoirePanel() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        slideView.setHorizontalAlignment(SwingConstants.CENTER);
        slideView.setVerticalAlignment(SwingConstants.CENTER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        statusLabel.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));

        add(buildToolbar(), BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        refresh();
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        openButton.addActionListener(e -> chooseFile());
        saveButton.addActionListener(e -> exportFile());
        bar.add(openButton);
        bar.add(saveButton);
        bar.addSeparator();

        firstButton.addActionListener(e -> showSlide(1));
        prevButton.addActionListener(e -> showSlide(currentSlide));
        nextButton.addActionListener(e -> showSlide(currentSlide + 2));
        lastButton.addActionListener(e -> showSlide(model.getSlideCount()));
        bar.add(firstButton);
        bar.add(prevButton);

        slideField.setHorizontalAlignment(JTextField.RIGHT);
        slideField.setToolTipText("Slide number (press Enter)");
        ActionListener jump = e -> {
            try {
                showSlide(Integer.parseInt(slideField.getText().trim()));
            } catch (NumberFormatException nfe) {
                refresh();   // put the field back on the real slide
            }
        };
        slideField.addActionListener(jump);
        bar.add(slideField);
        bar.add(slideCountLabel);

        bar.add(nextButton);
        bar.add(lastButton);
        bar.addSeparator();

        zoomOutButton.addActionListener(e -> setZoom(zoom / ZOOM_STEP));
        bar.add(zoomOutButton);
        bar.add(zoomLabel);
        zoomInButton.addActionListener(e -> setZoom(zoom * ZOOM_STEP));
        bar.add(zoomInButton);
        fitButton.addActionListener(e -> fitToWidth());
        bar.add(fitButton);
        return bar;
    }

    // ------------------------------------------------------------------
    // Document handling
    // ------------------------------------------------------------------

    /** Pops a chooser and opens the selected deck, if the user approves. */
    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        // The accept-all "All Files" filter stays available alongside this one.
        chooser.setFileFilter(
                new FileNameExtensionFilter("Slide decks",
                        "zip", "pptx", "ppt", "odp", "md", "markdown"));
        // Several .md files open as one deck (a slide each); anything else opens singly.
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File[] selected = chooser.getSelectedFiles();
        if (selected.length > 1) {
            openFiles(selected);
        } else {
            openFile(selected.length == 1 ? selected[0] : chooser.getSelectedFile());
        }
    }

    /** Pops a save dialog and exports the current deck in the chosen format. */
    private void exportFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save deck as");
        chooser.setFileFilter(
                new FileNameExtensionFilter("Slide decks",
                        "zip", "pptx", "ppt", "odp", "md", "markdown"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        if (target == null) {
            return;
        }
        if (target.exists()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Overwrite " + target.getName() + "?", "Confirm overwrite",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
        }
        saveFile(target, DeckCodecs.formatFor(target));
    }

    /**
     * Opens {@code file} as a slide deck in any supported format, resetting to
     * the first slide. A file that is not a readable deck leaves the current one
     * in place and is reported on the status line.
     *
     * @param file the deck to open (a {@code .zip}, {@code .pptx}, {@code .ppt},
     *        {@code .odp} or {@code .md}), may be null
     * @return true when the deck opened
     */
    public boolean openFile(File file) {
        if (file == null) {
            statusLabel.setText("Could not open the file");
            return false;
        }
        try {
            DeckFormat format = DeckCodecs.sniff(file);
            PresentoireModel opened = DeckCodecs.open(file, format);
            if (model != null) {
                model.close();
            }
            model = opened;
            sourceFormat = format;
            currentSlide = 0;
            zoom = 1.0f;
            refresh();
            return true;
        } catch (IOException | RuntimeException e) {
            statusLabel.setText("Could not open " + file.getName());
            return false;
        }
    }

    /**
     * Opens several Markdown files as one deck, one slide per file, ordered by
     * name. This is the multi-select half of Open; a single file goes through
     * {@link #openFile(File)} instead, which splits it on {@code ---} and
     * headings. A failure leaves the current deck in place and is reported on the
     * status line.
     *
     * @param files the {@code .md} files to open, may be null or empty
     * @return true when the deck opened
     */
    public boolean openFiles(File[] files) {
        if (files == null || files.length == 0) {
            statusLabel.setText("Could not open the files");
            return false;
        }
        try {
            PresentoireModel opened = DeckCodecs.openMarkdown(files);
            if (model != null) {
                model.close();
            }
            model = opened;
            sourceFormat = DeckFormat.MARKDOWN;
            currentSlide = 0;
            zoom = 1.0f;
            refresh();
            return true;
        } catch (IOException | RuntimeException e) {
            statusLabel.setText("Could not open " + files.length + " files");
            return false;
        }
    }

    /**
     * Saves the current deck to {@code target} in {@code format}. A failure
     * leaves the deck untouched and is reported on the status line.
     *
     * @param target the file to write, may be null
     * @param format the format to write, may be null
     * @return true when the deck was written
     */
    boolean saveFile(File target, DeckFormat format) {
        if (target == null || format == null) {
            statusLabel.setText("Could not save the deck");
            return false;
        }
        try {
            DeckCodecs.export(model, target, format);
            statusLabel.setText("Saved " + target.getName());
            return true;
        } catch (IOException | RuntimeException e) {
            statusLabel.setText("Could not save " + target.getName());
            return false;
        }
    }

    /** Moves to a 1-based slide (clamped) and re-renders. */
    public void showSlide(int oneBasedNumber) {
        int count = model.getSlideCount();
        int target = Math.max(1, Math.min(count, oneBasedNumber)) - 1;
        if (target != currentSlide || slideFieldIsStale()) {
            currentSlide = target;
            refresh();
        }
    }

    /** Zooms so the slide width fills the visible viewport. */
    public void fitToWidth() {
        int viewportWidth = scroll.getViewport().getWidth();
        if (viewportWidth <= 0) {
            return;
        }
        setZoom((viewportWidth - 8) / (float) SlideRenderer.BASE_SIDE_PX);
    }

    /** Sets the zoom (1.0 == 100&nbsp;%), clamped, and re-renders. */
    public void setZoom(float newZoom) {
        float clamped = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
        if (Math.abs(clamped - zoom) < 1e-6f) {
            return;
        }
        zoom = clamped;
        refresh();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** Re-renders the current slide and refreshes every readout. */
    void refresh() {
        int count = model.getSlideCount();
        boolean open = count > 0;

        firstButton.setEnabled(open);
        prevButton.setEnabled(open);
        nextButton.setEnabled(open);
        lastButton.setEnabled(open);
        saveButton.setEnabled(open);
        slideField.setEnabled(open);
        zoomOutButton.setEnabled(open);
        zoomInButton.setEnabled(open);
        fitButton.setEnabled(open);

        if (!open) {
            slideView.setIcon(null);
            slideView.setText("This deck has no slides");
            slideField.setText("");
            slideCountLabel.setText("of 0");
            zoomLabel.setText(Math.round(zoom * 100f) + "%");
            statusLabel.setText("Presentoire - no slides");
            scroll.revalidate();
            scroll.repaint();
            return;
        }

        currentSlide = Math.max(0, Math.min(count - 1, currentSlide));
        int side = Math.round(SlideRenderer.BASE_SIDE_PX * zoom);
        BufferedImage image = SlideRenderer.render(model.getSlide(currentSlide), model, side);
        slideView.setText(null);
        slideView.setIcon(new ImageIcon(image));

        slideField.setText(Integer.toString(currentSlide + 1));
        slideCountLabel.setText("of " + count);
        zoomLabel.setText(Math.round(zoom * 100f) + "%");
        statusLabel.setText("Slide " + (currentSlide + 1) + " of " + count
                + "  -  " + Math.round(zoom * 100f) + "%"
                + (sourceFormat == null
                        ? "  -  built-in demo deck"
                        : "  -  " + sourceFormat.description()));

        scroll.revalidate();
        scroll.repaint();
    }

    private boolean slideFieldIsStale() {
        return !Integer.toString(currentSlide + 1).equals(slideField.getText().trim());
    }

    // ------------------------------------------------------------------
    // Test / inspection accessors (package-private; the desktop does not use them)
    // ------------------------------------------------------------------

    PresentoireModel model() {
        return model;
    }

    int currentSlide() {
        return currentSlide;
    }

    int slideCount() {
        return model.getSlideCount();
    }

    float zoom() {
        return zoom;
    }

    String statusText() {
        return statusLabel.getText();
    }

    Icon slideIcon() {
        return slideView.getIcon();
    }

    /**
     * The scroll viewport's view: centres the slide when it is smaller than the
     * viewport and lets it scroll when it is larger, by reporting "tracks
     * viewport" only while the slide fits.
     */
    private static final class CenteredSlide extends JPanel implements Scrollable {

        CenteredSlide(JLabel slide) {
            super(new GridBagLayout());
            add(slide);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation,
                int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation,
                int direction) {
            return 64;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return getParent() == null
                    || getPreferredSize().width <= getParent().getWidth();
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() == null
                    || getPreferredSize().height <= getParent().getHeight();
        }
    }
}
