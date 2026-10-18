/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file except in
 * compliance with the License. A copy of the License is available at
 * http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.terminal.display;

import org.jdesktop.terminal.emulation.CharacterAttr;
import org.jdesktop.terminal.emulation.CharacterCell;
import org.jdesktop.terminal.emulation.TerminalEmulator;
import org.jdesktop.terminal.emulation.TerminalState;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.io.OutputStream;

/**
 * The Swing component that renders a terminal. It displays the character grid
 * from a {@link TerminalState}, handles keyboard input (forwarding it to the
 * PTY), manages the scrollback buffer, and repaints on state changes.
 *
 * <p>The display uses a monospaced font and renders each cell individually.
 * Colors are mapped from the 256-color palette to AWT {@link Color} objects.
 * The component is double-buffered via {@code JComponent}'s built-in
 * double buffering.</p>
 */
public final class TerminalDisplay extends JComponent implements TerminalOutput {
    
    // Standard 16-color palette (colors 0-15)
    private static final Color[] BASIC_COLORS = {
        new Color(0, 0, 0),       // 0: black
        new Color(205, 0, 0),     // 1: red
        new Color(0, 205, 0),     // 2: green
        new Color(205, 205, 0),   // 3: yellow
        new Color(0, 0, 238),     // 4: blue
        new Color(205, 0, 205),   // 5: magenta
        new Color(0, 205, 205),   // 6: cyan
        new Color(229, 229, 229), // 7: white
        new Color(127, 127, 127), // 8: bright black
        new Color(255, 0, 0),     // 9: bright red
        new Color(0, 255, 0),     // 10: bright green
        new Color(255, 255, 0),   // 11: bright yellow
        new Color(92, 92, 255),   // 12: bright blue
        new Color(255, 0, 255),   // 13: bright magenta
        new Color(0, 255, 255),   // 14: bright cyan
        new Color(255, 255, 255), // 15: bright white
    };
    
    private static final Color DEFAULT_FG = new Color(204, 204, 204);
    private static final Color DEFAULT_BG = new Color(30, 30, 30);
    private static final Color CURSOR_COLOR = new Color(204, 204, 204, 128);
    
    private final TerminalState state;
    private final TerminalEmulator emulator;
    private final ScrollbackBuffer scrollback;
    private OutputStream ptyOutput;
    
    // Font metrics
    private Font font;
    private int charWidth;
    private int charHeight;
    private int charAscent;
    
    // Scroll offset (how many lines into the scrollback we're viewing)
    private int scrollOffset = 0;
    
    // Cursor blink
    private boolean cursorOn = true;
    private Timer blinkTimer;
    
    /**
     * Creates a new terminal display with the given initial dimensions.
     *
     * @param rows    initial number of rows
     * @param columns initial number of columns
     */
    public TerminalDisplay(int rows, int columns) {
        this.state = new TerminalState(rows, columns);
        this.emulator = new TerminalEmulator(state, s -> repaint());
        this.scrollback = new ScrollbackBuffer(10000, columns);
        
        setFont(new Font("Monospaced", Font.PLAIN, 14));
        setForeground(DEFAULT_FG);
        setBackground(DEFAULT_BG);
        setOpaque(true);
        setFocusable(true);
        setDoubleBuffered(true);
        
        // Keyboard input
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleKeyPress(e);
            }
            
