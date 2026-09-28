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
package org.jdesktop.lg3d.apps.pdfviewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * A plain-Swing PDF reader: the 2D/Swing desktop face of the PDF Viewer.
 *
 * <p>The panel is an idiomatic document viewer, not a port of any 3D scene: a
 * toolbar (Open, first / previous / page&nbsp;n&nbsp;of&nbsp;N / next / last,
 * zoom out / percent / zoom in / fit width), a scrollable page canvas that
 * centres the rendered page when it is smaller than the viewport, and a status
 * line. Pages are rasterised by the AWT-free {@link PdfDocument} (Apache
 * PDFBox), so the panel itself touches no Java&nbsp;3D and runs on a machine
 * without the 3D desktop.</p>
 *
 * <p>It is registered in {@code Desktop2DAppRegistry.PANEL_APPS} against the
 * {@link PdfViewer} main class, so the one shared start-menu descriptor opens
 * this panel as an MDI internal frame in the 2D/Swing desktop, while the 3D
 * desktop hosts the very same instance on a {@code SwingNode} through
 * {@link PdfViewer} / {@code TitledSwingWindow}. Standard layout managers are
 * used throughout (the host is ordinary Swing in 2D, and the 3D host installs
 * the Metal look-and-feel before construction), and no widget here is a Synth
 * combo box, so it paints correctly offscreen too.</p>
 */
