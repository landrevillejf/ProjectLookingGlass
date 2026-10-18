/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 */
package org.jdesktop.terminal.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects and runs scripts in various languages (bash, python, groovy, mandela).
 */
public final class ScriptRunner {
    
    private ScriptRunner() {}
    
    public enum ScriptType {
        BASH(".sh", "#!/bin/bash"),
        PYTHON(".py", "#!/usr/bin/env python"),
        GROOVY(".groovy", "#!/usr/bin/env groovy"),
        MANDELA(".mnd", "// Mandela script"),
        UNKNOWN("", "");
        
        private final String extension;
        private final String shebang;
        
        ScriptType(String extension, String shebang) {
            this.extension = extension;
            this.shebang = shebang;
        }
        
        public String getExtension() { return extension; }
        public String getShebang() { return shebang; }
    }
    
    public static ScriptType detectScriptType(Path scriptPath) {
        String name = scriptPath.getFileName().toString().toLowerCase();
        for (ScriptType type : ScriptType.values()) {
            if (name.endsWith(type.extension)) {
                return type;
            }
        }
        // Check shebang
        try {
            String firstLine = Files.readAllLines(scriptPath).get(0);
            for (ScriptType type : ScriptType.values()) {
                if (firstLine.startsWith(type.shebang)) {
                    return type;
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return ScriptType.UNKNOWN;
    }
    
    public static String[] buildCommand(ScriptType type, Path scriptPath, String[] args) {
        String interpreter = switch (type) {
            case BASH -> "bash";
            case PYTHON -> "python3";
            case GROOVY -> "groovy";
            case MANDELA -> "mandela";
            default -> null;
        };
        if (interpreter == null) return null;
        String[] cmd = new String[2 + (args != null ? args.length : 0)];
        cmd[0] = interpreter;
        cmd[1] = scriptPath.toString();
        if (args != null) {
            System.arraycopy(args, 0, cmd, 2, args.length);
        }
        return cmd;
    }
}
