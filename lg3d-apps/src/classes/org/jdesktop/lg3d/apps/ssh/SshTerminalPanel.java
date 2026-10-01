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
package org.jdesktop.lg3d.apps.ssh;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * A lightweight ANSI/VT100 terminal emulator component for Swing.
 *
 * <p>Renders a character-cell grid with full SGR (Select Graphic Rendition)
 * support: 16 basic colors, 256-color palette, 24-bit true color, bold, faint,
 * italic, underline, blink, reverse and strikethrough. Handles cursor movement,
 * line/screen erase, scroll regions, and alternate screen buffer.</p>
 *
 * <p>Performance: output is coalesced — incoming bytes accumulate in a buffer
 * and are flushed to the screen model on a 16 ms timer (~60 fps), so rapid
 * output (e.g. {@code cat /dev/urandom}) does not thrash the EDT.</p>
 *
 * <p>Scrollback: a configurable ring buffer (default 10 000 lines) retains
 * history above the visible viewport.</p>
 */
public final class SshTerminalPanel extends JComponent {

    private static final long serialVersionUID = 1L;

    // ------------------------------------------------------------------
    // Terminal dimensions and scrollback
    // ------------------------------------------------------------------

    private int columns = 80;
    private int rows = 24;
    private int scrollbackLimit = 10000;
    private int fontSize = 14;
    private String fontFamily = "Monospaced";
    private boolean antiAliasing = true;

    private int charWidth;
    private int charHeight;
    private int charAscent;

    // ------------------------------------------------------------------
    // Screen model: each cell is a char + attributes
    // ------------------------------------------------------------------

    private char[][] screenChars;
    private int[][] screenAttrs; // packed: fg(24) | bg(24) | style(8)
    private final Deque<char[]> scrollbackChars = new ArrayDeque<>();
    private final Deque<int[]> scrollbackAttrs = new ArrayDeque<>();
    private int scrollbackSize;

    // Cursor
    private int cursorRow;
    private int cursorCol;
    private boolean cursorVisible = true;
    private boolean cursorBlinkState = true;

    // Current SGR attributes
    private int currentFg = 7;  // default white (index into palette)
    private int currentBg = 0;  // default black
    private int currentStyle;   // bitmask: BOLD|FAINT|ITALIC|UNDERLINE|BLINK|REVERSE|STRIKE

    // Style bitmask constants
    public static final int STYLE_BOLD = 1;
    public static final int STYLE_FAINT = 2;
    public static final int STYLE_ITALIC = 4;
    public static final int STYLE_UNDERLINE = 8;
    public static final int STYLE_BLINK = 16;
    public static final int STYLE_REVERSE = 32;
    public static final int STYLE_STRIKE = 64;

    // Scroll region
    private int scrollTop;
    private int scrollBottom;

    // Alternate screen buffer (for vim, less, etc.)
    private char[][] altScreenChars;
    private int[][] altScreenAttrs;
    private int altCursorRow, altCursorCol;
    private boolean alternateScreen;

    // Saved cursor (DEC SC / DEC RC)
    private int savedCursorRow, savedCursorCol, savedFg, savedBg, savedStyle;

    // ------------------------------------------------------------------
    // ANSI parser state
    // ------------------------------------------------------------------

    private static final int STATE_GROUND = 0;
    private static final int STATE_ESC = 1;
    private static final int STATE_CSI = 2;
    private static final int STATE_OSC = 3;
    private static final int STATE_CHARSET = 4;

    private int parserState = STATE_GROUND;
    private final StringBuilder csiParams = new StringBuilder();
    private final StringBuilder oscString = new StringBuilder();
    private char intermediateChar;

    // ------------------------------------------------------------------
    // Output coalescing
    // ------------------------------------------------------------------

    private final List<byte[]> pendingOutput = new ArrayList<>();
    private javax.swing.Timer flushTimer;
    private static final int FLUSH_INTERVAL_MS = 16; // ~60 fps

    // ------------------------------------------------------------------
    // Colors: standard 16-color ANSI palette + 256-color extension
    // ------------------------------------------------------------------

    private static final Color[] BASE_COLORS = {
        new Color(0, 0, 0),       // 0 black
        new Color(170, 0, 0),     // 1 red
        new Color(0, 170, 0),     // 2 green
        new Color(170, 85, 0),    // 3 yellow/brown
        new Color(0, 0, 170),     // 4 blue
        new Color(170, 0, 170),   // 5 magenta
        new Color(0, 170, 170),   // 6 cyan
        new Color(170, 170, 170), // 7 white
        new Color(85, 85, 85),    // 8 bright black
        new Color(255, 85, 85),   // 9 bright red
        new Color(85, 255, 85),   // 10 bright green
        new Color(255, 255, 85),  // 11 bright yellow
        new Color(85, 85, 255),   // 12 bright blue
        new Color(255, 85, 255),  // 13 bright magenta
        new Color(85, 255, 255),  // 14 bright cyan
        new Color(255, 255, 255)  // 15 bright white
    };

