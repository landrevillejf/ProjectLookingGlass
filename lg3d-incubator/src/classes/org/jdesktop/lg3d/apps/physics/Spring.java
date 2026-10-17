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
/**
 * An effector used to model a damped spring between the center of two physics objects.
 */
public class Spring extends Effector{
    private float springConstant;
    private float dampingConstant;
    private float length;
    PhysicsObject left;
    PhysicsObject right;
    
    /**
     * Creates a new Spring between the two given objects.
     * @param left the first object to be attached to the spring.
     * @param right the second object to be attached to the spring.
     * @param length the length of the relaxed spring.
     * @param spring the spring constant.
     * @param damper the damper constant.
     */
    
    public Spring(PhysicsObject left, PhysicsObject right, float length, float spring, float damper) {
        this.left=left;
        this.right=right;
        this.length=length;
        this.springConstant=spring;
        this.dampingConstant=damper;
    }
    /**
     * Applies the spring force to the two objects.
     */
    public void apply() {
        Vector3f distance=new Vector3f();
        Vector3f velocity=new Vector3f();
        
        distance.sub(left.getPosition(),right.getPosition());
        velocity.sub(left.getVelocity(),right.getVelocity());
        float stretch=distance.length();
        float forceScale;
        forceScale=(springConstant*(stretch-length)+dampingConstant*(velocity.dot(distance)/stretch))/stretch;
        distance.scale(forceScale);
        right.addForce(distance);
        distance.scale(-1.0f);
        left.addForce(distance);
    }
    
    
}
