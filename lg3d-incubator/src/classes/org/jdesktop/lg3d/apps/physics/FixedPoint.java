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
     * A class used to model fixed points in a physics simulation.
     * Can be used to attach moving objects to fixed coordinates with damped springs.
     */
    
public class FixedPoint extends PhysicsObject {
    
    /**
     * Constructs a new fixed point.
     * @param point the coordinates for the fixed point.
     */
    public FixedPoint(Vector3f point) {
        this.velocity=new Vector3f();
        this.force=new Vector3f();
        this.position=new Vector3f(point);
        this.invMass=0.0f;
        
    } 
    /**
     * Dummy function to prevent the fixed point to be moved around by the simulation.
     */
    
    public void update() {
        //a fixed point is fixed and not updated, ever.
    }

   
}
