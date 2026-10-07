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

import java.util.function.Consumer;

/**
 * A loaded page, handed to extensions through
 * {@link BrowserExtension#onPageLoaded} as the content-script injection point.
 *
 * <p>The script runner is supplied by the broker: it is the real
 * {@code WebEngine.executeScript} for extensions granted
 * {@link Permission#CONTENT_SCRIPT} and a silent no-op otherwise, so the
 * permission gate is enforced without the extension having to check it.</p>
 */
public final class PageContext {

    private final String url;
    private final String title;
    private final Consumer<String> scriptRunner;

    /**
     * @param url          the committed page URL
     * @param title        the page title (may be empty)
     * @param scriptRunner runs injected JavaScript, or a no-op when not granted
     */
    public PageContext(String url, String title, Consumer<String> scriptRunner) {
        this.url = (url == null) ? "" : url;
        this.title = (title == null) ? "" : title;
        this.scriptRunner = scriptRunner;
    }

    public String getUrl() { return url; }
    public String getTitle() { return title; }

    /**
     * Injects and runs JavaScript in the page. A no-op when the extension was
     * not granted {@link Permission#CONTENT_SCRIPT}.
     *
     * @param js the script source
     */
    public void executeScript(String js) {
        if (js != null && !js.isBlank() && scriptRunner != null) {
            scriptRunner.accept(js);
        }
    }

    @Override
    public String toString() {
        return "PageContext[" + url + "]";
    }
}
