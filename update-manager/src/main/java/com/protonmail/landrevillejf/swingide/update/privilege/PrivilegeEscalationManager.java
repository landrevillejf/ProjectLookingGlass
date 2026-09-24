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

import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

@Slf4j
public class PrivilegeEscalationManager {
    
    private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
    private static final boolean IS_WINDOWS = OS_NAME.contains("win");
    private static final boolean IS_MACOS = OS_NAME.contains("mac");
    private static final boolean IS_LINUX = OS_NAME.contains("linux");
    
    /**
     * Check if we need admin privileges.
     */
    public static boolean needsAdminPrivileges() {
        return IS_WINDOWS || IS_MACOS || IS_LINUX;
    }
    
    /**
     * Check if already running with admin privileges.
     */
    public static boolean isRunningAsAdmin() throws PrivilegeException {
        if (IS_WINDOWS) {
            return isAdminWindowsImpl();
        } else if (IS_MACOS || IS_LINUX) {
            return isAdminUnixImpl();
        }
        return false;
    }
    
    /**
     * Prompt for admin privileges (Windows UAC).
     */
    public static boolean promptForWindowsUAC(JFrame parentFrame) throws PrivilegeException {
        if (!IS_WINDOWS) {
            throw new PrivilegeException("Windows UAC prompting only works on Windows");
        }
        
        try {
            log.info("Requesting Windows UAC elevation");
            
            // Create UAC prompt script
            String uacScript = createWindowsUACScript();
            
            // Execute the script which will request elevation
            Process process = new ProcessBuilder("cmd.exe", "/c", uacScript)
                .start();
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                log.info("UAC elevation granted");
                return true;
            } else {
                log.warn("UAC elevation denied or failed");
                return false;
            }
            
        } catch (IOException | InterruptedException e) {
            throw new PrivilegeException("Failed to request Windows UAC elevation", e);
        }
    }
    
    /**
     * Prompt for sudo password (macOS/Linux).
     */
    public static boolean promptForSudoPassword(JFrame parentFrame) throws PrivilegeException {
        if (IS_WINDOWS) {
            throw new PrivilegeException("Sudo prompting not available on Windows");
        }
        
        try {
            log.info("Requesting sudo privileges");
            
            // Use osascript on macOS or zenity/kdialog on Linux for password prompt
            String[] command;
            
            if (IS_MACOS) {
                command = new String[]{
                    "osascript",
                    "-e",
                    "display dialog \"Administrator password required for update installation:\" " +
                    "with icon caution with hidden answer default answer \"\""
                };
            } else {
                // Try zenity first, fallback to kdialog
                command = new String[]{
                    "bash",
                    "-c",
                    "which zenity >/dev/null 2>&1 && zenity --password --title=\"Sudo Password\" " +
                    "|| kdialog --password \"Enter sudo password:\""
                };
            }
            
            Process process = new ProcessBuilder(command)
                .start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            
            String password = reader.readLine();
            int exitCode = process.waitFor();
            
            if (exitCode == 0 && password != null && !password.isEmpty()) {
                log.info("Sudo password provided");
                return testSudoPassword(password);
            } else {
                log.warn("Sudo password prompt cancelled or failed");
                return false;
            }
            
        } catch (IOException | InterruptedException e) {
            throw new PrivilegeException("Failed to request sudo password", e);
        }
    }
    
    /**
     * Request admin privileges with platform-specific method.
     */
    public static boolean requestAdminPrivileges(JFrame parentFrame) throws PrivilegeException {
        if (isRunningAsAdmin()) {
            log.info("Already running with admin privileges");
            return true;
        }
        
        if (IS_WINDOWS) {
            return promptForWindowsUAC(parentFrame);
        } else if (IS_MACOS || IS_LINUX) {
            return promptForSudoPassword(parentFrame);
        }
        
        return false;
    }
    
    private static boolean isAdminWindowsImpl() {
        try {
            // Try to create a test file in System32 to check admin status
            java.io.File systemRoot = new java.io.File("C:\\Windows\\System32\\drivers\\etc\\hosts");
            return systemRoot.canWrite();
        } catch (Exception e) {
            return false;
        }
    }
    
    private static boolean isAdminUnixImpl() {
        try {
            // Check if UID is 0 (root)
            ProcessBuilder pb = new ProcessBuilder("id", "-u");
            Process process = pb.start();
            
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream())
            );
            
            String uid = reader.readLine();
            int exitCode = process.waitFor();
            
            return exitCode == 0 && "0".equals(uid);
        } catch (Exception e) {
            return false;
        }
    }
    
    private static boolean testSudoPassword(String password) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sudo", "-S", "echo", "test");
            Process process = pb.start();
            
            process.getOutputStream().write((password + "\n").getBytes());
            process.getOutputStream().flush();
            process.getOutputStream().close();
            
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            log.debug("Sudo password test failed", e);
            return false;
        }
    }
    
    private static String createWindowsUACScript() {
        return "echo Requesting admin privileges... && " +
            "echo This update requires administrator privileges && " +
            "timeout /t 2 /nobreak";
    }
    
    public static class PrivilegeException extends Exception {
        public PrivilegeException(String message) {
            super(message);
        }
        
        public PrivilegeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
