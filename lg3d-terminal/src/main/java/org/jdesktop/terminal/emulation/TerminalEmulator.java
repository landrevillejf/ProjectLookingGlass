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
package org.jdesktop.terminal.emulation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The terminal emulator: a state machine that processes a byte stream from a
 * PTY and mutates a {@link TerminalState} accordingly. Handles printable
 * characters, C0 control codes (BS, TAB, LF, CR, BEL), and ANSI/VT100 escape
 * sequences (CSI, OSC).
 *
 * <p>The emulator is deliberately single-threaded: call {@link #process(byte)}
 * or {@link #process(byte[], int, int)} from the PTY reader thread. The
 * display reads the {@link TerminalState} snapshot on the EDT.</p>
 *
 * <p>Supported CSI sequences (the common subset): cursor movement (CUU/CUD/CUF/CUB/CUP/HVP),
 * erase (ED/EL), scroll (SU/SD), SGR (colors + styles), DEC private modes
 * (cursor visible, auto-wrap, origin mode), save/restore cursor (DECSC/DECRC),
 * and scroll region (DECSTBM).</p>
 */
public final class TerminalEmulator {
    
    private final TerminalState state;
    private final Consumer<TerminalState> changeListener;
    
    // Parser state machine
    private enum ParseState { GROUND, ESCAPE, CSI, OSC }
    private ParseState parseState = ParseState.GROUND;
    
    // CSI parameter accumulator
    private final List<Integer> params = new ArrayList<>();
    private final StringBuilder currentParam = new StringBuilder();
    private boolean privateMarker = false; // '?' prefix for DEC private modes
    
    // OSC accumulator
    private final StringBuilder oscBuffer = new StringBuilder();
    
    /**
     * Creates a new emulator for the given terminal state.
     *
     * @param state          the state to mutate
     * @param changeListener notified after each state change (may be null)
     */
    public TerminalEmulator(TerminalState state, Consumer<TerminalState> changeListener) {
        this.state = state;
        this.changeListener = changeListener;
    }
    
    /** Returns the terminal state this emulator is driving. */
    public TerminalState getState() {
        return state;
    }
    
    /** Processes a single byte from the PTY. */
    public void process(byte b) {
        int ch = b & 0xFF;
        
        switch (parseState) {
            case GROUND -> processGround(ch);
            case ESCAPE -> processEscape(ch);
            case CSI -> processCsi(ch);
            case OSC -> processOsc(ch);
            default -> { /* unknown state, ignore */ }
        }
    }
    
    /** Processes a range of bytes from the PTY. */
    public void process(byte[] data, int offset, int length) {
        for (int i = 0; i < length; i++) {
            process(data[offset + i]);
        }
    }
    
    // -- GROUND state: printable chars and C0 controls ----------------------
    
    private void processGround(int ch) {
        switch (ch) {
            case 0x07 -> { /* BEL: could trigger an audible bell */ }
            case 0x08 -> state.moveCursorBackward(1);       // BS
            case 0x09 -> state.tab();                        // HT
            case 0x0A, 0x0B, 0x0C -> state.newLine();       // LF, VT, FF
            case 0x0D -> state.carriageReturn();             // CR
            case 0x1B -> parseState = ParseState.ESCAPE;     // ESC
            case 0x00, 0x1C, 0x1D, 0x1E, 0x1F -> { /* NUL/ignored C0 */ }
            default -> {
                if (ch >= 0x20) {
                    // Printable character
                    writeChar((char) ch);
                }
            }
        }
    }
    
    private void writeChar(char ch) {
        // Auto-wrap: if cursor is at the right margin, move to the next line
        if (state.getCursorCol() >= state.getColumns() - 1 && state.isAutoWrap()) {
            state.putChar(ch);
            state.newLine();
            state.setCursorPosition(state.getCursorRow(), 0);
        } else if (state.getCursorCol() >= state.getColumns()) {
            // Past the margin without auto-wrap: clamp
            state.setCursorPosition(state.getCursorRow(), state.getColumns() - 1);
            state.putChar(ch);
        } else {
            state.putChar(ch);
            state.moveCursorForward(1);
        }
        notifyChanged();
    }
    
    // -- ESCAPE state: after ESC -------------------------------------------
    
    private void processEscape(int ch) {
        switch (ch) {
            case '[' -> {
                parseState = ParseState.CSI;
                params.clear();
                currentParam.setLength(0);
                privateMarker = false;
            }
            case ']' -> {
                parseState = ParseState.OSC;
                oscBuffer.setLength(0);
            }
            case '7' -> { state.saveCursor(); notifyChanged(); parseState = ParseState.GROUND; } // DECSC
            case '8' -> { state.restoreCursor(); notifyChanged(); parseState = ParseState.GROUND; } // DECRC
            case 'D' -> { state.newLine(); notifyChanged(); parseState = ParseState.GROUND; }     // IND
            case 'E' -> { state.carriageReturn(); state.newLine(); notifyChanged(); parseState = ParseState.GROUND; } // NEL
            case 'M' -> { state.scrollDown(1); notifyChanged(); parseState = ParseState.GROUND; } // RI
            case 'c' -> { state.clear(); notifyChanged(); parseState = ParseState.GROUND; }       // RIS (full reset)
            default -> parseState = ParseState.GROUND; // Unknown escape, ignore
        }
    }
    
    // -- CSI state: ESC [ ... final byte -----------------------------------
    
    private void processCsi(int ch) {
        if (ch == '?') {
            privateMarker = true;
            return;
        }
        if (ch >= '0' && ch <= '9') {
            currentParam.append((char) ch);
            return;
        }
        if (ch == ';') {
            params.add(parseInt(currentParam.toString(), 0));
            currentParam.setLength(0);
            return;
        }
        // Final byte (0x40-0x7E): dispatch the CSI sequence
        if (ch >= 0x40 && ch <= 0x7E) {
            // Flush last parameter
            params.add(parseInt(currentParam.toString(), 0));
            currentParam.setLength(0);
            dispatchCsi(ch);
            parseState = ParseState.GROUND;
        } else {
            // Invalid byte in CSI, abort
            parseState = ParseState.GROUND;
        }
    }
    
    private void dispatchCsi(int finalByte) {
        if (privateMarker) {
            dispatchDecCsi(finalByte);
            return;
        }
        switch (finalByte) {
            case 'A' -> state.moveCursorUp(param(0, 1));              // CUU
            case 'B' -> state.moveCursorDown(param(0, 1));            // CUD
            case 'C' -> state.moveCursorForward(param(0, 1));         // CUF
            case 'D' -> state.moveCursorBackward(param(0, 1));        // CUB
            case 'E' -> { state.moveCursorDown(param(0, 1)); state.carriageReturn(); } // CNL
            case 'F' -> { state.moveCursorUp(param(0, 1)); state.carriageReturn(); }   // CPL
            case 'G' -> state.setCursorPosition(state.getCursorRow(), param(0, 1) - 1); // CHA
            case 'H' -> setCupPosition();                              // CUP
            case 'J' -> dispatchEraseDisplay();                         // ED
            case 'K' -> dispatchEraseLine();                            // EL
            case 'L' -> state.scrollDown(param(0, 1));                 // IL
            case 'M' -> state.scrollUp(param(0, 1));                   // DL
            case 'S' -> state.scrollUp(param(0, 1));                   // SU
            case 'T' -> state.scrollDown(param(0, 1));                 // SD
            case 'd' -> state.setCursorPosition(param(0, 1) - 1, state.getCursorCol()); // VPA
            case 'm' -> dispatchSgr();                                  // SGR
            case 'r' -> dispatchDecstbm();                              // DECSTBM
            case 's' -> state.saveCursor();                            // save cursor (ANSI)
            case 'u' -> state.restoreCursor();                         // restore cursor (ANSI)
            default -> { /* Unknown CSI, ignore */ }
        }
        notifyChanged();
    }
    
    private void dispatchDecCsi(int finalByte) {
        switch (finalByte) {
            case 'h' -> dispatchDecSet();   // DECSET
            case 'l' -> dispatchDecReset(); // DECRST
            default -> { /* Unknown DEC CSI, ignore */ }
        }
    }
    
    // -- CSI dispatch helpers ----------------------------------------------
    
    private int param(int index, int defaultValue) {
        if (index < params.size()) {
            int v = params.get(index);
            return v == 0 ? defaultValue : v;
        }
        return defaultValue;
    }
    
    private void setCupPosition() {
        int row = param(0, 1) - 1;
        int col = params.size() > 1 ? param(1, 1) - 1 : 0;
        state.setCursorPosition(row, col);
    }
    
    private void dispatchEraseDisplay() {
        int mode = param(0, 0);
        switch (mode) {
            case 0 -> state.clearFromCursorToEnd();
            case 1 -> state.clearFromBeginningToCursor();
            case 2, 3 -> state.clearScreen();
            default -> { /* unknown erase mode, ignore */ }
        }
    }
    
    private void dispatchEraseLine() {
        int mode = param(0, 0);
        switch (mode) {
            case 0 -> state.clearLineRange(state.getCursorRow(), state.getCursorCol(), state.getColumns() - 1);
            case 1 -> state.clearLineRange(state.getCursorRow(), 0, state.getCursorCol());
            case 2 -> state.clearLine(state.getCursorRow());
            default -> { /* unknown erase mode, ignore */ }
        }
    }
    
    private void dispatchSgr() {
        if (params.isEmpty() || (params.size() == 1 && params.get(0) == 0)) {
            state.setCurrentAttr(CharacterAttr.DEFAULT);
            return;
        }
        CharacterAttr attr = state.getCurrentAttr();
        for (int i = 0; i < params.size(); i++) {
            int code = params.get(i);
            attr = applySgrCode(attr, code, params, i);
            // 38/48 consume extra params for 256-color mode
            if (code == 38 || code == 48) {
                if (i + 1 < params.size() && params.get(i + 1) == 5) {
                    i += 2; // skip "5;<color>"
                } else if (i + 1 < params.size() && params.get(i + 1) == 2) {
                    i += 4; // skip "2;<r>;<g>;<b>"
                }
            }
        }
        state.setCurrentAttr(attr);
    }
    
    private CharacterAttr applySgrCode(CharacterAttr attr, int code, List<Integer> allParams, int index) {
        // Standard SGR codes
        if (code == 0) return CharacterAttr.DEFAULT;
        if (code == 1) return attr.withBold(true);
        if (code == 2) return attr.withDim(true);
        if (code == 3) return attr.withItalic(true);
        if (code == 4) return attr.withUnderline(true);
        if (code == 5 || code == 6) return attr.withBlink(true);
        if (code == 7) return attr.withInverse(true);
        if (code == 22) return attr.withBold(false).withDim(false);
        if (code == 23) return attr.withItalic(false);
        if (code == 24) return attr.withUnderline(false);
        if (code == 25) return attr.withBlink(false);
        if (code == 27) return attr.withInverse(false);
        // Standard colors
        if (code >= 30 && code <= 37) return attr.withFg(code - 30);
        if (code == 38) return applyExtendedFg(attr, allParams, index);
        if (code == 39) return attr.withFg(-1); // default fg
        if (code >= 40 && code <= 47) return attr.withBg(code - 40);
        if (code == 48) return applyExtendedBg(attr, allParams, index);
        if (code == 49) return attr.withBg(-1); // default bg
        // Bright colors
        if (code >= 90 && code <= 97) return attr.withFg(code - 90 + 8);
        if (code >= 100 && code <= 107) return attr.withBg(code - 100 + 8);
        // Unknown SGR code, ignore
        return attr;
    }
    
    private CharacterAttr applyExtendedFg(CharacterAttr attr, List<Integer> allParams, int index) {
        if (index + 1 < allParams.size()) {
            int mode = allParams.get(index + 1);
            if (mode == 5 && index + 2 < allParams.size()) {
                return attr.withFg(allParams.get(index + 2)); // 256-color
            }
        }
        return attr;
    }
    
    private CharacterAttr applyExtendedBg(CharacterAttr attr, List<Integer> allParams, int index) {
        if (index + 1 < allParams.size()) {
            int mode = allParams.get(index + 1);
            if (mode == 5 && index + 2 < allParams.size()) {
                return attr.withBg(allParams.get(index + 2)); // 256-color
            }
        }
        return attr;
    }
    
    private void dispatchDecstbm() {
        int top = param(0, 1) - 1;
        int bottom = params.size() > 1 ? param(1, state.getRows()) - 1 : state.getRows() - 1;
        state.setScrollRegion(top, bottom);
        state.setCursorPosition(0, 0);
    }
    
    private void dispatchDecSet() {
        for (int mode : params) {
            switch (mode) {
                case 1 -> state.setOriginMode(false);  // DECCKM: cursor keys normal (ignored for now)
                case 6 -> state.setOriginMode(true);   // DECOM: origin mode
                case 7 -> state.setAutoWrap(true);     // DECAWM: auto-wrap
                case 12 -> {} // cursor blink (ignored)
                case 25 -> state.setCursorVisible(true); // DECTCEM: cursor visible
                case 1049 -> {} // alternate screen buffer (Phase 2)
                default -> { /* unknown DEC mode, ignore */ }
            }
        }
    }
    
    private void dispatchDecReset() {
        for (int mode : params) {
            switch (mode) {
                case 1 -> state.setOriginMode(false);
                case 6 -> state.setOriginMode(false);
                case 7 -> state.setAutoWrap(false);
                case 12 -> {} // cursor blink
                case 25 -> state.setCursorVisible(false);
                case 1049 -> {} // alternate screen buffer (Phase 2)
                default -> { /* unknown DEC mode, ignore */ }
            }
        }
    }
    
    // -- OSC state: ESC ] ... ST -------------------------------------------
    
    private void processOsc(int ch) {
        if (ch == 0x07 || ch == 0x9C) {
            // BEL or ST terminates OSC
            parseState = ParseState.GROUND;
            // OSC content is ignored for now (Phase 2: window title)
        } else if (ch == 0x1B) {
            // ESC might start ST (ESC \)
            parseState = ParseState.GROUND;
        } else {
            oscBuffer.append((char) ch);
        }
    }
    
    // -- Utilities ---------------------------------------------------------
    
    private static int parseInt(String s, int defaultValue) {
        if (s == null || s.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    private void notifyChanged() {
        if (changeListener != null) {
            changeListener.accept(state);
        }
    }
}
