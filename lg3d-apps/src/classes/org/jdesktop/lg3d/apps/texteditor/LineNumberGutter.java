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
package org.jdesktop.lg3d.apps.texteditor;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.StyledDocument;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic;

/**
 * The line-number margin, installed as the row header of the editor's scroll
 * pane so it tracks vertical scrolling (and word-wrapped line heights) for
 * free. It paints the number of every visible line, right-aligned, with the
 * caret's line emphasised in the theme's foreground colour.
 *
 * <p>Line geometry is read from the {@link StyledDocument}'s paragraph root
 * (O(log n) offset lookup) and from {@code modelToView} for the exact y of
 * each visible line, so painting cost is proportional to the visible lines,
 * never to the document size. The width is derived from the editor font's
 * metrics &mdash; never from {@link #getFont()}, which is null on a peer-less
 * component in headless tests.</p>
 */
public class LineNumberGutter extends JComponent {

    /** Horizontal breathing room on both sides of the numbers. */
    private static final int PAD_X = 6;

    /** Reserved left column width for diagnostic / breakpoint glyphs. */
    private static final int MARKER_W = 12;

    /** Diameter of a diagnostic dot; a breakpoint fills the column edge to edge. */
    private static final int MARKER_DIAM = 7;

    private final StyledDocument document;
    private final javax.swing.JTextPane textPane;
    private Color background = Color.LIGHT_GRAY;
    private Color foreground = Color.DARK_GRAY;
    private Color activeForeground = Color.BLACK;
    private int caretLine = 0;

    /** 0-based line -> highest-severity diagnostic marker to paint on it. */
    private final Map<Integer, Diagnostic.Kind> markers = new HashMap<>();
    /** 0-based lines the user has toggled a breakpoint onto. */
    private final Set<Integer> breakpoints = new HashSet<>();

    public LineNumberGutter(StyledDocument document,
            javax.swing.JTextPane pane) {
        this.document = document;
        this.textPane = pane;
        setOpaque(true);
        installBreakpointClick();
    }

