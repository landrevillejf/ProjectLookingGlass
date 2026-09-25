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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Predicate;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.MenuModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure decision table behind the Alt+F2 run dialog: app-name match,
 * external command (available / missing), unknown input and blank input. The
 * PATH lookup is injected so every branch is deterministic and headless.
 */
class RunResolverTest {

    /** Every external command is present on the PATH. */
    private static final Predicate<String> ALL_AVAILABLE = command -> true;

    /** No external command is present on the PATH. */
    private static final Predicate<String> NONE_AVAILABLE = command -> false;

    private static MenuModel model() {
        ItemSpec calculator = new ItemSpec("Calculator",
                "java org.jdesktop.lg3d.apps.calculator.Calculator",
                "A calculator", "Utilities", null);
        ItemSpec terminal = new ItemSpec("Terminal", "xterm",
                "A terminal", "Utilities", null);
        return new MenuModel(List.of(), List.of(calculator, terminal));
    }

    @Test
    @DisplayName("an exact app name resolves to launching that item")
    void appNameMatch() {
        RunResolver.Decision decision =
                RunResolver.resolve("Calculator", model(), NONE_AVAILABLE);
        assertTrue(decision.isApp());
        assertEquals(RunResolver.Type.APP, decision.type());
        assertEquals("Calculator", decision.item().getName());
        assertEquals("java org.jdesktop.lg3d.apps.calculator.Calculator",
                decision.command());
    }

    @Test
    @DisplayName("app-name matching is case-insensitive and trims whitespace")
    void appNameMatchIsCaseInsensitiveAndTrimmed() {
        RunResolver.Decision decision =
                RunResolver.resolve("  cAlCuLaToR  ", model(), NONE_AVAILABLE);
        assertTrue(decision.isApp());
        assertEquals("Calculator", decision.item().getName());
    }

    @Test
    @DisplayName("an app name wins even when the external PATH is unavailable")
    void appNameMatchBeatsExternalAvailability() {
        // "Terminal" maps to the external "xterm"; the name match short-circuits
        // before any PATH check, so it resolves as an app.
        RunResolver.Decision decision =
                RunResolver.resolve("Terminal", model(), NONE_AVAILABLE);
        assertTrue(decision.isApp());
        assertEquals("Terminal", decision.item().getName());
    }

    @Test
    @DisplayName("an available external command resolves to running it")
    void externalCommand() {
        RunResolver.Decision decision =
                RunResolver.resolve("firefox --private-window", model(), ALL_AVAILABLE);
        assertTrue(decision.isCommand());
        assertEquals(RunResolver.Type.COMMAND, decision.type());
        assertEquals("firefox --private-window", decision.command());
        assertNull(decision.item());
    }

    @Test
    @DisplayName("a command whose executable is missing is not found")
    void externalCommandUnavailable() {
        RunResolver.Decision decision =
                RunResolver.resolve("firefox", model(), NONE_AVAILABLE);
        assertTrue(decision.isNotFound());
        assertNull(decision.command());
        assertNull(decision.item());
    }

    @Test
    @DisplayName("an unknown name that is not an available command is not found")
    void unknownInput() {
        RunResolver.Decision decision =
                RunResolver.resolve("Not An App", model(), NONE_AVAILABLE);
        assertTrue(decision.isNotFound());
    }

    @Test
    @DisplayName("a raw in-JVM java command that is no app name is not found")
    void rawJavaVerbIsNotAnExternalCommand() {
        // classify() reports this as UNAVAILABLE (not EXTERNAL), so it never
        // reaches the external branch even with the PATH wide open.
        RunResolver.Decision decision = RunResolver.resolve(
                "java org.jdesktop.lg3d.apps.paint.PaintApp", model(), ALL_AVAILABLE);
        assertTrue(decision.isNotFound());
    }

    @Test
    @DisplayName("blank and null input are not found")
    void blankAndNullInput() {
        assertTrue(RunResolver.resolve(null, model(), ALL_AVAILABLE).isNotFound());
        assertTrue(RunResolver.resolve("", model(), ALL_AVAILABLE).isNotFound());
        assertTrue(RunResolver.resolve("   ", model(), ALL_AVAILABLE).isNotFound());
    }

    @Test
    @DisplayName("a null model still resolves external commands")
    void nullModelResolvesCommandsOnly() {
        // With no menu nothing matches by name, so an available command runs...
        assertTrue(RunResolver.resolve("xterm", null, ALL_AVAILABLE).isCommand());
        // ...and the same command, unavailable, is simply not found.
        assertTrue(RunResolver.resolve("xterm", null, NONE_AVAILABLE).isNotFound());
    }
}
