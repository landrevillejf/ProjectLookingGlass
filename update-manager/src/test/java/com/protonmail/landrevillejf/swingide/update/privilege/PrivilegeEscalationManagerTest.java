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

