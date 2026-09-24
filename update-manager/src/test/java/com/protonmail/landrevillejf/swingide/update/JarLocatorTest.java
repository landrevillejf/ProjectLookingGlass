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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class JarLocatorTest {
    
    @Test
    void testIsRunningFromJar() {
        // This test runs from IDE or class files, so should return false
        boolean running = JarLocator.isRunningFromJar();
        assertThat(running).isFalse();
    }
    
    @Test
    void testGetCurrentJarPath() {
        Path jarPath = JarLocator.getCurrentJarPath();
        
        assertThat(jarPath).isNotNull();
    }
    
    @Test
    void testGetApplicationDirectory() {
        Path appDir = JarLocator.getApplicationDirectory();
        
        assertThat(appDir).isNotNull();
        assertThat(appDir).exists();
    }
    
    @Test
    void testCurrentJarPathIsAbsolute() {
        Path jarPath = JarLocator.getCurrentJarPath();
        
        assertThat(jarPath.isAbsolute()).isTrue();
    }
    
    @Test
    void testApplicationDirectoryIsAbsolute() {
        Path appDir = JarLocator.getApplicationDirectory();
        
        assertThat(appDir.isAbsolute()).isTrue();
    }
    
    @Test
    void testGetCurrentJarPathConsistency() {
        Path path1 = JarLocator.getCurrentJarPath();
        Path path2 = JarLocator.getCurrentJarPath();
        
        assertThat(path1).isEqualTo(path2);
    }
    
    @Test
    void testGetApplicationDirectoryConsistency() {
        Path dir1 = JarLocator.getApplicationDirectory();
        Path dir2 = JarLocator.getApplicationDirectory();
        
        assertThat(dir1).isEqualTo(dir2);
    }
}
