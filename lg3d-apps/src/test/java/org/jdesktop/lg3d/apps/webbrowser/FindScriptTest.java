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
package org.jdesktop.lg3d.apps.webbrowser;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the {@link FindScript} JS builder and count parser. */
class FindScriptTest {

    @Test
    @DisplayName("escapeJs escapes quotes, backslashes and control characters")
    void escapeJs() {
        assertEquals("", FindScript.escapeJs(null));
        assertEquals("", FindScript.escapeJs(""));
        assertEquals("a\\'b", FindScript.escapeJs("a'b"));
        assertEquals("a\\\\b", FindScript.escapeJs("a\\b"));
        assertEquals("a\\nb", FindScript.escapeJs("a\nb"));
        assertEquals("\\u003c\\u003e\\u0026", FindScript.escapeJs("<>&"));
    }

    @Test
    @DisplayName("a query cannot break out of its JS string literal (injection)")
    void injectionContained() {
        String evil = "');alert(1);//";
        String script = FindScript.highlightScript(evil);
        // The single quote is escaped, so the literal is never terminated early.
        assertTrue(script.contains("\\'"), "the single quote is escaped");
        assertFalse(script.contains("query:'');"),
                "the injected quote must not close the string literal");
    }

    @Test
    @DisplayName("the highlight script embeds the escaped query and returns a:b")
    void highlightScriptShape() {
        String script = FindScript.highlightScript("hello");
        assertTrue(script.contains("query:'hello'"));
        assertTrue(script.contains("'1:'") || script.contains("return '1:"));
        assertTrue(script.contains("0:0"), "the no-match path reports 0:0");
    }

    @Test
    @DisplayName("the scripts are non-empty and reference the shared state")
    void scriptsPresent() {
        assertTrue(FindScript.nextScript().contains("__lg3dFind"));
        assertTrue(FindScript.prevScript().contains("__lg3dFind"));
        assertTrue(FindScript.clearScript().contains("lg3dFindClear"));
        assertTrue(FindScript.installHelpersScript().contains("__lg3dFindHelpers"));
    }

    @Test
    @DisplayName("parseCounts reads 'active:total' and tolerates junk")
    void parseCounts() {
        assertArrayEquals(new int[] {3, 7}, FindScript.parseCounts("3:7"));
        assertArrayEquals(new int[] {0, 0}, FindScript.parseCounts("0:0"));
        assertArrayEquals(new int[] {0, 0}, FindScript.parseCounts(null));
        assertArrayEquals(new int[] {0, 0}, FindScript.parseCounts("garbage"));
        assertArrayEquals(new int[] {0, 5}, FindScript.parseCounts("5"),
                "a bare number is treated as the total");
        assertArrayEquals(new int[] {0, 0}, FindScript.parseCounts("-1:-2"),
                "negatives clamp to zero");
    }

    @Test
    @DisplayName("parseCounts accepts a numeric script result")
    void parseCountsNumber() {
        assertArrayEquals(new int[] {1, 4}, FindScript.parseCounts(Integer.valueOf(4)));
        assertArrayEquals(new int[] {0, 0}, FindScript.parseCounts(Integer.valueOf(0)));
    }

    @Test
    @DisplayName("marker class names are stable constants")
    void markerClasses() {
        assertEquals("lg3d-find", FindScript.MARK_CLASS);
        assertEquals("lg3d-find-active", FindScript.ACTIVE_CLASS);
    }
}
