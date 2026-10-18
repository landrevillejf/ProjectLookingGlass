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
package org.jdesktop.lg3d.apps.texteditor;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Seam over "fetch this URL to this file", so provisioning a build tool or a
 * JDK is headless-testable: production uses {@link HttpDownloader}, tests inject
 * a fake that writes canned archive bytes without touching the network. This is
 * the only place in the editor that reaches out to download anything, and it is
 * reached only while creating a project or adding a JDK to the manager - never
 * during an ordinary build or run.
 */
interface Downloader {

    /**
     * Downloads {@code url} into {@code dest}, creating its parent directory.
     *
     * @param url  the absolute {@code http(s)} URL to fetch
     * @param dest the file to write
     * @throws IOException when the transfer fails or the server reports an error
     */
    void fetch(String url, Path dest) throws IOException;
}
