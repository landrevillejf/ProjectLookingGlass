/**
 * Project Looking Glass
 *
 * $RCSfile: X11IntegrationModule.java,v $
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 *
 * $Revision: 1.8 $
 * $Date: 2007-04-10 23:25:10 $
 * $State: Exp $
 */
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import org.jdesktop.lg3d.displayserver.nativewindow.IntegrationModule;
import java.util.logging.Level;
import java.util.logging.Logger;


public final class X11IntegrationModule implements IntegrationModule {
    private static final Logger logger = Logger.getLogger("lg.x11");
    
    public void initialize() {
        String display = System.getProperty("lg.lgserverdisplay");
	if (display == null) {
	    display = ":0";
	}
	try {
            logger.fine("Starting X11WindowManager on display " + display);
            // X11WindowManager will start it's own event thread.
            X11WindowManager wm = new X11WindowManager(display);

            // Optionally layer the Composite/Damage compositor on top of the
            // WM claim. The WM must already hold SubstructureRedirect (done in
            // its constructor) before CompositeRedirectSubwindows can succeed,
            // hence this happens after construction. Gated behind a system
            // property so the default WM-only path is unchanged.
            if (Boolean.getBoolean("lg3d.x11.compositor")) {
                logger.fine("lg3d.x11.compositor=true; starting X11Compositor");
                X11Compositor compositor =
                    new X11Compositor(wm.getDisplay(), wm.getRootWindow());
                wm.setCompositor(compositor);
                // Exempt lg3d's own Canvas3D window from WM management and
                // Composite redirection so it keeps drawing directly to screen.
                compositor.exemptOwnWindow();
            }

            logger.fine("X11 integration module successfully started");
	} catch (Throwable e) {
            logger.log(Level.SEVERE, "X Window Manager creation failed: ", e);
	    throw new RuntimeException("X Window Manager creation failed: " + e);
	}
    }
    
}
