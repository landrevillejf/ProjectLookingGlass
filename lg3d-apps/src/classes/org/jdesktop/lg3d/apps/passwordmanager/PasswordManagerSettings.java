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
package org.jdesktop.lg3d.apps.passwordmanager;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The Password Manager's persisted preferences: the auto-lock timeout, the
 * password-generator policy (length and which character classes to include),
 * whether secrets are masked by default, and the last category filter used. A
 * plain Jackson bean, so {@link VaultStore} round-trips it as JSON; every setter
 * clamps or normalises its input so a hand-edited file can never produce an
 * invalid generator policy or a negative timeout.
 *
 * <p>No secret is stored here - the master password and the entries live only in
 * the sealed {@link VaultEnvelope}.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PasswordManagerSettings {

    /** Default auto-lock timeout in minutes. */
    public static final int DEFAULT_AUTOLOCK_MINUTES = 5;
    /** Default generated-password length. */
    public static final int DEFAULT_LENGTH = 20;
    /** Shortest allowed generated password. */
    public static final int MIN_LENGTH = 4;
    /** Longest allowed generated password. */
    public static final int MAX_LENGTH = 128;

    private int autoLockMinutes = DEFAULT_AUTOLOCK_MINUTES;
    private int generatorLength = DEFAULT_LENGTH;
    private boolean genUpper = true;
    private boolean genLower = true;
    private boolean genDigits = true;
    private boolean genSymbols = true;
    private boolean maskByDefault = true;
    private String lastCategory = "";

    /** Minutes of inactivity before the vault re-locks; {@code 0} means never. */
    public int getAutoLockMinutes() {
        return autoLockMinutes;
    }

    public void setAutoLockMinutes(int autoLockMinutes) {
        this.autoLockMinutes = Math.max(0, autoLockMinutes);
    }

    /** The generated-password length, clamped to {@code [MIN_LENGTH, MAX_LENGTH]}. */
    public int getGeneratorLength() {
        return generatorLength;
    }

    public void setGeneratorLength(int generatorLength) {
        this.generatorLength = Math.min(MAX_LENGTH, Math.max(MIN_LENGTH, generatorLength));
    }

    public boolean isGenUpper() {
        return genUpper;
    }

    public void setGenUpper(boolean genUpper) {
        this.genUpper = genUpper;
    }

    public boolean isGenLower() {
        return genLower;
    }

    public void setGenLower(boolean genLower) {
        this.genLower = genLower;
    }

    public boolean isGenDigits() {
        return genDigits;
    }

    public void setGenDigits(boolean genDigits) {
        this.genDigits = genDigits;
    }

    public boolean isGenSymbols() {
        return genSymbols;
    }

    public void setGenSymbols(boolean genSymbols) {
        this.genSymbols = genSymbols;
    }

    /** True when newly-shown passwords start masked. */
    public boolean isMaskByDefault() {
        return maskByDefault;
    }

    public void setMaskByDefault(boolean maskByDefault) {
        this.maskByDefault = maskByDefault;
    }

    /** The last category filter selected; blank means "All". */
    public String getLastCategory() {
        return lastCategory;
    }

    public void setLastCategory(String lastCategory) {
        this.lastCategory = (lastCategory == null) ? "" : lastCategory.trim();
    }

    /**
     * True when at least one generator character class is selected (a policy with
     * none would generate an empty password, so the panel refuses it).
     *
     * @return true when the generator can produce a password
     */
    public boolean isGeneratorUsable() {
        return genUpper || genLower || genDigits || genSymbols;
    }
}
