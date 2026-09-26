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
package org.jdesktop.lg3d.utils.shape;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the Java-3D-free logic of {@link ShaderEffects}: the {@code lg.shaders}
 * toggle, the soft-shadow uniform binding order, and - importantly - that
 * building a program degrades to {@code null} rather than throwing when the GLSL
 * resources are missing or no GL context exists. The unit-test JVM is headless,
 * so the jogamp scene-graph objects cannot be constructed there; these tests
 * assert the graceful-fallback contract the fixed-function widgets rely on. The
 * actual GPU render is verified by an offscreen probe (see the phase PR).
 */
class ShaderEffectsTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty(ShaderEffects.SHADERS_PROPERTY);
    }

    @Test
    @DisplayName("shaders are off unless lg.shaders parses boolean-true")
    void disabledByDefault() {
        System.clearProperty(ShaderEffects.SHADERS_PROPERTY);
        assertFalse(ShaderEffects.isEnabled(), "unset must default to off");

        System.setProperty(ShaderEffects.SHADERS_PROPERTY, "false");
        assertFalse(ShaderEffects.isEnabled());

        System.setProperty(ShaderEffects.SHADERS_PROPERTY, "true");
        assertTrue(ShaderEffects.isEnabled());

        // Boolean.parseBoolean is case-insensitive on "true" and off for all else.
        System.setProperty(ShaderEffects.SHADERS_PROPERTY, "TRUE");
        assertTrue(ShaderEffects.isEnabled());

        System.setProperty(ShaderEffects.SHADERS_PROPERTY, "yes");
        assertFalse(ShaderEffects.isEnabled());
    }

    @Test
    @DisplayName("the soft-shadow program declares its three uniforms in binding order")
    void softShadowUniformNames() {
        assertArrayEquals(
            new String[] { "uHalfWin", "uSoftNESW", "uAlpha" },
            ShaderEffects.SOFT_SHADOW_UNIFORMS);
    }

    @Test
    @DisplayName("the frosted-glass program declares its five uniforms in binding order")
    void frostedGlassUniformNames() {
        assertArrayEquals(
            new String[] { "uHalfWin", "uRadius", "uEdge", "uAa", "uTint" },
            ShaderEffects.FROSTED_GLASS_UNIFORMS);
    }

    @Test
    @DisplayName("a program whose GLSL resources are missing yields null, not a throw")
    void missingResourcesYieldNull() {
        assertNull(ShaderEffects.program(
            "resources/no-such-shader.vert",
            "resources/no-such-shader.frag",
            new String[0]));
    }

    @Test
    @DisplayName("building the soft-shadow program never throws, with or without a GL context")
    void softShadowProgramIsSafeWithoutGl() {
        // Headless (no display/GL) the jogamp objects fail to initialise; the
        // loader must swallow that and return null so callers fall back to the
        // fixed-function widget. With a GL context it returns a real program.
        // Either way it must never propagate the failure to the scene-graph build.
        assertDoesNotThrow(ShaderEffects::softShadowProgram);
    }

    @Test
    @DisplayName("building the frosted-glass program never throws, with or without a GL context")
    void frostedGlassProgramIsSafeWithoutGl() {
        assertDoesNotThrow(ShaderEffects::frostedGlassProgram);
    }
}