public class PdfViewerPanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 780;
    public static final int HEIGHT_PX = 600;

    /** Zoom limits, as multiples of the 100&nbsp;% (72&nbsp;DPI) render. */
    private static final float MIN_ZOOM = 0.10f;
    private static final float MAX_ZOOM = 8.0f;
    private static final float ZOOM_STEP = 1.25f;

    /** Backdrop behind the page, like every conventional reader. */
    private static final Color BACKDROP = new Color(0x52, 0x56, 0x59);

    private final PdfDocument document = new PdfDocument();
    private float zoom = 1.0f;

    private final JButton openButton = new JButton("Open...");
    private final JButton firstButton = new JButton("|<");
    private final JButton prevButton = new JButton("<");
    private final JButton nextButton = new JButton(">");
    private final JButton lastButton = new JButton(">|");
    private final JButton zoomOutButton = new JButton("-");
    private final JButton zoomInButton = new JButton("+");
    private final JButton fitButton = new JButton("Fit width");

    private final JTextField pageField = new JTextField(4);
    private final JLabel pageCountLabel = new JLabel("of 0");
    private final JLabel zoomLabel = new JLabel("100%");

    private final JLabel pageView = new JLabel();
    private final CenteredPage pageHolder = new CenteredPage(pageView);
    private final JScrollPane scroll = new JScrollPane(pageHolder);

    private final JLabel statusLabel = new JLabel(" ");

    public PdfViewerPanel() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        pageView.setOpaque(true);
        pageView.setBackground(BACKDROP);
        pageView.setHorizontalAlignment(SwingConstants.CENTER);
        pageView.setVerticalAlignment(SwingConstants.CENTER);
        pageHolder.setBackground(BACKDROP);

        scroll.getViewport().setBackground(BACKDROP);
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
        bar.add(openButton);
        bar.addSeparator();

        firstButton.addActionListener(e -> showPage(1));
        prevButton.addActionListener(e -> showPage(document.getCurrentPage() - 1));
        nextButton.addActionListener(e -> showPage(document.getCurrentPage() + 1));
        lastButton.addActionListener(e -> showPage(document.getPageCount()));
        bar.add(firstButton);
        bar.add(prevButton);

        pageField.setHorizontalAlignment(JTextField.RIGHT);
        pageField.setToolTipText("Page number (press Enter)");
        ActionListener jump = e -> {
            try {
                showPage(Integer.parseInt(pageField.getText().trim()));
            } catch (NumberFormatException nfe) {
                refresh();   // put the field back on the real page
            }
        };
        pageField.addActionListener(jump);
        bar.add(pageField);
        bar.add(pageCountLabel);

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

    /** Pops a chooser and opens the selected PDF, if the user approves. */
    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("PDF files", "pdf"));
        if (document.getCurrentFile() != null) {
            File current = new File(document.getCurrentFile());
            if (current.getParentFile() != null) {
                chooser.setCurrentDirectory(current.getParentFile());
            }
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openFile(chooser.getSelectedFile());
        }
    }

    /**
     * Opens {@code file}, resetting to page 1. A file that fails to parse
     * leaves the previous document in place and is reported on the status line.
     *
     * @return true when the file opened
     */
    public boolean openFile(File file) {
        if (file == null || !document.open(file)) {
            statusLabel.setText("Could not open "
                    + (file == null ? "the file" : file.getName()));
            return false;
        }
        zoom = 1.0f;
        refresh();
        return true;
    }

    /** Moves to a 1-based page (clamped) and re-renders. */
    public void showPage(int number) {
        boolean moved = document.setPage(number);
        if (moved || pageFieldIsStale()) {
            refresh();
        }
    }

    /** Zooms so the page width fills the visible viewport. */
    public void fitToWidth() {
        float pageW = document.getPageWidthPoints();
        int viewportW = scroll.getViewport().getWidth();
        if (pageW <= 0f || viewportW <= 0) {
            return;
        }
        setZoom((viewportW - 4) / pageW);
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

    /** Re-renders the current page and refreshes every readout. */
    void refresh() {
        int pages = document.getPageCount();
        boolean open = pages > 0;

        firstButton.setEnabled(open);
        prevButton.setEnabled(open);
        nextButton.setEnabled(open);
        lastButton.setEnabled(open);
        pageField.setEnabled(open);
        zoomOutButton.setEnabled(open);
        zoomInButton.setEnabled(open);
        fitButton.setEnabled(open);

        if (!open) {
            pageView.setIcon(null);
            pageView.setText("No document open - use Open... to choose a PDF file");
            pageView.setForeground(Color.LIGHT_GRAY);
            pageField.setText("");
            pageCountLabel.setText("of 0");
            zoomLabel.setText("100%");
            statusLabel.setText("PDF Viewer - no document open");
            scroll.revalidate();
            scroll.repaint();
            return;
        }

        BufferedImage image = document.renderCurrentPage(zoom);
        if (image != null) {
            pageView.setText(null);
            pageView.setIcon(new ImageIcon(image));
        }
        pageField.setText(Integer.toString(document.getCurrentPage()));
        pageCountLabel.setText("of " + pages);
        zoomLabel.setText(Math.round(zoom * 100f) + "%");

        String name = document.getCurrentFile();
        if (name != null) {
            int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            name = slash >= 0 ? name.substring(slash + 1) : name;
        }
        statusLabel.setText((name == null ? "document" : name)
                + "  -  page " + document.getCurrentPage() + " of " + pages
                + "  -  " + Math.round(zoom * 100f) + "%");

        scroll.revalidate();
        scroll.repaint();
    }

    private boolean pageFieldIsStale() {
        return !Integer.toString(document.getCurrentPage())
                .equals(pageField.getText().trim());
    }

    // ------------------------------------------------------------------
    // Test / inspection accessors (package-private; the desktop does not use them)
    // ------------------------------------------------------------------

    PdfDocument document() {
        return document;
    }

    int currentPage() {
        return document.getCurrentPage();
    }

    int pageCount() {
        return document.getPageCount();
    }

    float zoom() {
        return zoom;
    }

    String statusText() {
        return statusLabel.getText();
    }

    Icon pageIcon() {
        return pageView.getIcon();
    }

    /**
     * The scroll viewport's view: centres the page quad when it is smaller
     * than the viewport and lets it scroll when it is larger, by reporting
     * "tracks viewport" only while the page fits.
     */
    private static final class CenteredPage extends JPanel implements Scrollable {

        CenteredPage(JLabel page) {
            super(new GridBagLayout());
            add(page);
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
