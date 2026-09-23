/**
 * Project Looking Glass
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
 */
package org.jdesktop.lg3d.apps.imagestudio;

/**
 * The Image Studio application entry point.
 *
 * <p>Builds the {@link ImageStudioFrame3D} editing window and makes it live,
 * following the same {@code main} pattern as {@code Lg3dHelp} and
 * {@code FileManager}: construct the {@code Frame3D}, then
 * {@code changeEnabled(true)} and {@code changeVisible(true)}.</p>
 *
 * <p>The start-menu descriptor
 * ({@code lg3d-apps/src/config/imagestudio.lgcfg}) launches this class
 * in-JVM with the command {@code java org.jdesktop.lg3d.apps.imagestudio.ImageStudioApp}.
 * The bundled JAI jars and the incubator jar are on the {@code :lg3d-core:run}
 * classpath, so the app and {@code javax.media.jai.*} both resolve.</p>
 */
public class ImageStudioApp {

    public static void main(String[] args) {
        ImageStudioFrame3D app = new ImageStudioFrame3D();
        app.changeEnabled(true);
        app.changeVisible(true);
    }
}
