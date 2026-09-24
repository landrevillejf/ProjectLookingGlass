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
package org.jdesktop.lg3d.apps.about;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of the About model. The version and Java 3D resolution are
 * factored into pure helpers ({@code resolveVersion}, {@code resolveJava3D}) so
 * every fallback branch is exercised directly, without mutating the JVM's global
 * system properties or depending on Java 3D being present.
 */
class AboutInfoTest {

    @Test
    @DisplayName("resolveVersion prefers the system property, then the manifest")
    void resolveVersionPrecedence() {
        assertEquals("1.9.0",
                AboutInfo.resolveVersion("1.9.0", "9.9.9"),
                "the lg.version system property wins over the manifest");
        assertEquals("9.9.9",
                AboutInfo.resolveVersion(null, "9.9.9"),
                "falls back to the manifest when the property is absent");
        assertEquals("9.9.9",
                AboutInfo.resolveVersion("   ", "9.9.9"),
                "a blank property is treated as absent");
    }

    @Test
    @DisplayName("resolveVersion trims and degrades to UNKNOWN")
    void resolveVersionFallback() {
        assertEquals("1.2.3", AboutInfo.resolveVersion("  1.2.3  ", null),
                "the resolved value is trimmed");
        assertEquals(AboutInfo.UNKNOWN, AboutInfo.resolveVersion(null, null));
        assertEquals(AboutInfo.UNKNOWN, AboutInfo.resolveVersion("", "  "));
    }

    @Test
    @DisplayName("resolveJava3D appends the version when known")
    void resolveJava3D() {
        assertEquals("Jogamp Java 3D 1.7.2",
                AboutInfo.resolveJava3D("1.7.2"));
        assertEquals("Jogamp Java 3D 1.7.2",
                AboutInfo.resolveJava3D("  1.7.2  "),
                "the version is trimmed");
        assertEquals("Jogamp Java 3D", AboutInfo.resolveJava3D(null),
                "degrades to the bare provider name when Java 3D is absent");
        assertEquals("Jogamp Java 3D", AboutInfo.resolveJava3D("   "));
    }

    @Test
    @DisplayName("getVersion and getJava3D never return null")
    void accessorsAreNonNull() {
        assertNotNull(AboutInfo.getVersion());
        assertNotNull(AboutInfo.getJava3D());
        assertTrue(AboutInfo.getJava3D().startsWith("Jogamp Java 3D"));
    }

    @Test
    @DisplayName("getFields lists the runtime facts in order")
    void fieldsAreOrderedAndPopulated() {
        List<AboutInfo.Field> fields = AboutInfo.getFields();
        assertEquals(5, fields.size());
        assertEquals("Version", fields.get(0).getLabel());
        assertEquals("Edition", fields.get(1).getLabel());
        assertEquals("Java 3D", fields.get(2).getLabel());
        assertEquals("Java", fields.get(3).getLabel());
        assertEquals("Platform", fields.get(4).getLabel());
        for (AboutInfo.Field field : fields) {
            assertNotNull(field.getValue());
            assertFalse(field.getValue().isBlank(),
                    field.getLabel() + " must have a value");
        }
    }

    @Test
    @DisplayName("getFields is unmodifiable")
    void fieldsAreUnmodifiable() {
        List<AboutInfo.Field> fields = AboutInfo.getFields();
        assertThrows(UnsupportedOperationException.class,
                () -> fields.add(new AboutInfo.Field("x", "y")));
    }

    @Test
    @DisplayName("Field exposes its label, value and a readable toString")
    void fieldAccessors() {
        AboutInfo.Field field = new AboutInfo.Field("Java", "21");
        assertEquals("Java", field.getLabel());
        assertEquals("21", field.getValue());
        assertEquals("Java: 21", field.toString());
    }

    @Test
    @DisplayName("the static identity text is present and credits the porter")
    void identityConstants() {
        assertFalse(AboutInfo.PRODUCT_NAME.isBlank());
        assertFalse(AboutInfo.TAGLINE.isBlank());
        assertFalse(AboutInfo.DESCRIPTION.isBlank());
        assertFalse(AboutInfo.LICENSE.isBlank());
        assertTrue(AboutInfo.CREDITS.contains("Jean-Francois Landreville"),
                "the modernization port must be attributed to its author");
        assertTrue(AboutInfo.CREDITS.contains("Sun Microsystems"),
                "the original 2004-2006 work is credited too");
        assertEquals("lg.version", AboutInfo.VERSION_PROPERTY);
    }
}
