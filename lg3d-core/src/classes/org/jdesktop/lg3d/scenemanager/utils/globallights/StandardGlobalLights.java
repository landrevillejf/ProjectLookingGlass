/**
 * Project Looking Glass
 *
 * $RCSfile: StandardGlobalLights.java,v $
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
 * $Revision: 1.6 $
 * $Date: 2007-01-29 18:18:43 $
 * $State: Exp $
 */
package org.jdesktop.lg3d.scenemanager.utils.globallights;

import org.jogamp.vecmath.Color3f;
import org.jogamp.vecmath.Vector3f;
import org.jogamp.vecmath.Point3f;

import org.jdesktop.lg3d.sg.AmbientLight;
import org.jdesktop.lg3d.sg.BoundingSphere;
import org.jdesktop.lg3d.sg.DirectionalLight;
import org.jdesktop.lg3d.sg.Light;
import org.jdesktop.lg3d.utils.schedule.DayNightCurve;
import org.jdesktop.lg3d.wg.Component3D;


public class StandardGlobalLights extends GlobalLights {

    /**
     * The most recently initialized rig, so the daylight/nightlight schedule
     * can reach the live lights; null until {@link #initialize()} has run (and
     * therefore null on the 2D desktop, which builds no 3D scene).
     */
    private static volatile StandardGlobalLights live;

    private AmbientLight ambientLight;
    private DirectionalLight keyLight;
    private DirectionalLight fillLight;

    /** The live global-light rig, or null when the 3D desktop is not running. */
    public static StandardGlobalLights live() {
        return live;
    }

    public void initialize() {
        setName("StandardGlobalLights");

        BoundingSphere bounds
            = new BoundingSphere(
                new Point3f(0.0f, 0.0f, 0.0f), Float.POSITIVE_INFINITY);

        Component3D top = new Component3D();
        top.setName("StandardGlobalLights");

        // Set up the global lights. Each keeps ALLOW_COLOR_WRITE so the
        // day/night schedule can re-tint it after the scene graph goes live;
        // the capability must be set before the node is made live.
        Color3f ambientColor = new Color3f(0.4f, 0.4f, 0.4f);
        Color3f light1Color = new Color3f(0.7f, 0.7f, 0.6f);
        Vector3f light1Direction  = new Vector3f(1.0f, -1.0f, -2.0f);
        Color3f light2Color = new Color3f(0.2f, 0.2f, 0.3f);
        Vector3f light2Direction  = new Vector3f(-1.0f, 1.0f, 0.0f);

        ambientLight = new AmbientLight(ambientColor);
        ambientLight.setCapability(Light.ALLOW_COLOR_WRITE);
        ambientLight.setInfluencingBounds(bounds);
        top.addChild(ambientLight);

        keyLight = new DirectionalLight(light1Color, light1Direction);
        keyLight.setCapability(Light.ALLOW_COLOR_WRITE);
        keyLight.setInfluencingBounds(bounds);
        top.addChild(keyLight);

        fillLight = new DirectionalLight(light2Color, light2Direction);
        fillLight.setCapability(Light.ALLOW_COLOR_WRITE);
        fillLight.setInfluencingBounds(bounds);
        top.addChild(fillLight);

        addChild(top);

        live = this;
    }

    /**
     * Re-tints the whole rig for the day/night blend {@code t} (0 = full
     * daylight, 1 = full night), interpolating each light's colour between the
     * day and night palettes. A no-op before {@link #initialize()} has built
     * the lights. Safe to call repeatedly; the caller drives the schedule.
     */
    public void applyDayNight(float t) {
        if (ambientLight == null || keyLight == null || fillLight == null) {
            return;
        }
        ambientLight.setColor(toColor(
                DayNightCurve.lerp(DayNightCurve.DAY_AMBIENT, DayNightCurve.NIGHT_AMBIENT, t)));
        keyLight.setColor(toColor(
                DayNightCurve.lerp(DayNightCurve.DAY_KEY, DayNightCurve.NIGHT_KEY, t)));
        fillLight.setColor(toColor(
                DayNightCurve.lerp(DayNightCurve.DAY_FILL, DayNightCurve.NIGHT_FILL, t)));
    }

    private static Color3f toColor(float[] rgb) {
        return new Color3f(rgb[0], rgb[1], rgb[2]);
    }
}
