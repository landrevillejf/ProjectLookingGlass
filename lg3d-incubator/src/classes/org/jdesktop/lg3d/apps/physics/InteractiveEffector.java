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
 * Effector to add an interactive force to objects.
 * It contains a force vector that can be set that is added to the affected objects each time frame.
 * The force vector can be set to decay over time.
 *
 * @param multiple a multiple that is applied to the force vector each time it is set.
 * @param decay a multiple that is applied to the force vector each time has been applied to the obejcts.
 */

public class InteractiveEffector extends Effector {
    private Vector3f currentForce;
    private float decay;
    private float multiple;
            
    public InteractiveEffector(float multiple, float decay) {
        currentForce=new Vector3f();
        this.multiple=multiple;
        this.decay=decay;
    }
    
    public void apply() {
        for(int i=0;i<objects.size();i++) 
            objects.get(i).addForce(currentForce);
        currentForce.scale(decay);
        
    }
    
    public void setCurrentForce(Vector3f force) {
        currentForce.set(force);
        currentForce.scale(multiple);
        
    }
    
    
}