    private static final Color[] PALETTE_256 = new Color[256];
    static {
        System.arraycopy(BASE_COLORS, 0, PALETTE_256, 0, 16);
        // 6x6x6 color cube (indices 16-231)
        int idx = 16;
        for (int r = 0; r < 6; r++) {
            for (int g = 0; g < 6; g++) {
                for (int b = 0; b < 6; b++) {
                    PALETTE_256[idx++] = new Color(
                            r == 0 ? 0 : 55 + r * 40,
                            g == 0 ? 0 : 55 + g * 40,
                            b == 0 ? 0 : 55 + b * 40);
                }
            }
        }
        // Grayscale ramp (indices 232-255)
        for (int i = 0; i < 24; i++) {
            int v = 8 + i * 10;
            PALETTE_256[232 + i] = new Color(v, v, v);
        }
    }

    // True color storage (for 24-bit SGR): index > 255 means true color
    private Color trueColorFg;
    private Color trueColorBg;
    private static final int TRUE_COLOR_FG_FLAG = 0x01000000;
    private static final int TRUE_COLOR_BG_FLAG = 0x02000000;

    // ------------------------------------------------------------------
    // Input listener
    // ------------------------------------------------------------------

    /** Callback for key input to send to the remote shell. */
    public interface InputListener {
        void onKeyInput(byte[] data);
    }

    private InputListener inputListener;

    // ------------------------------------------------------------------
    // Scroll pane
    // ------------------------------------------------------------------

    private final JScrollPane scrollPane;
    private final JScrollBar verticalBar;
    private int scrollOffset; // lines scrolled back from bottom

    // ------------------------------------------------------------------
    // Constructor
    // ------------------------------------------------------------------

    public SshTerminalPanel() {
        setOpaque(true);
        setBackground(Color.BLACK);
        setFocusable(true);
        setDoubleBuffered(true);

        screenChars = new char[rows][columns];
        screenAttrs = new int[rows][columns];
        clearScreen();

        scrollPane = new JScrollPane(this);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        verticalBar = scrollPane.getVerticalScrollBar();
        verticalBar.addAdjustmentListener(e -> {
            if (!e.getValueIsAdjusting()) {
                scrollOffset = verticalBar.getMaximum() - verticalBar.getValue() - verticalBar.getVisibleAmount();
                repaint();
            }
        });

        setFont(new Font(fontFamily, Font.PLAIN, fontSize));
        recalculateMetrics();

        // Coalesced flush timer
        flushTimer = new javax.swing.Timer(FLUSH_INTERVAL_MS, e -> flushPendingOutput());
        flushTimer.setRepeats(true);

        setupKeyHandling();
        setupMouseHandling();
    }

    /** @return the scroll pane wrapping this terminal. */
    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    public void setFontSize(int size) {
        this.fontSize = Math.max(8, Math.min(72, size));
        setFont(new Font(fontFamily, Font.PLAIN, fontSize));
        recalculateMetrics();
        revalidate();
        repaint();
    }

    public int getFontSize() { return fontSize; }

    public void setScrollbackLimit(int lines) {
        this.scrollbackLimit = Math.max(100, Math.min(1000000, lines));
    }

    public void setAntiAliasing(boolean aa) {
        this.antiAliasing = aa;
        repaint();
    }

    public void setInputListener(InputListener listener) {
        this.inputListener = listener;
    }

    public int getColumns() { return columns; }
    public int getRows() { return rows; }

    // ------------------------------------------------------------------
    // Output processing
    // ------------------------------------------------------------------

