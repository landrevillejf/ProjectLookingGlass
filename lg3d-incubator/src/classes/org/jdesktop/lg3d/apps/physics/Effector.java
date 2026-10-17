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
 * Effector.java
 */

package org.jdesktop.lg3d.apps.physics;

import java.util.Vector;

/**
 * An abstract super class for things applying forces to physical objects.
 * Can be used for gravity, wind, air drag, etc. 
 */

abstract public class Effector {
    
    
    protected Vector<PhysicsObject> objects;
    
    /**
     * Constructor that initiates the objects vector.
     */
    public Effector() {
        objects=new Vector();
    }
    /**
     * Applies the effects of the Effector to its PhysicsObjects.
     * 
     */
    
    abstract public void apply();
    
    /**
     * Adds an object to the list of objects affected by this effector.
     * @param object the object to add to the list.
     */
    public void addObject(PhysicsObject object) {
        objects.addElement(object);
    }
            
    
}
