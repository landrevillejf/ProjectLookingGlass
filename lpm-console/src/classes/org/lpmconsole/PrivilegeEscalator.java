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
package org.lpmconsole;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles privilege escalation for LPM commands using pkexec or sudo.
 */
public class PrivilegeEscalator {
    
    private static final boolean USE_PKEXEC = isPkexecAvailable();

    private static boolean isPkexecAvailable() {
        try {
            Process process = new ProcessBuilder("which", "pkexec").start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /**
     * Escalate a command using pkexec or sudo.
     * @param command The command to escalate
     * @param args Arguments to the command
     * @return ProcessBuilder configured for escalation
     */
    public static ProcessBuilder escalate(String command, List<String> args) {
        List<String> fullCommand = new ArrayList<>();
        
        if (USE_PKEXEC) {
            fullCommand.add("pkexec");
        } else {
            fullCommand.add("sudo");
        }
        
        fullCommand.add(command);
        fullCommand.addAll(args);
        
        return new ProcessBuilder(fullCommand);
    }

    /**
     * Check if escalation is available.
     */
    public static boolean isEscalationAvailable() {
        return USE_PKEXEC || isSudoAvailable();
    }

    private static boolean isSudoAvailable() {
        try {
            Process process = new ProcessBuilder("which", "sudo").start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    public static String getEscalationMethod() {
        return USE_PKEXEC ? "pkexec" : "sudo";
    }
}
