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
import java.util.Vector;
/**
 * A class used to represent general convex polyheda in a physics simulation.
 * NOT IMPLEMENTED YET!
 * @author Tobias Evert
 */
public class ConvexPolyhedra extends PhysicsBody implements Collideable{
    
    /** Creates a new instance of ConvexPolyhedra */
    public ConvexPolyhedra() {
        
    }
    
    /**
     * checks this object for collisions with a rigid body sphere. 
     * @param sphere the sphere to check for collisions with.
     * @param contacts a Vector in which any contacts found will be stored.
     */
    public void collideSphere(PhysicsSphere sphere, Vector<Contact> contacts) {
        
    };
    
    
    /**
     * checks this object for collisions with a rigid body box.
     * @param box the box to check for collisions with.
     * @param contacts a Vector in which any contacts found will be stored.
     */
  
    public void collideBox(PhysicsBox box, Vector<Contact> contacts) {
    
    }
    
    
    /**
     * Checks the object for collision against another Collideable.
     * This method will typically just initiate the correct the correct colllision detection method by double dispatch.
     *
     * @param c the Collideable to check collision with.
     * @param contacts
     */
    
    public void collide(Collideable c, Vector<Contact> contacts) {
        c.collideConvexPolyhedra(this,contacts);
    }
  
    
    /**
     * checks this object for collisions with a static plane.
     * @param plane the plane to check for collisions with.
     * @param contacts a Vector in which any contacts found will be stored.
     */
    public void collidePlane(PhysicsPlane plane, Vector<Contact> contacts) {
    }
    
   /**
     * checks this object for collisions with a convex polyhedra.
     * @param polyhedra the polyhedra to check for collisions with.
     * @param contacts a Vector in which any contacts found will be stored.
     */
      
    public void collideConvexPolyhedra(ConvexPolyhedra polyhedra, Vector<Contact> contacts) {
    }
    
    
    
}
