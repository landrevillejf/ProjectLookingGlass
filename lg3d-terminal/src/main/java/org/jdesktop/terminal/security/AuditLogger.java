/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 */
package org.jdesktop.terminal.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Logs all commands executed in terminal sessions for audit purposes.
 */
public final class AuditLogger {
    
    private final Path logFile;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    public AuditLogger(Path logFile) {
        this.logFile = logFile;
    }
    
    public void logCommand(int sessionId, String command) {
        String timestamp = LocalDateTime.now().format(formatter);
        String entry = String.format("[%s] Session %d: %s%n", timestamp, sessionId, command);
        try {
            Files.writeString(logFile, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Failed to write audit log: " + e.getMessage());
        }
    }
    
    public void logSessionStart(int sessionId, String shell) {
        String timestamp = LocalDateTime.now().format(formatter);
        String entry = String.format("[%s] Session %d STARTED: %s%n", timestamp, sessionId, shell);
        try {
            Files.writeString(logFile, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Failed to write audit log: " + e.getMessage());
        }
    }
    
    public void logSessionEnd(int sessionId) {
        String timestamp = LocalDateTime.now().format(formatter);
        String entry = String.format("[%s] Session %d ENDED%n", timestamp, sessionId);
        try {
            Files.writeString(logFile, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Failed to write audit log: " + e.getMessage());
        }
    }
}
