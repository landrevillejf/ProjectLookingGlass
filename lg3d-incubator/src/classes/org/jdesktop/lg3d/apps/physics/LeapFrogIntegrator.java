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
package org.jdesktop.lg3d.apps.physics;
import org.jogamp.vecmath.Vector3f;
import org.jogamp.vecmath.Quat4f;
/**
 * An integrator that uses the Leap Frog method to calculate future states of objects.
 */

public class LeapFrogIntegrator implements Integrator {
    
    /**
     * Update the given object's state using the supplied time step.
     * 
     * @param object the PhysicsObject to update.
     * @param dh the length of the time step to use.
     *
     */
    
    public void updateObject(PhysicsObject object, float dh) {
        Vector3f velocity=object.getVelocity();
        Vector3f velocityChange=object.getForce();
        velocityChange.scale(object.getInvMass()*dh);
        velocity.add(velocityChange);
        object.setVelocity(velocity);
        
        
        Vector3f position=object.getPosition();
        velocity.scale(dh);
        position.add(velocity);
        object.setPosition(position);
        
        
    }
    
    /**
     * Update the given body's state using the supplied time step.
     * 
     * @param body the PhysicsBody to update.
     * @param dh the length of the time step to use.
     *
     */
    
    public void updateBody(PhysicsBody body, float dh) {
        Vector3f velocity=body.getVelocity();
        Vector3f velocityChange=body.getForce();
        velocityChange.scale(body.getInvMass()*dh);
        velocity.add(velocityChange);
        body.setVelocity(velocity);
        
        
        Vector3f position=body.getPosition();
        velocity.scale(dh);
        position.add(velocity);
        body.setPosition(position);
        
        Quat4f attitudeChange=body.getAngularVelocity();
        Quat4f attitude=body.getAttitude();
        attitudeChange.mul(attitude);
        attitudeChange.scale(0.5f*dh);
        attitude.add(attitudeChange);
        attitude.normalize();
    }
    
    /**
     * Update the given object's velocity using the supplied time step.
     * 
     * @param object the PhysicsObject to update.
     * @param dh the length of the time step to use.
     *
     */
    
    public void updateVelocity(PhysicsObject object, float dh) {
        Vector3f velocity=object.getVelocity();
        Vector3f velocityChange=object.getForce();
        velocityChange.scale(object.getInvMass()*dh);
        velocity.add(velocityChange);
        object.setVelocity(velocity);
        
    }

    
    /**
     * Update the given body's position using the supplied time step.
     * 
     * @param body the PhysicsBody to update.
     * @param dh the length of the time step to use.
     *
     */
    
    public void updatePosition(PhysicsBody body, float dh) {
        Vector3f velocity=body.getVelocity();
        Vector3f position=body.getPosition();
        velocity.scale(dh);
        position.add(velocity);
        body.setPosition(position);
        
        Quat4f attitudeChange=body.getAngularVelocity();
        Quat4f attitude=body.getAttitude();
        attitudeChange.mul(attitude);
        attitudeChange.scale(0.5f*dh);
        attitude.add(attitudeChange);
        attitude.normalize();
        body.setAttitude(attitude);
        body.resetForce();
    }
    
     /**
     * Update the given object's position using the supplied time step.
     * 
     * @param object the PhysicsObject to update.
     * @param dh the length of the time step to use.
     *
     */
    
     public void updatePosition(PhysicsObject object, float dh) {
        Vector3f velocity=object.getVelocity();
        Vector3f position=object.getPosition();
        velocity.scale(dh);
        position.add(velocity);
        object.setPosition(position);
        object.resetForce();
     }
}  
