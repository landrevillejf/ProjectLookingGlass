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
import javax.swing.JComponent;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.StyledDocument;

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

    private final StyledDocument document;
    private final javax.swing.JTextPane textPane;
    private Color background = Color.LIGHT_GRAY;
    private Color foreground = Color.DARK_GRAY;
    private Color activeForeground = Color.BLACK;
    private int caretLine = 0;

    public LineNumberGutter(StyledDocument document,
            javax.swing.JTextPane pane) {
        this.document = document;
        this.textPane = pane;
        setOpaque(true);
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

    /** Recomputes and applies the preferred width for the current font. */
    public void refreshWidth() {
        Font font = editorFont();
        FontMetrics fm = getFontMetrics(font);
        int lines = Math.max(1, document.getDefaultRootElement().getElementCount());
        int digits = Math.max(3, Integer.toString(lines).length());
        int width = PAD_X * 2 + fm.charWidth('9') * digits;
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
                }
                line++;
            }
        } finally {
            g2.dispose();
        }
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
