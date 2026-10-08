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
package org.jdesktop.lg3d.apps.vpn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link TunnelVerdict}: the pure leak-check classification (a changed egress
 * IP means tunneled, an unchanged one means leak, a missing either side means
 * unreachable), the plain-text IP parse and the human descriptions. All pure, so this
 * runs headless.
 */
class TunnelVerdictTest {

    @Test
    @DisplayName("a changed egress IP is tunneled; an unchanged one leaks")
    void classifyComparesIps() {
        assertEquals(TunnelVerdict.TUNNELED, TunnelVerdict.classify("1.2.3.4", "5.6.7.8"));
        assertEquals(TunnelVerdict.LEAK, TunnelVerdict.classify("1.2.3.4", "1.2.3.4"));
    }

    @Test
    @DisplayName("a missing baseline or current IP is unreachable, never a false pass")
    void classifyIsUnreachableWhenEitherSideMissing() {
        assertEquals(TunnelVerdict.UNREACHABLE, TunnelVerdict.classify(null, "5.6.7.8"));
        assertEquals(TunnelVerdict.UNREACHABLE, TunnelVerdict.classify("1.2.3.4", null));
        assertEquals(TunnelVerdict.UNREACHABLE, TunnelVerdict.classify("", "5.6.7.8"));
        assertEquals(TunnelVerdict.UNREACHABLE, TunnelVerdict.classify("1.2.3.4", "   "));
        assertEquals(TunnelVerdict.UNREACHABLE, TunnelVerdict.classify(null, null));
    }

    @Test
    @DisplayName("both sides are trimmed before comparison")
    void classifyTrims() {
        assertEquals(TunnelVerdict.LEAK, TunnelVerdict.classify("  1.2.3.4  ", "1.2.3.4\n"));
        assertEquals(TunnelVerdict.TUNNELED, TunnelVerdict.classify(" 1.2.3.4", "5.6.7.8 "));
    }

    @Test
    @DisplayName("parsePublicIp trims a plain-text body and tolerates null")
    void parsePublicIp() {
        assertEquals("9.9.9.9", TunnelVerdict.parsePublicIp("  9.9.9.9\n"));
        assertEquals("2001:db8::1", TunnelVerdict.parsePublicIp("2001:db8::1"));
        assertEquals("", TunnelVerdict.parsePublicIp(null));
        assertEquals("", TunnelVerdict.parsePublicIp("   "));
    }

    @Test
    @DisplayName("every verdict has a distinct, non-blank description")
    void descriptions() {
        for (TunnelVerdict v : TunnelVerdict.values()) {
            assertFalse(v.describe().isBlank(), v + " has a description");
        }
        assertNotEquals(TunnelVerdict.TUNNELED.describe(), TunnelVerdict.LEAK.describe());
        assertNotEquals(TunnelVerdict.LEAK.describe(), TunnelVerdict.UNREACHABLE.describe());
        assertTrue(TunnelVerdict.TUNNELED.describe().toLowerCase().contains("verified"));
        assertTrue(TunnelVerdict.LEAK.describe().toLowerCase().contains("leak"));
    }
}
