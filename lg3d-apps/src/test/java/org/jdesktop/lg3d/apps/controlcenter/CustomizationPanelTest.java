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
package org.jdesktop.lg3d.apps.controlcenter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pure row mapping of the Customization panel's language selector.
 *
 * <p>Only {@link CustomizationPanel#languageCodeIndex} is tested: constructing
 * the panel would read the real {@code DesktopConfig} preferences node, which
 * the headless suite deliberately does not touch.</p>
 */
class CustomizationPanelTest {

    @Test
    @DisplayName("each persisted locale code selects its own row")
    void knownCodesMapToTheirRows() {
        assertEquals(0, CustomizationPanel.languageCodeIndex("system"));
        assertEquals(1, CustomizationPanel.languageCodeIndex("en"));
        assertEquals(2, CustomizationPanel.languageCodeIndex("fr"));
    }

    @Test
    @DisplayName("an unknown or missing code lands on the system-default row")
    void unknownCodeFallsToSystemDefault() {
        // DesktopConfig normalizes what it stores, so reaching the panel with
        // junk means a hand-edited preference; the row mirrors what the
        // desktop itself would resolve.
        assertEquals(0, CustomizationPanel.languageCodeIndex("de"));
        assertEquals(0, CustomizationPanel.languageCodeIndex(""));
        assertEquals(0, CustomizationPanel.languageCodeIndex(null));
    }
}
