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