            @Override
            public void keyTyped(KeyEvent e) {
                handleKeyTyped(e);
            }
        });
        
        // Mouse click to focus
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
            }
        });
        
        // Mouse wheel for scrollback
        addMouseWheelListener(e -> {
            int notches = e.getWheelRotation();
            scrollOffset = Math.max(0, Math.min(scrollback.size(), scrollOffset + notches));
            repaint();
        });
        
        // Cursor blink timer
        blinkTimer = new Timer(500, e -> {
            cursorOn = !cursorOn;
            if (state.isCursorVisible()) {
                repaint();
            }
        });
        blinkTimer.start();
        
        // Compute initial font metrics
        updateFontMetrics();
    }
    
    /** Sets the PTY output stream for forwarding keyboard input. */
    public void setPtyOutput(OutputStream output) {
        this.ptyOutput = output;
    }
    
    /** Returns the terminal emulator for feeding data. */
    public TerminalEmulator getEmulator() {
        return emulator;
    }
    
    /** Returns the terminal state. */
    public TerminalState getState() {
        return state;
    }
    
    /** Returns the scrollback buffer. */
    public ScrollbackBuffer getScrollback() {
        return scrollback;
    }
    
    @Override
    public void setFont(Font font) {
        super.setFont(font);
        updateFontMetrics();
    }
    
    private void updateFontMetrics() {
        FontMetrics fm = getFontMetrics(font != null ? font : getFont());
        if (fm != null) {
            charWidth = fm.charWidth('M');
            charHeight = fm.getHeight();
            charAscent = fm.getAscent();
        }
    }
    
    @Override
    public Dimension getPreferredSize() {
        int w = state.getColumns() * charWidth;
        int h = state.getRows() * charHeight;
        return new Dimension(w, h);
    }
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(font != null ? font : getFont());
            
            int rows = state.getRows();
            int cols = state.getColumns();
            
            for (int r = 0; r < rows; r++) {
                int screenRow = r;
                int y = r * charHeight;
                
                if (scrollOffset > 0 && r == 0) {
                    // Draw scrollback lines at the top
                    for (int s = 0; s < Math.min(scrollOffset, rows); s++) {
                        CharacterCell[] line = scrollback.getLine(scrollOffset - 1 - s);
                        if (line != null) {
                            drawLine(g2, line, cols, s * charHeight);
                        }
                    }
                }
                
                // Draw the terminal grid line
                CharacterCell[] line = new CharacterCell[cols];
                for (int c = 0; c < cols; c++) {
                    line[c] = state.getCell(r, c);
                }
                drawLine(g2, line, cols, y);
                
                // Draw cursor
                if (state.isCursorVisible() && cursorOn && scrollOffset == 0
                        && r == state.getCursorRow()) {
                    int cx = state.getCursorCol() * charWidth;
                    g2.setColor(CURSOR_COLOR);
                    g2.fillRect(cx, y, charWidth, charHeight);
                }
            }
        } finally {
            g2.dispose();
        }
    }
    
    private void drawLine(Graphics2D g2, CharacterCell[] line, int cols, int y) {
        for (int c = 0; c < cols; c++) {
            CharacterCell cell = line[c];
            CharacterAttr attr = cell.getAttr();
            
            // Resolve colors
            Color fg = resolveFg(attr);
            Color bg = resolveBg(attr);
            
            // Draw background
            int x = c * charWidth;
            g2.setColor(bg);
            g2.fillRect(x, y, charWidth, charHeight);
            
            // Draw character
            char ch = cell.getChar();
            if (ch != ' ') {
                g2.setColor(fg);
                Font f = g2.getFont();
                Font styled = f;
                if (attr.isBold()) {
                    styled = styled.deriveFont(Font.BOLD);
                }
                if (attr.isItalic()) {
                    styled = styled.deriveFont(Font.ITALIC);
                }
                if (styled != f) {
                    g2.setFont(styled);
                }
                g2.drawString(String.valueOf(ch), x, y + charAscent);
                if (styled != f) {
                    g2.setFont(f);
                }
                
                // Underline
                if (attr.isUnderline()) {
                    g2.drawLine(x, y + charAscent + 1, x + charWidth, y + charAscent + 1);
                }
            }
        }
    }
    
    private Color resolveFg(CharacterAttr attr) {
        int fg = attr.getFg();
        if (attr.isInverse()) {
            return resolveBg(attr); // swap
        }
        if (fg < 0) return DEFAULT_FG;
        if (fg < BASIC_COLORS.length) return BASIC_COLORS[fg];
        return colorFrom256(fg);
    }
    
    private Color resolveBg(CharacterAttr attr) {
        int bg = attr.getBg();
        if (attr.isInverse()) {
            return resolveFg(attr); // swap
        }
        if (bg < 0) return DEFAULT_BG;
        if (bg < BASIC_COLORS.length) return BASIC_COLORS[bg];
        return colorFrom256(bg);
    }
    
    private Color colorFrom256(int index) {
        if (index < 16) return BASIC_COLORS[index];
        if (index < 232) {
            // 6x6x6 color cube
            int i = index - 16;
            int r = (i / 36) * 51;
            int g = ((i / 6) % 6) * 51;
            int b = (i % 6) * 51;
            return new Color(r, g, b);
        }
        // Grayscale ramp
        int gray = 8 + (index - 232) * 10;
        return new Color(gray, gray, gray);
    }
    
    // -- Keyboard handling --------------------------------------------------
    
    private void handleKeyPress(KeyEvent e) {
        if (ptyOutput == null) return;
        
        byte[] bytes = switch (e.getKeyCode()) {
            case KeyEvent.VK_UP -> new byte[]{0x1B, '[', 'A'};
            case KeyEvent.VK_DOWN -> new byte[]{0x1B, '[', 'B'};
            case KeyEvent.VK_RIGHT -> new byte[]{0x1B, '[', 'C'};
            case KeyEvent.VK_LEFT -> new byte[]{0x1B, '[', 'D'};
            case KeyEvent.VK_HOME -> new byte[]{0x1B, '[', 'H'};
            case KeyEvent.VK_END -> new byte[]{0x1B, '[', 'F'};
            case KeyEvent.VK_PAGE_UP -> new byte[]{0x1B, '[', '5', '~'};
            case KeyEvent.VK_PAGE_DOWN -> new byte[]{0x1B, '[', '6', '~'};
            case KeyEvent.VK_DELETE -> new byte[]{0x1B, '[', '3', '~'};
            case KeyEvent.VK_F1 -> new byte[]{0x1B, 'O', 'P'};
            case KeyEvent.VK_F2 -> new byte[]{0x1B, 'O', 'Q'};
            case KeyEvent.VK_F3 -> new byte[]{0x1B, 'O', 'R'};
            case KeyEvent.VK_F4 -> new byte[]{0x1B, 'O', 'S'};
            case KeyEvent.VK_F5 -> new byte[]{0x1B, '[', '1', '5', '~'};
            case KeyEvent.VK_F6 -> new byte[]{0x1B, '[', '1', '7', '~'};
            case KeyEvent.VK_F7 -> new byte[]{0x1B, '[', '1', '8', '~'};
            case KeyEvent.VK_F8 -> new byte[]{0x1B, '[', '1', '9', '~'};
            case KeyEvent.VK_F9 -> new byte[]{0x1B, '[', '2', '0', '~'};
            case KeyEvent.VK_F10 -> new byte[]{0x1B, '[', '2', '1', '~'};
            case KeyEvent.VK_F11 -> new byte[]{0x1B, '[', '2', '3', '~'};
            case KeyEvent.VK_F12 -> new byte[]{0x1B, '[', '2', '4', '~'};
            case KeyEvent.VK_ENTER -> new byte[]{0x0D};
            case KeyEvent.VK_BACK_SPACE -> new byte[]{0x7F};
            case KeyEvent.VK_TAB -> new byte[]{0x09};
            case KeyEvent.VK_ESCAPE -> new byte[]{0x1B};
            default -> null;
        };
        
        if (bytes != null) {
            sendToPty(bytes);
            e.consume();
        }
    }
    
    private void handleKeyTyped(KeyEvent e) {
        if (ptyOutput == null) return;
        char ch = e.getKeyChar();
        if (ch != KeyEvent.CHAR_UNDEFINED && !e.isActionKey()) {
            // Ctrl+key handling
            if (e.isControlDown() && ch >= 'a' && ch <= 'z') {
                sendToPty(new byte[]{(byte) (ch - 'a' + 1)});
            } else {
                sendToPty(String.valueOf(ch).getBytes());
            }
            e.consume();
        }
    }
    
    private void sendToPty(byte[] bytes) {
        try {
            ptyOutput.write(bytes);
            ptyOutput.flush();
        } catch (IOException ex) {
            // PTY closed, ignore
        }
    }
    
    @Override
    public void dispose() {
        if (blinkTimer != null) {
            blinkTimer.stop();
            blinkTimer = null;
        }
    }
}
