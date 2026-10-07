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

/**
 * A capability an extension declares in its {@link ExtensionManifest} and that
 * the user must grant (through the extension manager) before the matching hook
 * or {@link BrowserContext} capability becomes live.
 *
 * <p>Permissions are the browser's consent boundary: an enabled extension only
 * receives the hooks and context methods its granted permissions cover. This is
 * a user-consent / UX boundary, not a JVM sandbox &mdash; in-process extension
 * code is trusted code, and the manager makes that explicit when approving.</p>
 */
public enum Permission {

    /** Veto or redirect navigations via {@link BrowserExtension#onNavigate}. */
    NAVIGATE,

    /** Allow or block popups via {@link BrowserExtension#onPopup}. */
    POPUP,

    /** Inject JavaScript into loaded pages via {@link PageContext#executeScript}. */
    CONTENT_SCRIPT,

    /** Observe or influence downloads. */
    DOWNLOADS,

    /** Read or change browser settings. */
    SETTINGS,

    /** Contribute toolbar buttons via {@link BrowserExtension#toolbarContributions}. */
    TOOLBAR
}
