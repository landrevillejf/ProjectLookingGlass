/**
 * Project Looking Glass
 *
 * $RCSfile: ScreenCaptureEvent.java,v $
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
 * $Date: 2006-05-05 16:29:17 $
 * $State: Exp $
 */
package org.jdesktop.lg3d.displayserver;

import java.awt.Rectangle;

import org.jdesktop.lg3d.wg.event.LgEvent;

/**
 * Event for triggering screen capture
 *
 * @author  paulby
 */
public class ScreenCaptureEvent extends LgEvent {
    
    /**
     * Capture either a SINGLE screen, START capturing every screen,
     * STOP capturing every screen or capture the specificed number 
     * of frames.
     */
    public enum CaptureMode { SINGLE, START, STOP, START_COUNT };
    
    private CaptureMode mode;
    private int frameCount;
    
    /**
     * Extension string used to determine formate, ie png, gif, etc
     */
    private String format;
    
    /**
     * Directory in which to place images
     */
    private String dir;
    
    /**
     * Optional crop region in screen/canvas coordinates (x, y, width, height).
     * When set, the captured raster is cropped to this rectangle; when null the
     * whole canvas/screen is captured. The Screen Snapshot app's "selection"
     * area sets it so the 3D desktop can capture just the region the user
     * dragged out.
     */
    private Rectangle region;
    
    /** Capture a SINGLE screen */
    public ScreenCaptureEvent(String directory) {
        this(CaptureMode.SINGLE, directory);
    }
    
    /**
     * Create a capture event with the specified mode.
     *
     * File(s) will be .png and will be placed in /tmp
     */
    public ScreenCaptureEvent( CaptureMode mode, String directory ) {
        this.mode = mode;
        format="png";
        dir=directory;
    }
    
    /**
     * Set the mode for this event
     */
    public void setCaptureMode( CaptureMode mode ) {
        this.mode = mode;
    }
    
    /**
     * Return the capture mode for this event
     */
    public CaptureMode getCaptureMode() {
        return mode;
    }
    
    /**
     * Set the number of frames to be captured
     */
    public void setFrameCount( int frameCount ) {
        this.frameCount = frameCount;
    }
    
    /**
     * Returns the number of frames to be captured
     */
    public int getFrameCount() {
        return frameCount;
    }
    
    /**
     * Return the format string
     * eg png, gif etc
     */
    public String getFormat() {
        return format;
    }
    
    /**
     * Return the directory in which the image(s) should be stored
     */
    public String getDirectory() {
        return dir;
    }
    
    /**
     * Set the optional crop region; a defensive copy is taken. A null region
     * clears it, restoring a whole-canvas capture.
     */
    public void setRegion( Rectangle region ) {
        this.region = (region == null) ? null : new Rectangle(region);
    }
    
    /**
     * Return the crop region, or null for a whole-canvas capture. A defensive
     * copy is returned.
     */
    public Rectangle getRegion() {
        return (region == null) ? null : new Rectangle(region);
    }
    
    /**
     * True when a crop region has been set.
     */
    public boolean hasRegion() {
        return region != null;
    }
}
