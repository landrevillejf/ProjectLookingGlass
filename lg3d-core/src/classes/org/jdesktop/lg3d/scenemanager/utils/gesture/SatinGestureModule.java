/**
 * Project Looking Glass
 *
 * $RCSfile: SatinGestureModule.java,v $
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
 * $Date: 2006-03-01 19:31:24 $
 * $State: Exp $
 */
package org.jdesktop.lg3d.scenemanager.utils.gesture;

import org.jdesktop.lg3d.displayserver.AppConnectorPrivate;
import org.jdesktop.lg3d.wg.event.MouseMotionEvent3D;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.scenemanager.utils.event.Component3DGestureMoveLeftEvent;
import org.jdesktop.lg3d.scenemanager.utils.event.Component3DGestureMoveRightEvent;

/**
 * Implementation of the Project Looking Glass gesture system.
 *
 * <p>Historically this module sat on top of the SATIN stroke-recognition
 * library from Berkeley (http://guir.berkeley.edu/projects/satin), which used
 * Rubine's algorithm trained from {@code /resources/core-gestures.gsa}. The
 * bundled {@code satin-v2.3.jar} was compiled against the legacy
 * {@code javax.media.j3d}/{@code javax.vecmath} packages and is therefore
 * binary-incompatible with the Jogamp Java 3D rename adopted by this JDK 21
 * port. Rather than drag in the whole SATIN/Rubine stack, this reimplementation
 * classifies the captured stroke geometrically, which covers the common
 * horizontal "move window left/right" gestures. More intricate trained gestures
 * (e.g. zoom/flip shapes) are no longer recognised and are simply logged.
 */
class SatinGestureModule extends GestureModuleBase {

    /**
     * Minimum normalised horizontal travel before a stroke counts as a
     * deliberate left/right gesture.
     */
    private static final float MIN_TRAVEL = 0.05f;

    public SatinGestureModule() {
        super();
        logger.fine("Gesture module configured (geometric stroke classifier)");
    }

    /**
     * Classify the captured gesture stroke and post the corresponding event.
     */
    void processGesture() {
        logger.finer("Starting processGesture, eventQueue size " + gestureEvents.size());

        if (gestureEvents.size() < 2) {
            logger.fine("Gesture too short to classify");
            return;
        }

        MouseMotionEvent3D first = gestureEvents.get(0);
        MouseMotionEvent3D last = gestureEvents.get(gestureEvents.size() - 1);

        float dx = last.getImagePlateX() - first.getImagePlateX();
        float dy = last.getImagePlateY() - first.getImagePlateY();

        Frame3D startFrame3D =
            (Frame3D) startEvent.getIntersectedComponent3D(0, Frame3D.class);
        if (startFrame3D == null)
            return;

        // Only unambiguous, predominantly-horizontal swipes are recognised.
        if (Math.abs(dx) < MIN_TRAVEL || Math.abs(dx) < Math.abs(dy)) {
            logger.fine("Unrecognised gesture (dx=" + dx + ", dy=" + dy + ")");
            return;
        }

        if (dx < 0) {
            logger.fine("Posting Component3DGestureMoveLeftEvent");
            AppConnectorPrivate.getAppConnector().postEvent(
                new Component3DGestureMoveLeftEvent(), startFrame3D);
        } else {
            logger.fine("Posting Component3DGestureMoveRightEvent");
            AppConnectorPrivate.getAppConnector().postEvent(
                new Component3DGestureMoveRightEvent(), startFrame3D);
        }
    }
}
