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
package org.jdesktop.lg3d.apps.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.jdesktop.lg3d.apps.host.PanelHostApp.HostSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure half of {@link PanelHostApp}: the argument parsing that turns
 * the child's command line into a {@link HostSpec}, and the window-title
 * derivation. These are the decisions the isolated host must get right before it
 * touches a display, so they are asserted headlessly; only {@code main} and the
 * frame assembly need a live desktop (they are probe-verified, like the other
 * lg3d-apps Swing entry points).
 */
class PanelHostAppTest {

    @Test
    @DisplayName("parse yields null without a usable --panel value")
    void parseRequiresPanel() {
        assertNull(PanelHostApp.parse(null), "null args are rejected");
        assertNull(PanelHostApp.parse(new String[0]), "empty args are rejected");
        assertNull(PanelHostApp.parse(new String[] { "--title", "Calculator" }),
                "a title with no panel is rejected");
        assertNull(PanelHostApp.parse(new String[] { "--panel", "   " }),
                "a blank panel class is rejected");
    }

    @Test
    @DisplayName("parse reads the panel, title and path flags")
    void parseReadsAllFlags() {
        HostSpec spec = PanelHostApp.parse(new String[] {
                "--panel", "org.jdesktop.lg3d.apps.calculator.CalculatorPanel",
                "--title", "Calculator",
                "--path", "/tmp/session"});
        assertNotNull(spec);
        assertEquals("org.jdesktop.lg3d.apps.calculator.CalculatorPanel",
                spec.panelClassName());
        assertEquals("Calculator", spec.title());
        assertEquals(Path.of("/tmp/session"), spec.path());
    }

    @Test
    @DisplayName("parse defaults title and path to null and ignores stray tokens")
    void parseDefaultsAndIgnoresUnknown() {
        HostSpec spec = PanelHostApp.parse(new String[] {
                "--panel", "org.foo.CalculatorPanel", "bogus", "--title"});
        assertNotNull(spec);
        assertEquals("org.foo.CalculatorPanel", spec.panelClassName());
        assertNull(spec.title(), "a dangling --title with no value is ignored");
        assertNull(spec.path(), "no --path means the panel default");
    }

    @Test
    @DisplayName("titleOf prefers an explicit title")
    void titleOfUsesExplicitTitle() {
        HostSpec spec = PanelHostApp.parse(new String[] {
                "--panel", "org.foo.CalculatorPanel", "--title", "My Calculator"});
        assertEquals("My Calculator", PanelHostApp.titleOf(spec));
    }

    @Test
    @DisplayName("titleOf derives a title from the panel class name")
    void titleOfDerivesWhenAbsent() {
        HostSpec spec = PanelHostApp.parse(new String[] {
                "--panel", "org.jdesktop.lg3d.apps.calculator.CalculatorPanel"});
        assertEquals("Calculator", PanelHostApp.titleOf(spec));
    }

    @Test
    @DisplayName("humanize drops the package and Panel suffix and splits camel case")
    void humanizeSplitsCamelCase() {
        assertEquals("Calculator",
                PanelHostApp.humanize("org.foo.CalculatorPanel"));
        assertEquals("Advanced Text Editor",
                PanelHostApp.humanize("org.foo.AdvancedTextEditorPanel"));
        assertEquals("No Suffix", PanelHostApp.humanize("NoSuffix"));
    }

    @Test
    @DisplayName("humanize keeps a class named exactly Panel")
    void humanizeKeepsBarePanelName() {
        // The suffix is only stripped when something would remain, so a class
        // literally called "Panel" is not reduced to an empty title.
        assertEquals("Panel", PanelHostApp.humanize("org.foo.Panel"));
    }
}
