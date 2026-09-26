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
package org.jdesktop.lg3d.ftpclient.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link PasswordObfuscator}: round-trip fidelity, the obfuscation
 * marker, pass-through of null and legacy-plain values, and the forgiving
 * recovery of a corrupt stored value.
 */
class PasswordObfuscatorTest {

    @Test
    @DisplayName("obfuscate/deobfuscate round-trips and hides the plaintext")
    void roundTrip() {
        String plain = "s3cr3t-pa$$word";
        String obf = PasswordObfuscator.obfuscate(plain);
        assertThat(obf).isNotEqualTo(plain).startsWith("obf1:").doesNotContain("s3cr3t");
        assertThat(PasswordObfuscator.deobfuscate(obf)).isEqualTo(plain);
        assertThat(PasswordObfuscator.isObfuscated(obf)).isTrue();
    }

    @Test
    @DisplayName("empty and unicode passwords round-trip")
    void edgeValues() {
        assertThat(PasswordObfuscator.deobfuscate(PasswordObfuscator.obfuscate(""))).isEmpty();
        String uni = "p\u00e4ss\u20ac\u00f6rd";
        assertThat(PasswordObfuscator.deobfuscate(PasswordObfuscator.obfuscate(uni))).isEqualTo(uni);
    }

    @Test
    @DisplayName("null passes through both ways")
    void nullPassesThrough() {
        assertThat(PasswordObfuscator.obfuscate(null)).isNull();
        assertThat(PasswordObfuscator.deobfuscate(null)).isNull();
        assertThat(PasswordObfuscator.isObfuscated(null)).isFalse();
    }

    @Test
    @DisplayName("a legacy plain (unmarked) value is returned unchanged")
    void legacyPlain() {
        assertThat(PasswordObfuscator.isObfuscated("hunter2")).isFalse();
        assertThat(PasswordObfuscator.deobfuscate("hunter2")).isEqualTo("hunter2");
    }

    @Test
    @DisplayName("a corrupt marked value degrades to empty rather than throwing")
    void corruptDegradesToEmpty() {
        assertThat(PasswordObfuscator.deobfuscate("obf1:!!!not-base64!!!")).isEmpty();
    }
}
