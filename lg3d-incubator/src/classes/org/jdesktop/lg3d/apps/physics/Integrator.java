/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
/*
 * Integrator.java
 *
 */

package org.jdesktop.lg3d.apps.physics;

/**
 * An interface for time integrators used with the physics package. 
 * @author Administrator
 */
public interface Integrator {
    /**
     * Update the given object's state using the supplied time step.
     * 
     * @param object the PhysicsObject to update.
     * @param dh the length of the time step to use.
     *
     */
     
    public void updateObject(PhysicsObject object, float dh);
    
    /**
     * Update the given body's state using the supplied time step.
     * 
     * @param body the PhysicsBody to update.
     * @param dh the length of the time step to use.
     *
     */
 
    public void updateBody(PhysicsBody body, float dh);
    
    /**
     * Update the given object's velocity using the supplied time step.
     * 
     * @param object the PhysicsObject to update.
     * @param dh the length of the time step to use.
     *
     */
    public void updateVelocity(PhysicsObject object, float dh);
    
    /**
     * Update the given body's position using the supplied time step.
     * 
     * @param body the PhysicsBody to update.
     * @param dh the length of the time step to use.
     *
     */
    public void updatePosition(PhysicsBody body, float dh);
    
    /**
     * Update the given object's position using the supplied time step.
     * 
     * @param object the PhysicsObject to update.
     * @param dh the length of the time step to use.
     *
     */
    public void updatePosition(PhysicsObject object, float dh);
    
}
