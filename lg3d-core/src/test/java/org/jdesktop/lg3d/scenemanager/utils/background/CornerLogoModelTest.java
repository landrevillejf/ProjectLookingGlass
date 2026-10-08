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
package org.jdesktop.lg3d.scenemanager.utils.background;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.jdesktop.lg3d.scenemanager.utils.background.CornerLogo.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure {@link CornerLogo.Model#fromConfig(String)} parser: the seam
 * the image backgrounds use to turn the persisted {@code DesktopConfig} id into
 * a model. It is a static enum method, so it never constructs a scene-graph
 * object and is safe to run headlessly (the enclosing {@code CornerLogo}, a
 * {@code Component3D}, is not initialized by touching its nested enum).
 */
class CornerLogoModelTest {

    @Test
    @DisplayName("fromConfig maps the persisted ids to models")
    void mapsIds() {
        assertEquals(Model.JAVA, Model.fromConfig("java"));
        assertEquals(Model.MASCOT, Model.fromConfig("mascot"));
        assertEquals(Model.MASCOT, Model.fromConfig("  MASCOT  "));
    }

    @Test
    @DisplayName("fromConfig falls back to JAVA for null, blank and unknown ids")
    void fallsBackToJava() {
        assertEquals(Model.JAVA, Model.fromConfig(null));
        assertEquals(Model.JAVA, Model.fromConfig(""));
        assertEquals(Model.JAVA, Model.fromConfig("   "));
        assertEquals(Model.JAVA, Model.fromConfig("garbage"));
    }
}
