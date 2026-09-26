/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure seams of {@link PrinterStatus}: the {@code lpstat} parsing,
 * the management command builders, the shell quoting and the row label. The
 * CUPS probes themselves are host-dependent and are exercised only through
 * their graceful-degradation contract (empty / false), not asserted here.
 */
class PrinterStatusTest {

    @Test
    @DisplayName("lpstat -a output yields one queue per line with its accepting flag")
    void parsesQueues() {
        List<PrinterStatus.Printer> printers = PrinterStatus.parsePrinters(
                "PDF accepting requests since Mon Jan  1 00:00:00 2024\n"
              + "Old not accepting requests since Mon Jan  1 00:00:00 2024\n"
              + "\n"
              + "   \n");
        assertEquals(2, printers.size());
        assertEquals("PDF", printers.get(0).name());
        assertTrue(printers.get(0).accepting());
        assertEquals("Old", printers.get(1).name());
        assertFalse(printers.get(1).accepting(), "'not accepting' must not read as accepting");
    }

    @Test
    @DisplayName("a name-only line and null/blank input are tolerated")
    void parsesDegenerateInput() {
        assertEquals(1, PrinterStatus.parsePrinters("Solo").size(), "a bare name is a queue");
        assertTrue(PrinterStatus.parsePrinters(null).isEmpty());
        assertTrue(PrinterStatus.parsePrinters("").isEmpty());
    }

    @Test
    @DisplayName("lpstat -d default destination is parsed, absent default yields empty")
    void parsesDefault() {
        assertEquals("PDF", PrinterStatus.parseDefault("system default destination: PDF\n"));
        assertEquals("", PrinterStatus.parseDefault("no system default destination\n"));
        assertEquals("", PrinterStatus.parseDefault(null));
    }

    @Test
    @DisplayName("the management commands target lpoptions and lp")
    void commandBuilders() {
        assertArrayEquals(new String[] {"lpoptions", "-d", "PDF"},
                PrinterStatus.setDefaultCommand("PDF"));
        String[] test = PrinterStatus.testPageCommand("PDF");
        assertEquals("sh", test[0]);
        assertEquals("-c", test[1]);
        assertTrue(test[2].contains("lp -d 'PDF'"), "test page pipes into lp for the queue");
    }

    @Test
    @DisplayName("shell quoting wraps in single quotes and escapes embedded quotes")
    void shellQuoting() {
        assertEquals("'PDF'", PrinterStatus.shellQuote("PDF"));
        assertEquals("'a'\\''b'", PrinterStatus.shellQuote("a'b"));
    }

    @Test
    @DisplayName("the row label shows the accepting state")
    void labelShowsState() {
        assertEquals("PDF  (accepting)",
                PrinterStatus.label(new PrinterStatus.Printer("PDF", true)));
        assertEquals("Old  (rejecting)",
                PrinterStatus.label(new PrinterStatus.Printer("Old", false)));
        assertEquals("", PrinterStatus.label(null));
    }
}
