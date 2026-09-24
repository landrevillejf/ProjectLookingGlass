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
package com.protonmail.landrevillejf.swingide.update;

import lombok.extern.slf4j.Slf4j;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
public class JarLocator {
    
    private JarLocator() {
        // Utility class
    }
    
    public static Path getCurrentJarPath() {
        try {
            String jarPath = JarLocator.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .getPath();
            
            // Decode URL-encoded path
            jarPath = URLDecoder.decode(jarPath, StandardCharsets.UTF_8);
            
            // Remove leading slash on Windows
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                if (jarPath.startsWith("/")) {
                    jarPath = jarPath.substring(1);
                }
                // Replace forward slashes with backslashes
                jarPath = jarPath.replace("/", "\\");
            }
            
            return Paths.get(jarPath);
            
        } catch (Exception e) {
            log.error("Failed to locate current JAR path", e);
            throw new IllegalStateException("Cannot determine current JAR location", e);
        }
    }
    
    public static boolean isRunningFromJar() {
        try {
            String protocol = JarLocator.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .getProtocol();
            
            return "jar".equals(protocol);
            
        } catch (Exception e) {
            log.warn("Failed to determine if running from JAR", e);
            return false;
        }
    }
    
    public static Path getApplicationDirectory() {
        Path jarPath = getCurrentJarPath();
        if (isRunningFromJar()) {
            return jarPath.getParent();
        }
        // Running from IDE or class files
        return Paths.get(System.getProperty("user.dir"));
    }
}