    /**
     * A left-click on the gutter toggles a breakpoint on the clicked line —
     * the debugger's entry point (Phase 3); the extension reads the set back
     * through {@code EditorContext.getBreakpoints}. Row headers share the
     * viewport's y coordinate with the text pane, so {@code viewToModel} on
     * the pane maps a gutter point to the model offset directly.
     */
    private void installBreakpointClick() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                int line = lineAtPoint(e.getPoint());
                if (line >= 0) {
                    toggleBreakpoint(line);
                }
            }
        });
    }

    /** @return the 0-based line under the gutter point, or -1 when unmapped. */
    int lineAtPoint(Point p) {
        try {
            int offset = textPane.viewToModel(
                    new Point(0, p.y));
            if (offset < 0) {
                return -1;
            }
            return document.getDefaultRootElement().getElementIndex(offset);
        } catch (RuntimeException rte) {
            return -1; // no view yet (headless / still loading)
        }
    }

    /** Recolours the gutter from a theme; callers repaint afterwards. */
    public void setColors(Color background, Color foreground,
            Color activeForeground) {
        this.background = background;
        this.foreground = foreground;
        this.activeForeground = activeForeground;
    }

    /** The 0-based line the caret sits on; painted in the active colour. */
    public void setCaretLine(int line) {
        if (line != caretLine) {
            caretLine = line;
            repaint();
        }
    }

    /**
     * Replaces the diagnostic markers, keyed by 0-based line. A null argument
     * clears them. When several diagnostics land on one line the caller passes
     * the highest-severity kind for that line.
     */
    public void setMarkers(Map<Integer, Diagnostic.Kind> newMarkers) {
        markers.clear();
        if (newMarkers != null) {
            markers.putAll(newMarkers);
        }
        repaint();
    }

    /** Removes every diagnostic marker from the gutter. */
    public void clearMarkers() {
        if (!markers.isEmpty()) {
            markers.clear();
            repaint();
        }
    }

    /** @return the marker kind painted on the 0-based {@code line}, or null. */
    public Diagnostic.Kind markerAt(int line) {
        return markers.get(line);
    }

    /**
     * Toggles a breakpoint on the 0-based {@code line}.
     *
     * @return true when the line is now broken, false when it was cleared
     */
    public boolean toggleBreakpoint(int line) {
        if (breakpoints.add(line)) {
            repaint();
            return true;
        }
        breakpoints.remove(line);
        repaint();
        return false;
    }

    /** @return true when a breakpoint is set on the 0-based {@code line}. */
    public boolean hasBreakpoint(int line) {
        return breakpoints.contains(line);
    }

    /** @return the 0-based lines carrying a breakpoint, ascending. */
    public List<Integer> breakpointLines() {
        List<Integer> out = new ArrayList<>(breakpoints);
        Collections.sort(out);
        return out;
    }

    /** Removes every breakpoint. */
    public void clearBreakpoints() {
        if (!breakpoints.isEmpty()) {
            breakpoints.clear();
            repaint();
        }
    }

    /** Recomputes and applies the preferred width for the current font. */
    public void refreshWidth() {
        Font font = editorFont();
        FontMetrics fm = getFontMetrics(font);
        int lines = Math.max(1, document.getDefaultRootElement().getElementCount());
        int digits = Math.max(3, Integer.toString(lines).length());
        int width = MARKER_W + PAD_X * 2 + fm.charWidth('9') * digits;
        // Row headers take their width from the preferred size; the scroll
        // pane forces their height to the viewport's.
        setPreferredSize(new Dimension(width, 0));
        revalidate();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(background);
            g2.fillRect(0, 0, getWidth(), getHeight());

            Font font = editorFont();
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            Element root = document.getDefaultRootElement();
            Rectangle clip = g2.getClipBounds();

            // First visible line: ask the text pane what sits at the top of
            // the clip, then walk paragraphs down past the clip's bottom.
            int startOffset = 0;
            try {
                startOffset = textPane.viewToModel(
                        new Point(0, Math.max(0, clip.y)));
            } catch (RuntimeException rte) {
                startOffset = 0; // no view yet: paint from line 1
            }
            int line = root.getElementIndex(startOffset);
            int width = getWidth();
            while (line < root.getElementCount()) {
                Element paragraph = root.getElement(line);
                int y;
                int height;
                try {
                    Rectangle rect = textPane.modelToView(
                            paragraph.getStartOffset());
                    if (rect == null) {
                        break;
                    }
                    y = rect.y;
                    height = rect.height;
                } catch (BadLocationException ble) {
                    break;
                }
                if (y > clip.y + clip.height) {
                    break;
                }
                if (y + height >= clip.y) {
                    String number = Integer.toString(line + 1);
                    g2.setColor((line == caretLine)
                            ? activeForeground : foreground);
                    int textWidth = fm.stringWidth(number);
                    g2.drawString(number, width - PAD_X - textWidth,
                            y + fm.getAscent()
                            + (height - fm.getHeight()) / 2);
                    paintMarker(g2, line, y, height);
                }
                line++;
            }
        } finally {
            g2.dispose();
        }
    }

    /**
     * Paints the breakpoint ring and diagnostic dot (if any) for the 0-based
     * {@code line} into the reserved left column, vertically centred on the
     * line's view rectangle.
     */
    private void paintMarker(Graphics2D g2, int line, int y, int height) {
        int cx = PAD_X;
        int cy = y + height / 2;
        boolean bp = breakpoints.contains(line);
        if (bp) {
            int d = MARKER_DIAM + 3;
            g2.setColor(new Color(0xB0, 0x20, 0x20));
            g2.fillOval(cx - d / 2, cy - d / 2, d, d);
            g2.setColor(background);
            g2.fillOval(cx - d / 2 + 2, cy - d / 2 + 2, d - 4, d - 4);
        }
        Diagnostic.Kind kind = markers.get(line);
        if (kind != null) {
            g2.setColor(markerColor(kind));
            g2.fillOval(cx - MARKER_DIAM / 2, cy - MARKER_DIAM / 2,
                    MARKER_DIAM, MARKER_DIAM);
        }
    }

    private static Color markerColor(Diagnostic.Kind kind) {
        return switch (kind) {
            case ERROR -> new Color(0xE0, 0x40, 0x40);
            case WARNING -> new Color(0xE0, 0xA0, 0x20);
            case INFO -> new Color(0x40, 0x90, 0xE0);
            case HINT -> new Color(0x90, 0x90, 0x90);
        };
    }

    /**
     * The editor's font. Never {@link #getFont()}: a peer-less gutter in a
     * headless test has a null component font, while the text pane always
     * carries one (set by the tab's settings).
     */
    private Font editorFont() {
        Font font = textPane.getFont();
        return (font != null) ? font : new Font("Monospaced", Font.PLAIN, 12);
    }
}
