/*
 * Project Looking Glass
 *
 * $RCSfile: WebIcon.java,v $
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
 *
 * $Revision: 1.3 $
 * $Date: 2005-04-14 23:14:21 $
 * $State: Exp $
 */
package org.jdesktop.lg3d.apps.tapps;

import org.jogamp.java3d.utils.geometry.Sphere;
import org.jogamp.java3d.utils.image.TextureLoader;
import org.jogamp.java3d.Alpha;
import org.jogamp.java3d.Appearance;
import org.jogamp.java3d.BoundingSphere;
import org.jogamp.java3d.BranchGroup;
import org.jogamp.java3d.RotationInterpolator;
import org.jogamp.java3d.Texture;
import org.jogamp.java3d.Transform3D;
import org.jogamp.java3d.TransformGroup;
import org.jogamp.vecmath.Point3d;
import org.jogamp.vecmath.Vector3f;
import org.jdesktop.lg3d.utils.action.AppLaunchAction;
import org.jdesktop.lg3d.utils.action.ScaleActionBoolean;
import org.jdesktop.lg3d.utils.action.TranslateActionBoolean;
import org.jdesktop.lg3d.utils.c3danimation.NaturalMotionAnimation;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.eventadapter.MouseEnteredEventAdapter;
import org.jdesktop.lg3d.utils.eventadapter.MousePressedEventAdapter;
import org.jdesktop.lg3d.wg.Cursor3D;
import org.jdesktop.lg3d.wg.Java3DGraph;
import org.jdesktop.lg3d.wg.Tapp;

import org.jdesktop.lg3d.wg.event.MouseEvent3D.ButtonId;

/**
 * A sample 3D icon
 *
 * @author  paulby
 */
public class WebIcon extends Tapp {
    
    /** Creates a new instance of WebIcon */
    public WebIcon() {
        setPreferredSize(new Vector3f(0.01f,0.01f,0.01f));
        
        Java3DGraph j3d = new Java3DGraph();
        j3d.setAnimation(new NaturalMotionAnimation(150));
        
        j3d.addListener(
            new MouseEnteredEventAdapter(
                new TranslateActionBoolean(j3d, new Vector3f(0.0f, 0.01f * 0.2f, 0.0f), 175)));
        j3d.addListener(
            new MouseEnteredEventAdapter(
                new ScaleActionBoolean(j3d, 1.2f, 175)));
        j3d.addListener(
            new MousePressedEventAdapter(
                new ScaleActionBoolean(j3d, 1.1f, 100)));
        j3d.setMouseEventPropagatable(true);
        
        //new MouseClickedEventAdapter(this, new AppLaunchAction("java org.jdesktop.lg3d.apps.tutorial.SwingNodeTutorial"));
        setCursor(Cursor3D.SMALL_CURSOR);
        
//        this.addAnimation(new RotationAnimation(new MouseClickedEventAdapter(this, ButtonId.BUTTON1), 1000L, LoopType.ONCE, (Class)null, 0f, (float)(2f*Math.PI), new Vector3f(1f,0f,0f)));
        //this.addAnimation(new TranslationAnimation());
        
        //this.addAnimation(new MoveAnimation(new MouseDraggedEventAdapter(this)));

        j3d.addJ3dChild(buildGraph());
        addChild(j3d);
    }
    
    private BranchGroup buildGraph() {
        BranchGroup ret = new BranchGroup();
        
        Appearance app = new Appearance();
        TextureLoader loader = new TextureLoader( this.getClass().getResource("/org/jdesktop/lg3d/apps/tutorial/resources/images/earth.jpg"),null);
        Texture tex = loader.getTexture();
        app.setTexture(tex);
        
        TransformGroup planetRot = new TransformGroup();
        planetRot.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);
        planetRot.setCapability(TransformGroup.ALLOW_TRANSFORM_READ);
        
        Alpha planetAlpha = new Alpha(-1,10000l);
        RotationInterpolator planetInt = new RotationInterpolator(planetAlpha,planetRot);
        planetInt.setSchedulingBounds(new BoundingSphere(new Point3d(), Double.POSITIVE_INFINITY));
        planetRot.addChild(planetInt);

        Sphere planet = new Sphere( 0.004f, Sphere.GENERATE_NORMALS | Sphere.GENERATE_TEXTURE_COORDS, 9, app );
        planetRot.addChild(planet);
        
        Sphere moon = new Sphere( 0.001f, Sphere.GENERATE_NORMALS, 5, new Appearance() );
        
        TransformGroup moonPos = new TransformGroup();
        Transform3D t3d = new Transform3D();
        t3d.setTranslation(new Vector3f(0.005f,0f,0f));
        moonPos.setTransform(t3d);
        
        TransformGroup moonRot = new TransformGroup();
        moonRot.setCapability(TransformGroup.ALLOW_TRANSFORM_WRITE);
        moonRot.setCapability(TransformGroup.ALLOW_TRANSFORM_READ);
        
        moonRot.addChild(moonPos);
        moonPos.addChild(moon);
        
        Transform3D axis = new Transform3D();
        axis.rotZ(Math.toRadians(10));
        Alpha moonAlpha = new Alpha(-1,2000l);
        RotationInterpolator moonInt = new RotationInterpolator(moonAlpha,moonRot,axis,0f,(float)Math.PI*2f);
        moonInt.setSchedulingBounds(new BoundingSphere(new Point3d(), Double.POSITIVE_INFINITY));
        
        moonRot.addChild(moonInt);
        
        ret.addChild(planetRot);
        ret.addChild(moonRot);
        
        return ret;
    }
    
}