    /**
     * Feeds raw bytes from the SSH channel into the terminal emulator.
     * Thread-safe: buffers the data and schedules a coalesced flush on the EDT.
     */
    public void feed(byte[] data, int offset, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);
        synchronized (pendingOutput) {
            pendingOutput.add(copy);
        }
        if (!flushTimer.isRunning()) {
            SwingUtilities.invokeLater(() -> flushTimer.start());
        }
    }

    private void flushPendingOutput() {
        List<byte[]> batch;
        synchronized (pendingOutput) {
            if (pendingOutput.isEmpty()) {
                flushTimer.stop();
                return;
            }
            batch = new ArrayList<>(pendingOutput);
            pendingOutput.clear();
        }
        for (byte[] chunk : batch) {
            parseBytes(chunk);
        }
        updateScrollRange();
        repaint();
    }

    // ------------------------------------------------------------------
    // ANSI parser
    // ------------------------------------------------------------------

    private void parseBytes(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (parserState) {
                case STATE_GROUND -> handleGround(c);
                case STATE_ESC -> handleEsc(c);
                case STATE_CSI -> handleCsi(c);
                case STATE_OSC -> handleOsc(c);
                case STATE_CHARSET -> { parserState = STATE_GROUND; }
                default -> { parserState = STATE_GROUND; }
            }
        }
    }

    private void handleGround(char c) {
        switch (c) {
            case 27 -> parserState = STATE_ESC; // ESC
            case '\n' -> lineFeed();
            case '\r' -> cursorCol = 0;
            case '\b' -> { if (cursorCol > 0) cursorCol--; }
            case '\t' -> {
                int next = ((cursorCol / 8) + 1) * 8;
                cursorCol = Math.min(next, columns - 1);
            }
            case 7 -> { /* BEL - ignore */ }
            default -> {
                if (c >= 32) {
                    putChar(c);
                }
            }
        }
    }

    private void handleEsc(char c) {
        switch (c) {
            case '[' -> { parserState = STATE_CSI; csiParams.setLength(0); intermediateChar = 0; }
            case ']' -> { parserState = STATE_OSC; oscString.setLength(0); }
            case '(' , ')' -> parserState = STATE_CHARSET;
            case '7' -> saveCursor();
            case '8' -> restoreCursor();
            case 'M' -> reverseIndex();
            case 'D' -> lineFeed();
            case 'E' -> { cursorCol = 0; lineFeed(); }
            case 'c' -> fullReset();
            case '=' , '>' -> { /* keypad mode - ignore */ }
            default -> { /* unsupported escape */ }
        }
        if (parserState == STATE_ESC) parserState = STATE_GROUND;
    }

    private void handleCsi(char c) {
        if (c == '?' || c == '>' || c == '!') {
            intermediateChar = c;
            return;
        }
        if (c >= '0' && c <= '9' || c == ';' || c == ':') {
            csiParams.append(c);
            return;
        }
        // Final byte - dispatch
        parserState = STATE_GROUND;
        dispatchCsi(c);
    }

    private void handleOsc(char c) {
        if (c == 7 || c == 27) { // BEL or ST terminates OSC
            parserState = STATE_GROUND;
            // OSC sequences (window title, etc.) - we ignore them for now
        } else {
            oscString.append(c);
        }
    }

    private void dispatchCsi(char finalChar) {
        int[] params = parseCsiParams();
        switch (finalChar) {
            case 'A' -> cursorUp(param(params, 0, 1));       // CUU
            case 'B' -> cursorDown(param(params, 0, 1));     // CUD
            case 'C' -> cursorForward(param(params, 0, 1));  // CUF
            case 'D' -> cursorBack(param(params, 0, 1));     // CUB
            case 'E' -> { cursorCol = 0; cursorDown(param(params, 0, 1)); }
            case 'F' -> { cursorCol = 0; cursorUp(param(params, 0, 1)); }
            case 'G' -> cursorCol = clamp(param(params, 0, 1) - 1, 0, columns - 1); // CHA
            case 'H', 'f' -> {                                // CUP
                cursorRow = clamp(param(params, 0, 1) - 1, 0, rows - 1);
                cursorCol = clamp(param(params, 1, 1) - 1, 0, columns - 1);
            }
            case 'J' -> eraseInDisplay(param(params, 0, 0));  // ED
            case 'K' -> eraseInLine(param(params, 0, 0));     // EL
            case 'L' -> insertLines(param(params, 0, 1));     // IL
            case 'M' -> deleteLines(param(params, 0, 1));     // DL
            case 'P' -> deleteChars(param(params, 0, 1));     // DCH
            case '@' -> insertChars(param(params, 0, 1));     // ICH
            case 'S' -> scrollUp(param(params, 0, 1));        // SU
            case 'T' -> scrollDown(param(params, 0, 1));      // SD
            case 'X' -> eraseChars(param(params, 0, 1));      // ECH
            case 'd' -> cursorRow = clamp(param(params, 0, 1) - 1, 0, rows - 1); // VPA
            case 'm' -> handleSgr(params);                     // SGR
            case 'r' -> {                                      // DECSTBM
                scrollTop = clamp(param(params, 0, 1) - 1, 0, rows - 1);
                scrollBottom = clamp(param(params, 1, rows) - 1, scrollTop, rows - 1);
                cursorRow = 0; cursorCol = 0;
            }
            case 's' -> saveCursor();
            case 'u' -> restoreCursor();
            case 'h' -> handleMode(params, true);
            case 'l' -> handleMode(params, false);
            case 'n' -> handleDeviceStatus(params);
            case 'c' -> { /* DA - device attributes, ignore */ }
            case 't' -> { /* window manipulation - ignore */ }
            default -> { /* unsupported CSI */ }
        }
    }

    // ------------------------------------------------------------------
    // SGR (Select Graphic Rendition)
    // ------------------------------------------------------------------

    private void handleSgr(int[] params) {
        if (params.length == 0) { resetAttributes(); return; }
        for (int i = 0; i < params.length; i++) {
            int p = params[i];
            switch (p) {
                case 0 -> resetAttributes();
                case 1 -> currentStyle |= STYLE_BOLD;
                case 2 -> currentStyle |= STYLE_FAINT;
                case 3 -> currentStyle |= STYLE_ITALIC;
                case 4 -> currentStyle |= STYLE_UNDERLINE;
                case 5, 6 -> currentStyle |= STYLE_BLINK;
                case 7 -> currentStyle |= STYLE_REVERSE;
                case 9 -> currentStyle |= STYLE_STRIKE;
                case 22 -> currentStyle &= ~(STYLE_BOLD | STYLE_FAINT);
                case 23 -> currentStyle &= ~STYLE_ITALIC;
                case 24 -> currentStyle &= ~STYLE_UNDERLINE;
                case 25 -> currentStyle &= ~STYLE_BLINK;
                case 27 -> currentStyle &= ~STYLE_REVERSE;
                case 29 -> currentStyle &= ~STYLE_STRIKE;
                case 30, 31, 32, 33, 34, 35, 36, 37 -> {
                    currentFg = p - 30; trueColorFg = null;
                }
                case 38 -> { // Extended foreground
                    i = parseExtendedColor(params, i, true);
                }
                case 39 -> { currentFg = 7; trueColorFg = null; } // Default fg
                case 40, 41, 42, 43, 44, 45, 46, 47 -> {
                    currentBg = p - 40; trueColorBg = null;
                }
                case 48 -> { // Extended background
                    i = parseExtendedColor(params, i, false);
                }
                case 49 -> { currentBg = 0; trueColorBg = null; } // Default bg
                case 90, 91, 92, 93, 94, 95, 96, 97 -> {
                    currentFg = p - 90 + 8; trueColorFg = null;
                }
                case 100, 101, 102, 103, 104, 105, 106, 107 -> {
                    currentBg = p - 100 + 8; trueColorBg = null;
                }
                default -> { /* unsupported SGR parameter */ }
            }
        }
    }

    private int parseExtendedColor(int[] params, int i, boolean foreground) {
        if (i + 1 >= params.length) return i;
        int mode = params[++i];
        if (mode == 5 && i + 1 < params.length) {
            // 256-color: 38;5;n
            int colorIndex = clamp(params[++i], 0, 255);
            if (foreground) { currentFg = colorIndex; trueColorFg = null; }
            else { currentBg = colorIndex; trueColorBg = null; }
        } else if (mode == 2 && i + 3 < params.length) {
            // 24-bit true color: 38;2;r;g;b
            int r = clamp(params[++i], 0, 255);
            int g = clamp(params[++i], 0, 255);
            int b = clamp(params[++i], 0, 255);
            Color c = new Color(r, g, b);
            if (foreground) { trueColorFg = c; currentFg = TRUE_COLOR_FG_FLAG; }
            else { trueColorBg = c; currentBg = TRUE_COLOR_BG_FLAG; }
        }
        return i;
    }

    private void resetAttributes() {
        currentFg = 7; currentBg = 0; currentStyle = 0;
        trueColorFg = null; trueColorBg = null;
    }

    // ------------------------------------------------------------------
    // Mode set/reset (DEC private modes)
    // ------------------------------------------------------------------

    private void handleMode(int[] params, boolean set) {
        for (int p : params) {
            if (intermediateChar == '?') {
                switch (p) {
                    case 1049 -> { // Alternate screen buffer
                        if (set) enterAlternateScreen();
                        else leaveAlternateScreen();
                    }
                    case 25 -> cursorVisible = set;
                    case 1000, 1002, 1003, 1006 -> { /* mouse modes - ignore */ }
                    case 7 -> { /* auto-wrap - always on */ }
                    case 12 -> { /* cursor blink - always on */ }
                    default -> { /* unsupported mode */ }
                }
            }
        }
    }

    private void handleDeviceStatus(int[] params) {
        // DSR - we respond to cursor position requests via the input listener
        if (params.length > 0 && params[0] == 6 && inputListener != null) {
            String response = "\033[" + (cursorRow + 1) + ";" + (cursorCol + 1) + "R";
            inputListener.onKeyInput(response.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------
    // Screen operations
    // ------------------------------------------------------------------

    private void putChar(char c) {
        if (cursorCol >= columns) {
            cursorCol = 0;
            lineFeed();
        }
        screenChars[cursorRow][cursorCol] = c;
        screenAttrs[cursorRow][cursorCol] = packAttrs();
        cursorCol++;
    }

    private int packAttrs() {
        // Pack: style in high byte, bg in middle, fg in low
        return (currentStyle << 24) | ((currentBg & 0xFFFFFF) << 8) | (currentFg & 0xFF)
                | (trueColorFg != null ? TRUE_COLOR_FG_FLAG : 0)
                | (trueColorBg != null ? TRUE_COLOR_BG_FLAG : 0);
    }

    private void lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1);
        } else if (cursorRow < rows - 1) {
            cursorRow++;
        }
    }

    private void reverseIndex() {
        if (cursorRow == scrollTop) {
            scrollDown(1);
        } else if (cursorRow > 0) {
            cursorRow--;
        }
    }

    private void scrollUp(int n) {
        for (int i = 0; i < n; i++) {
            // Push top line of scroll region into scrollback
            if (scrollTop == 0 && !alternateScreen) {
                addToScrollback(screenChars[0], screenAttrs[0]);
            }
            // Shift lines up within scroll region
            for (int r = scrollTop; r < scrollBottom; r++) {
                screenChars[r] = screenChars[r + 1];
                screenAttrs[r] = screenAttrs[r + 1];
            }
            // Clear bottom line
            screenChars[scrollBottom] = new char[columns];
            screenAttrs[scrollBottom] = new int[columns];
            java.util.Arrays.fill(screenChars[scrollBottom], ' ');
        }
    }

    private void scrollDown(int n) {
        for (int i = 0; i < n; i++) {
            for (int r = scrollBottom; r > scrollTop; r--) {
                screenChars[r] = screenChars[r - 1];
                screenAttrs[r] = screenAttrs[r - 1];
            }
            screenChars[scrollTop] = new char[columns];
            screenAttrs[scrollTop] = new int[columns];
            java.util.Arrays.fill(screenChars[scrollTop], ' ');
        }
    }

    private void addToScrollback(char[] line, int[] attrs) {
        char[] lineCopy = new char[columns];
        int[] attrCopy = new int[columns];
        System.arraycopy(line, 0, lineCopy, 0, Math.min(line.length, columns));
        System.arraycopy(attrs, 0, attrCopy, 0, Math.min(attrs.length, columns));
        scrollbackChars.addLast(lineCopy);
        scrollbackAttrs.addLast(attrCopy);
        scrollbackSize++;
        while (scrollbackSize > scrollbackLimit) {
            scrollbackChars.pollFirst();
            scrollbackAttrs.pollFirst();
            scrollbackSize--;
        }
    }

    private void eraseInDisplay(int mode) {
        switch (mode) {
            case 0 -> { // From cursor to end
                eraseInLine(0);
                for (int r = cursorRow + 1; r < rows; r++) clearLine(r);
            }
            case 1 -> { // From start to cursor
                for (int r = 0; r < cursorRow; r++) clearLine(r);
                for (int c = 0; c <= cursorCol && c < columns; c++) {
                    screenChars[cursorRow][c] = ' ';
                    screenAttrs[cursorRow][c] = 0;
                }
            }
            case 2, 3 -> { // Entire screen
                for (int r = 0; r < rows; r++) clearLine(r);
                if (mode == 3) { scrollbackChars.clear(); scrollbackAttrs.clear(); scrollbackSize = 0; }
            }
            default -> { /* unsupported ED mode */ }
        }
    }

    private void eraseInLine(int mode) {
        switch (mode) {
            case 0 -> { for (int c = cursorCol; c < columns; c++) { screenChars[cursorRow][c] = ' '; screenAttrs[cursorRow][c] = 0; } }
            case 1 -> { for (int c = 0; c <= cursorCol && c < columns; c++) { screenChars[cursorRow][c] = ' '; screenAttrs[cursorRow][c] = 0; } }
            case 2 -> clearLine(cursorRow);
            default -> { /* unsupported EL mode */ }
        }
    }

    private void clearLine(int row) {
        java.util.Arrays.fill(screenChars[row], ' ');
        java.util.Arrays.fill(screenAttrs[row], 0);
    }

    private void clearScreen() {
        for (int r = 0; r < rows; r++) {
            screenChars[r] = new char[columns];
            screenAttrs[r] = new int[columns];
            java.util.Arrays.fill(screenChars[r], ' ');
        }
        scrollTop = 0;
        scrollBottom = rows - 1;
        cursorRow = 0;
        cursorCol = 0;
    }

    private void insertLines(int n) {
        for (int i = 0; i < n; i++) {
            for (int r = scrollBottom; r > cursorRow; r--) {
                screenChars[r] = screenChars[r - 1];
                screenAttrs[r] = screenAttrs[r - 1];
            }
            screenChars[cursorRow] = new char[columns];
            screenAttrs[cursorRow] = new int[columns];
            java.util.Arrays.fill(screenChars[cursorRow], ' ');
        }
    }

    private void deleteLines(int n) {
        for (int i = 0; i < n; i++) {
            for (int r = cursorRow; r < scrollBottom; r++) {
                screenChars[r] = screenChars[r + 1];
                screenAttrs[r] = screenAttrs[r + 1];
            }
            screenChars[scrollBottom] = new char[columns];
            screenAttrs[scrollBottom] = new int[columns];
            java.util.Arrays.fill(screenChars[scrollBottom], ' ');
        }
    }

    private void deleteChars(int n) {
        for (int i = 0; i < n; i++) {
            System.arraycopy(screenChars[cursorRow], cursorCol + 1, screenChars[cursorRow], cursorCol, columns - cursorCol - 1);
            System.arraycopy(screenAttrs[cursorRow], cursorCol + 1, screenAttrs[cursorRow], cursorCol, columns - cursorCol - 1);
            screenChars[cursorRow][columns - 1] = ' ';
            screenAttrs[cursorRow][columns - 1] = 0;
        }
    }

    private void insertChars(int n) {
        for (int i = 0; i < n; i++) {
            System.arraycopy(screenChars[cursorRow], cursorCol, screenChars[cursorRow], cursorCol + 1, columns - cursorCol - 1);
            System.arraycopy(screenAttrs[cursorRow], cursorCol, screenAttrs[cursorRow], cursorCol + 1, columns - cursorCol - 1);
            screenChars[cursorRow][cursorCol] = ' ';
            screenAttrs[cursorRow][cursorCol] = 0;
        }
    }

    private void eraseChars(int n) {
        for (int i = 0; i < n && cursorCol + i < columns; i++) {
            screenChars[cursorRow][cursorCol + i] = ' ';
            screenAttrs[cursorRow][cursorCol + i] = 0;
        }
    }

    private void cursorUp(int n) { cursorRow = clamp(cursorRow - n, scrollTop, scrollBottom); }
    private void cursorDown(int n) { cursorRow = clamp(cursorRow + n, scrollTop, scrollBottom); }
    private void cursorForward(int n) { cursorCol = clamp(cursorCol + n, 0, columns - 1); }
    private void cursorBack(int n) { cursorCol = clamp(cursorCol - n, 0, columns - 1); }

    private void saveCursor() {
        savedCursorRow = cursorRow; savedCursorCol = cursorCol;
        savedFg = currentFg; savedBg = currentBg; savedStyle = currentStyle;
    }

    private void restoreCursor() {
        cursorRow = savedCursorRow; cursorCol = savedCursorCol;
        currentFg = savedFg; currentBg = savedBg; currentStyle = savedStyle;
    }

    private void enterAlternateScreen() {
        altScreenChars = screenChars;
        altScreenAttrs = screenAttrs;
        altCursorRow = cursorRow;
        altCursorCol = cursorCol;
        screenChars = new char[rows][columns];
        screenAttrs = new int[rows][columns];
        for (int r = 0; r < rows; r++) java.util.Arrays.fill(screenChars[r], ' ');
        cursorRow = 0; cursorCol = 0;
        alternateScreen = true;
    }

    private void leaveAlternateScreen() {
        if (altScreenChars != null) {
            screenChars = altScreenChars;
            screenAttrs = altScreenAttrs;
            cursorRow = altCursorRow;
            cursorCol = altCursorCol;
            altScreenChars = null;
            altScreenAttrs = null;
        }
        alternateScreen = false;
    }

    private void fullReset() {
        leaveAlternateScreen();
        clearScreen();
        scrollbackChars.clear();
        scrollbackAttrs.clear();
        scrollbackSize = 0;
        resetAttributes();
        cursorVisible = true;
        parserState = STATE_GROUND;
    }

    // ------------------------------------------------------------------
    // Resize
    // ------------------------------------------------------------------

    /** Resizes the terminal grid (called when the component is resized). */
    public void resize(int newCols, int newRows) {
        if (newCols == columns && newRows == rows) return;
        newCols = Math.max(20, newCols);
        newRows = Math.max(5, newRows);

        char[][] newChars = new char[newRows][newCols];
        int[][] newAttrs = new int[newRows][newCols];
        for (int r = 0; r < newRows; r++) {
            java.util.Arrays.fill(newChars[r], ' ');
            if (r < rows && r < newRows) {
                System.arraycopy(screenChars[r], 0, newChars[r], 0, Math.min(columns, newCols));
                System.arraycopy(screenAttrs[r], 0, newAttrs[r], 0, Math.min(columns, newCols));
            }
        }
        screenChars = newChars;
        screenAttrs = newAttrs;
        columns = newCols;
        rows = newRows;
        scrollBottom = rows - 1;
        cursorRow = clamp(cursorRow, 0, rows - 1);
        cursorCol = clamp(cursorCol, 0, columns - 1);
        revalidate();
        repaint();
    }

    /** Auto-resizes based on the component's pixel dimensions. */
    public void autoResize() {
        if (charWidth <= 0 || charHeight <= 0) return;
        int w = getWidth() / charWidth;
        int h = getHeight() / charHeight;
        if (w > 0 && h > 0) resize(w, h);
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        if (antiAliasing) {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
        }

        g2.setColor(getBackground());
        g2.fillRect(0, 0, getWidth(), getHeight());

        Font baseFont = getFont();
        Font boldFont = baseFont.deriveFont(Font.BOLD);
        Font italicFont = baseFont.deriveFont(Font.ITALIC);
        Font boldItalicFont = baseFont.deriveFont(Font.BOLD | Font.ITALIC);

        // Determine which lines to draw (scrollback + screen)
        int totalLines = scrollbackSize + rows;
        int visibleRows = rows;
        int startLine = totalLines - visibleRows - scrollOffset;
        if (startLine < 0) startLine = 0;

        for (int screenY = 0; screenY < visibleRows; screenY++) {
            int lineIdx = startLine + screenY;
            char[] lineChars;
            int[] lineAttrs;
            boolean isCursorLine = false;

            if (lineIdx < scrollbackSize) {
                // From scrollback
                char[] sc = scrollbackChars.stream().skip(lineIdx).findFirst().orElse(null);
                int[] sa = scrollbackAttrs.stream().skip(lineIdx).findFirst().orElse(null);
                if (sc == null) continue;
                lineChars = sc;
                lineAttrs = sa;
            } else {
                // From active screen
                int row = lineIdx - scrollbackSize;
                if (row < 0 || row >= rows) continue;
                lineChars = screenChars[row];
                lineAttrs = screenAttrs[row];
                isCursorLine = (row == cursorRow && scrollOffset == 0);
            }

            int y = screenY * charHeight;
            // Draw background runs for efficiency
            int x = 0;
            while (x < columns && x < lineChars.length) {
                int attr = (lineAttrs != null && x < lineAttrs.length) ? lineAttrs[x] : 0;
                Color bg = unpackBg(attr);
                int style = (attr >>> 24) & 0xFF;
                if ((style & STYLE_REVERSE) != 0) {
                    Color tmp = bg;
                    bg = unpackFg(attr);
                    // fg becomes tmp (handled below)
                }

                // Find run length with same bg
                int runEnd = x + 1;
                while (runEnd < columns && runEnd < lineChars.length) {
                    int nextAttr = (lineAttrs != null && runEnd < lineAttrs.length) ? lineAttrs[runEnd] : 0;
                    int nextStyle = (nextAttr >>> 24) & 0xFF;
                    Color nextBg = unpackBg(nextAttr);
                    if ((nextStyle & STYLE_REVERSE) != 0) nextBg = unpackFg(nextAttr);
                    if (!nextBg.equals(bg)) break;
                    runEnd++;
                }

                if (!bg.equals(getBackground())) {
                    g2.setColor(bg);
                    g2.fillRect(x * charWidth, y, (runEnd - x) * charWidth, charHeight);
                }

                // Draw characters in this run
                for (int cx = x; cx < runEnd; cx++) {
                    char ch = lineChars[cx];
                    if (ch == ' ' || ch == 0) continue;
                    int cAttr = (lineAttrs != null && cx < lineAttrs.length) ? lineAttrs[cx] : 0;
                    int cStyle = (cAttr >>> 24) & 0xFF;
                    Color fg = ((cStyle & STYLE_REVERSE) != 0) ? unpackBg(cAttr) : unpackFg(cAttr);
                    if ((cStyle & STYLE_FAINT) != 0) {
                        fg = new Color(fg.getRed() / 2, fg.getGreen() / 2, fg.getBlue() / 2);
                    }
                    Font f = baseFont;
                    if ((cStyle & STYLE_BOLD) != 0 && (cStyle & STYLE_ITALIC) != 0) f = boldItalicFont;
                    else if ((cStyle & STYLE_BOLD) != 0) f = boldFont;
                    else if ((cStyle & STYLE_ITALIC) != 0) f = italicFont;

                    g2.setFont(f);
                    g2.setColor(fg);
                    g2.drawString(String.valueOf(ch), cx * charWidth, y + charAscent);

                    if ((cStyle & STYLE_UNDERLINE) != 0) {
                        g2.drawLine(cx * charWidth, y + charHeight - 1,
                                (cx + 1) * charWidth - 1, y + charHeight - 1);
                    }
                    if ((cStyle & STYLE_STRIKE) != 0) {
                        int mid = y + charHeight / 2;
                        g2.drawLine(cx * charWidth, mid, (cx + 1) * charWidth - 1, mid);
                    }
                }
                x = runEnd;
            }

            // Draw cursor
            if (isCursorLine && cursorVisible && cursorBlinkState && scrollOffset == 0) {
                int cx = cursorCol * charWidth;
                int cy = screenY * charHeight;
                g2.setColor(new Color(255, 255, 255, 180));
                g2.fillRect(cx, cy, charWidth, charHeight);
                // Redraw char under cursor
                if (cursorCol < lineChars.length && lineChars[cursorCol] != ' '
                        && lineChars[cursorCol] != 0) {
                    g2.setColor(Color.BLACK);
                    g2.setFont(baseFont);
                    g2.drawString(String.valueOf(lineChars[cursorCol]), cx, cy + charAscent);
                }
            }
        }

        g2.dispose();
    }



    // ------------------------------------------------------------------
    // Color unpacking
    // ------------------------------------------------------------------

    private Color unpackFg(int attr) {
        int fg = attr & 0xFF;
        if ((attr & TRUE_COLOR_FG_FLAG) != 0 && trueColorFg != null) return trueColorFg;
        if (fg < 256) return PALETTE_256[fg];
        return PALETTE_256[7]; // default white
    }

    private Color unpackBg(int attr) {
        int bg = (attr >>> 8) & 0xFFFF; // We only use low bits for index
        if ((attr & TRUE_COLOR_BG_FLAG) != 0 && trueColorBg != null) return trueColorBg;
        int bgIdx = (attr >>> 8) & 0xFF;
        if (bgIdx < 256) return PALETTE_256[bgIdx];
        return PALETTE_256[0]; // default black
    }

    // ------------------------------------------------------------------
    // Input handling
    // ------------------------------------------------------------------

    private void setupKeyHandling() {
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                byte[] data = translateKey(e);
                if (data != null && inputListener != null) {
                    inputListener.onKeyInput(data);
                    e.consume();
                }
            }

            @Override
            public void keyTyped(KeyEvent e) {
                char c = e.getKeyChar();
                if (c >= 32 && c != 127 && inputListener != null) {
                    // Only send if not already handled by keyPressed
                    if (!e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
                        inputListener.onKeyInput(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
                        e.consume();
                    }
                }
            }
        });
    }

    private byte[] translateKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ctrl = e.isControlDown();
        boolean alt = e.isAltDown();
        boolean shift = e.isShiftDown();

        // Ctrl+C
        if (ctrl && code == KeyEvent.VK_C) return new byte[]{3};
        // Ctrl+D
        if (ctrl && code == KeyEvent.VK_D) return new byte[]{4};
        // Ctrl+Z
        if (ctrl && code == KeyEvent.VK_Z) return new byte[]{26};
        // Ctrl+L
        if (ctrl && code == KeyEvent.VK_L) return new byte[]{12};
        // Ctrl+A
        if (ctrl && code == KeyEvent.VK_A) return new byte[]{1};
        // Ctrl+E
        if (ctrl && code == KeyEvent.VK_E) return new byte[]{5};
        // Ctrl+K
        if (ctrl && code == KeyEvent.VK_K) return new byte[]{11};
        // Ctrl+U
        if (ctrl && code == KeyEvent.VK_U) return new byte[]{21};
        // Ctrl+W
        if (ctrl && code == KeyEvent.VK_W) return new byte[]{23};
        // Ctrl+R
        if (ctrl && code == KeyEvent.VK_R) return new byte[]{18};
        // Ctrl+T
        if (ctrl && code == KeyEvent.VK_T) return new byte[]{20};
        // Ctrl+Y
        if (ctrl && code == KeyEvent.VK_Y) return new byte[]{25};
        // Ctrl+P / Ctrl+N (history in shell)
        if (ctrl && code == KeyEvent.VK_P) return new byte[]{16};
        if (ctrl && code == KeyEvent.VK_N) return new byte[]{14};
        // Ctrl+B / Ctrl+F
        if (ctrl && code == KeyEvent.VK_B) return new byte[]{2};
        if (ctrl && code == KeyEvent.VK_F) return new byte[]{6};
        // Ctrl+H (backspace)
        if (ctrl && code == KeyEvent.VK_H) return new byte[]{8};
        // Generic Ctrl+letter
        if (ctrl && code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) {
            return new byte[]{(byte)(code - KeyEvent.VK_A + 1)};
        }

        // Special keys
        return switch (code) {
            case KeyEvent.VK_ENTER -> new byte[]{'\r'};
            case KeyEvent.VK_BACK_SPACE -> new byte[]{127};
            case KeyEvent.VK_TAB -> new byte[]{'\t'};
            case KeyEvent.VK_ESCAPE -> new byte[]{27};
            case KeyEvent.VK_UP -> esc("[A");
            case KeyEvent.VK_DOWN -> esc("[B");
            case KeyEvent.VK_RIGHT -> esc("[C");
            case KeyEvent.VK_LEFT -> esc("[D");
            case KeyEvent.VK_HOME -> esc("[H");
            case KeyEvent.VK_END -> esc("[F");
            case KeyEvent.VK_INSERT -> esc("[2~");
            case KeyEvent.VK_DELETE -> esc("[3~");
            case KeyEvent.VK_PAGE_UP -> esc("[5~");
            case KeyEvent.VK_PAGE_DOWN -> esc("[6~");
            case KeyEvent.VK_F1 -> esc("OP");
            case KeyEvent.VK_F2 -> esc("OQ");
            case KeyEvent.VK_F3 -> esc("OR");
            case KeyEvent.VK_F4 -> esc("OS");
            case KeyEvent.VK_F5 -> esc("[15~");
            case KeyEvent.VK_F6 -> esc("[17~");
            case KeyEvent.VK_F7 -> esc("[18~");
            case KeyEvent.VK_F8 -> esc("[19~");
            case KeyEvent.VK_F9 -> esc("[20~");
            case KeyEvent.VK_F10 -> esc("[21~");
            case KeyEvent.VK_F11 -> esc("[23~");
            case KeyEvent.VK_F12 -> esc("[24~");
            default -> null;
        };
    }

    private static byte[] esc(String seq) {
        byte[] s = seq.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[s.length + 1];
        result[0] = 27;
        System.arraycopy(s, 0, result, 1, s.length);
        return result;
    }

    private void setupMouseHandling() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                requestFocusInWindow();
            }
        });
        // Ctrl+mouse wheel = font zoom
        addMouseWheelListener((MouseWheelEvent e) -> {
            if (e.isControlDown()) {
                int delta = e.getWheelRotation();
                setFontSize(fontSize - delta);
                e.consume();
            } else {
                // Scroll back
                int delta = e.getWheelRotation() * 3;
                scrollOffset = clamp(scrollOffset + delta, 0, scrollbackSize);
                verticalBar.setValue(verticalBar.getMaximum() - verticalBar.getVisibleAmount() - scrollOffset);
                repaint();
                e.consume();
            }
        });
    }

    // ------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------

    private void recalculateMetrics() {
        // Defensive: getFont() can return null on a peer-less component in
        // headless tests; fall back to the explicitly-set terminal font.
        Font f = getFont();
        if (f == null) {
            f = new Font(fontFamily, Font.PLAIN, fontSize);
            setFont(f);
        }
        FontMetrics fm = getFontMetrics(f);
        charWidth = fm.charWidth('W');
        charHeight = fm.getHeight();
        charAscent = fm.getAscent();
        // Update preferred size
        setPreferredSize(new java.awt.Dimension(charWidth * columns, charHeight * rows));
        scrollPane.setPreferredSize(new java.awt.Dimension(charWidth * columns, charHeight * rows));
        autoResize();
    }

    private void updateScrollRange() {
        int total = scrollbackSize + rows;
        verticalBar.setMaximum(total);
        verticalBar.setVisibleAmount(rows);
        if (scrollOffset == 0) {
            verticalBar.setValue(verticalBar.getMaximum() - verticalBar.getVisibleAmount());
        }
    }

    /** Clears the terminal screen and scrollback. */
    public void clear() {
        fullReset();
        repaint();
    }

    /** Selects all visible text (for copy). */
    public String getVisibleText() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            sb.append(screenChars[r]).append('\n');
        }
        return sb.toString();
    }

    /** Returns the full scrollback + screen as text. */
    public String getFullText() {
        StringBuilder sb = new StringBuilder();
        for (char[] line : scrollbackChars) {
            sb.append(line).append('\n');
        }
        for (int r = 0; r < rows; r++) {
            sb.append(screenChars[r]).append('\n');
        }
        return sb.toString();
    }

    @Override
    public java.awt.Dimension getPreferredSize() {
        return new java.awt.Dimension(charWidth * columns, charHeight * rows);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int[] parseCsiParams() {
        if (csiParams.isEmpty()) return new int[0];
        String[] parts = csiParams.toString().split(";");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                result[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                result[i] = 0;
            }
        }
        return result;
    }

    private static int param(int[] params, int index, int defaultValue) {
        if (index >= params.length || params[index] == 0) return defaultValue;
        return params[index];
    }
}
