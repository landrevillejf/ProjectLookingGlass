package com.protonmail.landrevillejf.swingide.update.privilege;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PrivilegeEscalationManagerTest {
    
    @Test
    void testNeedsAdminPrivileges() {
        boolean needsPrivilege = PrivilegeEscalationManager.needsAdminPrivileges();
        assertThat(needsPrivilege).isTrue();
    }
    
    @Test
    void testIsRunningAsAdmin() throws PrivilegeEscalationManager.PrivilegeException {
        boolean isAdmin = PrivilegeEscalationManager.isRunningAsAdmin();
        assertThat(isAdmin).isNotNull();
    }
    
    @Test
    void testPrivilegeExceptionMessage() {
        PrivilegeEscalationManager.PrivilegeException ex = 
            new PrivilegeEscalationManager.PrivilegeException("Test message");
        
        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).isEqualTo("Test message");
    }
    
    @Test
    void testPrivilegeExceptionWithCause() {
        Exception cause = new Exception("Cause");
        PrivilegeEscalationManager.PrivilegeException ex = 
            new PrivilegeEscalationManager.PrivilegeException("Test message", cause);
        
        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).isEqualTo("Test message");
        assertThat(ex.getCause()).isEqualTo(cause);
    }
    
    @Test
    void testMultiplePrivilegeChecks() throws PrivilegeEscalationManager.PrivilegeException {
        boolean needsPrivilege1 = PrivilegeEscalationManager.needsAdminPrivileges();
        boolean needsPrivilege2 = PrivilegeEscalationManager.needsAdminPrivileges();
        
        assertThat(needsPrivilege1).isEqualTo(needsPrivilege2);
    }
    
    @Test
    void testPrivilegeExceptionInheritance() {
        PrivilegeEscalationManager.PrivilegeException ex = 
            new PrivilegeEscalationManager.PrivilegeException("Test");
        
        assertThat(ex).isInstanceOf(Exception.class);
    }
}

