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
package com.protonmail.landrevillejf.swingide.update.privilege;

import com.protonmail.landrevillejf.swingide.update.UpdateException;
import com.protonmail.landrevillejf.swingide.update.privilege.PrivilegeEscalationManager.PrivilegeException;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Guards the last step of an installation: replacing the running JAR.
 * <p>
 * When the IDE is installed in a system location the current user cannot write
 * to, a plain {@code Files.copy} fails with an opaque I/O error. This guard
 * detects the situation up front, optionally asks for elevated privileges and,
 * when they are declined or unavailable, reports a clear, actionable message
 * instead of letting the installation half-fail.
 * </p>
 * <p>
 * The elevation prompt is injected through {@link EscalationPrompt} so the guard
 * can be unit tested without spawning {@code osascript}, {@code zenity} or a
 * Windows UAC dialog.
 * </p>
 */
@Slf4j
public class InstallLocationGuard {

    /**
     * Requests the administrative privileges needed to write to a protected
     * location.
     */
    @FunctionalInterface
    public interface EscalationPrompt {
        /**
         * @return {@code true} when the privileges were granted
         */
        boolean request();
    }

    private final boolean escalationEnabled;
    private final EscalationPrompt prompt;

    /**
     * Creates a guard using the platform default elevation prompt.
     *
     * @param escalationEnabled whether the user may be prompted for privileges
     */
    public InstallLocationGuard(boolean escalationEnabled) {
        this(escalationEnabled, InstallLocationGuard::defaultPrompt);
    }

    /**
     * Full constructor, used by tests to inject a deterministic prompt.
     *
     * @param escalationEnabled whether the user may be prompted for privileges
     * @param prompt            callback requesting the elevation
     */
    public InstallLocationGuard(boolean escalationEnabled, EscalationPrompt prompt) {
        this.escalationEnabled = escalationEnabled;
        this.prompt = prompt;
    }

    /**
     * Whether the update can replace {@code target} without extra privileges.
     * <p>
     * A JAR that does not exist yet is judged on its parent directory, which is
     * what the installation script actually writes into.
     * </p>
     *
     * @param target the JAR the installer replaces
     * @return {@code true} when the target (or its directory) is writable
     */
    public boolean isWritable(Path target) {
        if (target == null) {
            return false;
        }

        Path probe = Files.exists(target) ? target : target.getParent();
        if (probe == null) {
            return false;
        }

        return Files.isWritable(probe);
    }

    /**
     * Makes sure the installation target can be written to, requesting elevated
     * privileges when needed and enabled.
     *
     * @param target the JAR the installer replaces
     * @throws UpdateException when the target stays read-only and the update
     *                         cannot proceed
     */
    public void ensureWritable(Path target) throws UpdateException {
        if (isWritable(target)) {
            return;
        }

        if (target == null) {
            throw new UpdateException("Cannot determine the installation target");
        }

        log.warn("The installation target is not writable: {}", target);

        if (escalationEnabled) {
            boolean granted;
            try {
                granted = prompt.request();
            } catch (RuntimeException e) {
                log.warn("Privilege escalation failed: {}", e.getMessage());
                granted = false;
            }

            if (granted) {
                log.info("Administrative privileges granted, continuing the installation");
                return;
            }

            log.warn("Administrative privileges were declined or unavailable");
        }

        throw new UpdateException(
            "Project Looking Glass cannot write to its installation location: " + target
                + ". Relaunch the IDE as an administrator (or move it to a writable"
                + " folder such as your home directory) and try again."
        );
    }

    private static boolean defaultPrompt() {
        try {
            if (PrivilegeEscalationManager.isRunningAsAdmin()) {
                return true;
            }
            return PrivilegeEscalationManager.requestAdminPrivileges(null);
        } catch (PrivilegeException e) {
            log.warn("Could not request administrative privileges: {}", e.getMessage());
            return false;
        }
    }
}
