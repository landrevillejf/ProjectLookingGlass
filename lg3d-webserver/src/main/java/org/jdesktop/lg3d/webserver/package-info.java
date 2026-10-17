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
/**
 * The LG3D web server: a self-contained Spring Boot (embedded Tomcat) server
 * that hosts and supervises containerized Spring Boot applications.
 *
 * <p>This module is the backend half of the desktop's <em>Web Server Console</em>
 * (the frontend half lives in {@code lg3d-apps},
 * {@code org.jdesktop.lg3d.apps.webserver}). It is built as an executable
 * {@code bootJar} and run as a <strong>child process</strong> -- like
 * {@code wonderland-server}, it is deliberately never placed on the desktop
 * classpath, so Spring Boot never enters the desktop JVM. The console forks the
 * jar (or attaches to an already-running instance) and drives it over the
 * management REST API.</p>
 *
 * <p>Two hard rules govern this package, both inherited from the repository's
 * conventions:</p>
 * <ul>
 *   <li><strong>No lg3d-core, no Java 3D.</strong> Nothing here may import
 *       {@code org.jdesktop.lg3d.*} scene-graph types or {@code org.jogamp.*};
 *       the server is a plain Spring Boot process.</li>
 *   <li><strong>Degrade, never 500.</strong> Docker is optional and driven
 *       through the system {@code docker} CLI (no {@code docker-java}). When no
 *       daemon is reachable the management API reports
 *       {@code dockerAvailable=false} and a deploy fails with a readable
 *       {@code 503} body -- never an unhandled stack trace.</li>
 * </ul>
 */
package org.jdesktop.lg3d.webserver;
