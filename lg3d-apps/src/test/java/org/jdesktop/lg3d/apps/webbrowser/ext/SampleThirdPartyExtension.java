/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.webbrowser.ext;

import java.util.Set;

/**
 * A stand-in "third-party" extension used only by {@code ExtensionRegistryTest}.
 * It is deliberately NOT listed in any classpath {@code META-INF/services} file;
 * the test writes a temporary jar whose service file names this class, so it is
 * discovered through the child {@link java.net.URLClassLoader} path exactly like
 * a real drop-in jar. It must be public with a public no-arg constructor for
 * {@link java.util.ServiceLoader}.
 */
public final class SampleThirdPartyExtension implements BrowserExtension {

    /** The id the registry test looks for. */
    public static final String ID = "test.sample-third-party";

    @Override
    public ExtensionManifest manifest() {
        return new ExtensionManifest(ID, "Sample Third Party", "0.1.0",
                "A test-only drop-in extension.", "Test",
                Set.of(Permission.NAVIGATE, Permission.TOOLBAR));
    }
}
