/**
 * Project Looking Glass
 *
 * Copyright (c) 2026 Project Looking Glass contributors.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.event.KeyEvent;

import org.jdesktop.lg3d.wg.event.MouseEvent3D;
import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for the pure key/button mapping tables in
 * {@link X11InputForwarder}. These exercise the AWT virtual-key-code to X11
 * keysym translation and the lg3d-to-X button mapping without a live
 * {@code Display}, {@code XTest}, or any synthetic input injection - the tables
 * are static and side-effect free.
 */
class X11InputForwarderKeysymTest {

    // ---- vkToKeysym: letters, digits, ASCII punctuation ----

    @Test
    void lettersMapToLowercaseBaseKeysyms() {
        // Base (unshifted) keysym so the server applies Shift/Ctrl/Alt itself.
        assertEquals(0x61, X11InputForwarder.vkToKeysym(KeyEvent.VK_A));
        assertEquals(0x6d, X11InputForwarder.vkToKeysym(KeyEvent.VK_M));
        assertEquals(0x7a, X11InputForwarder.vkToKeysym(KeyEvent.VK_Z));
    }

    @Test
    void digitsMapToTheirAsciiKeysyms() {
        assertEquals(0x30, X11InputForwarder.vkToKeysym(KeyEvent.VK_0));
        assertEquals(0x39, X11InputForwarder.vkToKeysym(KeyEvent.VK_9));
    }

    @Test
    void asciiPunctuationWhoseVkEqualsCharMapsDirectly() {
        assertEquals(0x2c, X11InputForwarder.vkToKeysym(KeyEvent.VK_COMMA));        // ,
        assertEquals(0x2d, X11InputForwarder.vkToKeysym(KeyEvent.VK_MINUS));        // -
        assertEquals(0x2e, X11InputForwarder.vkToKeysym(KeyEvent.VK_PERIOD));       // .
        assertEquals(0x2f, X11InputForwarder.vkToKeysym(KeyEvent.VK_SLASH));        // /
        assertEquals(0x3b, X11InputForwarder.vkToKeysym(KeyEvent.VK_SEMICOLON));    // ;
        assertEquals(0x3d, X11InputForwarder.vkToKeysym(KeyEvent.VK_EQUALS));       // =
        assertEquals(0x5b, X11InputForwarder.vkToKeysym(KeyEvent.VK_OPEN_BRACKET)); // [
        assertEquals(0x5c, X11InputForwarder.vkToKeysym(KeyEvent.VK_BACK_SLASH));   // backslash
        assertEquals(0x5d, X11InputForwarder.vkToKeysym(KeyEvent.VK_CLOSE_BRACKET));// ]
    }

    // ---- vkToKeysym: specials resolved before the ASCII fallback ----

    @Test
    void editingAndWhitespaceKeysMapToFunctionKeysyms() {
        assertEquals(0x0020, X11InputForwarder.vkToKeysym(KeyEvent.VK_SPACE));
        assertEquals(0xff08, X11InputForwarder.vkToKeysym(KeyEvent.VK_BACK_SPACE));
        assertEquals(0xff09, X11InputForwarder.vkToKeysym(KeyEvent.VK_TAB));
        assertEquals(0xff0d, X11InputForwarder.vkToKeysym(KeyEvent.VK_ENTER));
        assertEquals(0xff1b, X11InputForwarder.vkToKeysym(KeyEvent.VK_ESCAPE));
        assertEquals(0xffff, X11InputForwarder.vkToKeysym(KeyEvent.VK_DELETE));
        assertEquals(0xff63, X11InputForwarder.vkToKeysym(KeyEvent.VK_INSERT));
    }

    @Test
    void navigationKeysWinOverTheirAsciiCodeRange() {
        // These VK codes fall inside 0x20..0x7e yet must resolve to XK_* first.
        assertEquals(0xff50, X11InputForwarder.vkToKeysym(KeyEvent.VK_HOME));
        assertEquals(0xff51, X11InputForwarder.vkToKeysym(KeyEvent.VK_LEFT));
        assertEquals(0xff52, X11InputForwarder.vkToKeysym(KeyEvent.VK_UP));
        assertEquals(0xff53, X11InputForwarder.vkToKeysym(KeyEvent.VK_RIGHT)); // VK 0x27 != apostrophe
        assertEquals(0xff54, X11InputForwarder.vkToKeysym(KeyEvent.VK_DOWN));
        assertEquals(0xff55, X11InputForwarder.vkToKeysym(KeyEvent.VK_PAGE_UP));
        assertEquals(0xff56, X11InputForwarder.vkToKeysym(KeyEvent.VK_PAGE_DOWN));
        assertEquals(0xff57, X11InputForwarder.vkToKeysym(KeyEvent.VK_END));
    }

