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
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Headless tests for the {@link ContentDisposition} parser. */
class ContentDispositionTest {

    @Test
    @DisplayName("null / blank / no-filename headers yield null")
    void noName() {
        assertNull(ContentDisposition.fileName(null));
        assertNull(ContentDisposition.fileName("   "));
        assertNull(ContentDisposition.fileName("attachment"));
    }

    @Test
    @DisplayName("a plain unquoted filename is returned")
    void plainUnquoted() {
        assertEquals("report.pdf",
                ContentDisposition.fileName("attachment; filename=report.pdf"));
    }

    @Test
    @DisplayName("a quoted filename keeps inner spaces")
    void plainQuoted() {
        assertEquals("my report.pdf",
                ContentDisposition.fileName("attachment; filename=\"my report.pdf\""));
    }

    @Test
    @DisplayName("the parameter match is case-insensitive and order-independent")
    void caseInsensitive() {
        assertEquals("a.zip",
                ContentDisposition.fileName("attachment; FILENAME=\"a.zip\"; charset=utf-8"));
    }

    @Test
    @DisplayName("the RFC 5987 extended form wins and is percent-decoded as UTF-8")
    void extendedWins() {
        String header = "attachment; filename=\"fallback.txt\"; "
                + "filename*=UTF-8''caf%C3%A9%20menu.pdf";
        assertEquals("café menu.pdf", ContentDisposition.fileName(header));
    }

    @Test
    @DisplayName("a semicolon inside quotes does not split the value")
    void quotedSemicolon() {
        assertEquals("weird;name.txt",
                ContentDisposition.fileName("attachment; filename=\"weird;name.txt\""));
    }

    @Test
    @DisplayName("directory components in a hostile header are stripped to one segment")
    void stripsPathTraversal() {
        assertEquals("passwd",
                ContentDisposition.fileName("attachment; filename=\"../../etc/passwd\""));
        assertEquals("evil.txt",
                ContentDisposition.fileName("attachment; filename=\"C:\\\\tmp\\\\evil.txt\""));
    }

    @Test
    @DisplayName("a name that reduces to nothing usable yields null")
    void reducesToNull() {
        assertNull(ContentDisposition.fileName("attachment; filename=\"..\""));
        assertNull(ContentDisposition.fileName("attachment; filename=\"   \""));
    }
}
