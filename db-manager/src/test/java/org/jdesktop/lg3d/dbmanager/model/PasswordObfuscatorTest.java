/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.dbmanager.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the reversible (obfuscation-only) password transform: round-trip
 * fidelity, the marker prefix, null pass-through, legacy plain values and a
 * corrupt stored value degrading to empty rather than throwing.
 */
class PasswordObfuscatorTest {

    @Test
    @DisplayName("obfuscate/deobfuscate round-trips and marks the value")
    void roundTrips() {
        String plain = "s3cr3t-pa$$w0rd";
        String stored = PasswordObfuscator.obfuscate(plain);
        assertThat(stored).isNotNull().startsWith("obf1:").isNotEqualTo(plain);
        assertThat(PasswordObfuscator.isObfuscated(stored)).isTrue();
        assertThat(PasswordObfuscator.deobfuscate(stored)).isEqualTo(plain);
    }

    @Test
    @DisplayName("round-trips unicode and empty passwords")
    void roundTripsEdgeValues() {
        assertThat(PasswordObfuscator.deobfuscate(
                PasswordObfuscator.obfuscate(""))).isEmpty();
        String unicode = "pässwörd-✓";
        assertThat(PasswordObfuscator.deobfuscate(
                PasswordObfuscator.obfuscate(unicode))).isEqualTo(unicode);
    }

    @Test
    @DisplayName("null passes through both directions")
    void nullPassesThrough() {
        assertThat(PasswordObfuscator.obfuscate(null)).isNull();
        assertThat(PasswordObfuscator.deobfuscate(null)).isNull();
        assertThat(PasswordObfuscator.isObfuscated(null)).isFalse();
    }

    @Test
    @DisplayName("a legacy plain (unmarked) value is returned as-is")
    void legacyPlainValueIsReturnedUnchanged() {
        assertThat(PasswordObfuscator.isObfuscated("hunter2")).isFalse();
        assertThat(PasswordObfuscator.deobfuscate("hunter2")).isEqualTo("hunter2");
    }

    @Test
    @DisplayName("a corrupt obfuscated value degrades to empty, not an exception")
    void corruptValueDegradesToEmpty() {
        // "obf1:" with a non-Base64 payload must not throw on load.
        assertThat(PasswordObfuscator.deobfuscate("obf1:!!!not-base64!!!")).isEmpty();
    }
}
