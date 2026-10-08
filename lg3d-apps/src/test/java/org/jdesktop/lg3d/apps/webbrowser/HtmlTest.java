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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for {@link Html} escaping. */
class HtmlTest {

    @Test
    @DisplayName("the five significant characters are escaped")
    void escapesSpecials() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", Html.escape("&<>\"'"));
    }

    @Test
    @DisplayName("null and empty become the empty string")
    void nullSafe() {
        assertEquals("", Html.escape(null));
        assertEquals("", Html.escape(""));
    }

    @Test
    @DisplayName("ordinary text is left untouched")
    void plainPassesThrough() {
        assertEquals("Hello, World 123", Html.escape("Hello, World 123"));
    }

    @Test
    @DisplayName("a script tag is neutralised")
    void neutralisesScript() {
        String out = Html.escape("<script>alert('x')</script>");
        assertFalse(out.contains("<script>"));
        assertTrue(out.contains("&lt;script&gt;"));
    }
}
