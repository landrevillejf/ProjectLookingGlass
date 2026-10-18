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

/**
 * What the New Project form collects: a name, the build system, that tool's
 * version (ignored for {@link BuildSystem#NONE}) and the id of the JDK the
 * project selects from the manager. This is the single value handed from
 * {@link ProjectCard} to the host's {@code newProject}, so the card never talks
 * to the provisioner directly and the whole creation intent is testable as data.
 *
 * @param name        the project name (already passed through
 *                    {@code sanitizeProjectName} by the caller)
 * @param buildSystem Gradle, Maven or plain Java
 * @param toolVersion the build-tool version to download (blank for
 *                    {@link BuildSystem#NONE})
 * @param jdkId       the {@link JdkEntry#id()} of the selected JDK
 */
record NewProjectRequest(String name, BuildSystem buildSystem,
                         String toolVersion, String jdkId) {

    /** Normalises nulls to blanks so consumers never null-check. */
    NewProjectRequest {
        name = (name == null) ? "" : name;
        buildSystem = (buildSystem == null) ? BuildSystem.NONE : buildSystem;
        toolVersion = (toolVersion == null) ? "" : toolVersion.trim();
        jdkId = (jdkId == null) ? "" : jdkId.trim();
    }
}