    @Test
    void punctuationWhoseVkDiffersFromAsciiIsSpecialCased() {
        assertEquals(0x0060, X11InputForwarder.vkToKeysym(KeyEvent.VK_BACK_QUOTE));
        assertEquals(0x0027, X11InputForwarder.vkToKeysym(KeyEvent.VK_QUOTE));
    }

    @Test
    void modifiersAndLocksMapToTheirKeysyms() {
        assertEquals(0xffe1, X11InputForwarder.vkToKeysym(KeyEvent.VK_SHIFT));
        assertEquals(0xffe3, X11InputForwarder.vkToKeysym(KeyEvent.VK_CONTROL));
        assertEquals(0xffe9, X11InputForwarder.vkToKeysym(KeyEvent.VK_ALT));
        assertEquals(0xffeb, X11InputForwarder.vkToKeysym(KeyEvent.VK_META));
        assertEquals(0xfe03, X11InputForwarder.vkToKeysym(KeyEvent.VK_ALT_GRAPH));
        assertEquals(0xffe5, X11InputForwarder.vkToKeysym(KeyEvent.VK_CAPS_LOCK));
        assertEquals(0xff7f, X11InputForwarder.vkToKeysym(KeyEvent.VK_NUM_LOCK));
        assertEquals(0xff14, X11InputForwarder.vkToKeysym(KeyEvent.VK_SCROLL_LOCK));
    }

    @Test
    void functionKeysMapToXkF1Range() {
        assertEquals(0xffbe, X11InputForwarder.vkToKeysym(KeyEvent.VK_F1));
        assertEquals(0xffc9, X11InputForwarder.vkToKeysym(KeyEvent.VK_F12));
    }

    @Test
    void keypadDigitsAndOperatorsMapToXkKpRange() {
        assertEquals(0xffb0, X11InputForwarder.vkToKeysym(KeyEvent.VK_NUMPAD0));
        assertEquals(0xffb9, X11InputForwarder.vkToKeysym(KeyEvent.VK_NUMPAD9));
        assertEquals(0xffaa, X11InputForwarder.vkToKeysym(KeyEvent.VK_MULTIPLY));
        assertEquals(0xffab, X11InputForwarder.vkToKeysym(KeyEvent.VK_ADD));
        assertEquals(0xffad, X11InputForwarder.vkToKeysym(KeyEvent.VK_SUBTRACT));
        assertEquals(0xffae, X11InputForwarder.vkToKeysym(KeyEvent.VK_DECIMAL));
        assertEquals(0xffaf, X11InputForwarder.vkToKeysym(KeyEvent.VK_DIVIDE));
    }

    @Test
    void unmappedKeysReturnZero() {
        assertEquals(0, X11InputForwarder.vkToKeysym(KeyEvent.VK_UNDEFINED));
        // A code outside every range and not a special: no keysym.
        assertEquals(0, X11InputForwarder.vkToKeysym(0x0100));
    }

    // ---- specialVkToKeysym directly ----

    @Test
    void specialTableReturnsZeroForNonSpecials() {
        // Plain letters/digits are not in the special table (handled by vkToKeysym).
        assertEquals(0, X11InputForwarder.specialVkToKeysym(KeyEvent.VK_A));
        assertEquals(0, X11InputForwarder.specialVkToKeysym(KeyEvent.VK_0));
        assertEquals(0, X11InputForwarder.specialVkToKeysym(KeyEvent.VK_UNDEFINED));
    }

    // ---- mapButton ----

    @Test
    void mouseButtonsMapToXButtonNumbers() {
        // X11 convention: 1 = left, 2 = middle, 3 = right (gnu.x11.Input.BUTTON*).
        assertEquals(1, X11InputForwarder.mapButton(MouseEvent3D.ButtonId.BUTTON1));
        assertEquals(2, X11InputForwarder.mapButton(MouseEvent3D.ButtonId.BUTTON2));
        assertEquals(3, X11InputForwarder.mapButton(MouseEvent3D.ButtonId.BUTTON3));
    }

    @Test
    void noButtonAndNullMapToMinusOne() {
        assertEquals(-1, X11InputForwarder.mapButton(MouseEvent3D.ButtonId.NOBUTTON));
        assertEquals(-1, X11InputForwarder.mapButton(null));
    }
}
