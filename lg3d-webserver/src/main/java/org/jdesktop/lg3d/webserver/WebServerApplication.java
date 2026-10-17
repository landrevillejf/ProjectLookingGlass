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
package org.jdesktop.lg3d.webserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.jdesktop.lg3d.webserver.config.WebServerProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The Spring Boot entry point: the one lifecycle that owns embedded Tomcat, the
 * management REST surface, the deployment registry and the reverse-proxy filter.
 *
 * <p>Mirrors {@code wonderland-server}'s {@code WonderlandServerApplication}. It
 * is made executable by the Spring Boot Gradle plugin's {@code bootJar} task; a
 * human starts it with {@code ./gradlew :lg3d-webserver:bootRun} and the desktop
 * console starts it as a child process with {@code java -jar}. The headless test
 * suite boots the same context in-JVM
 * ({@code @SpringBootTest(webEnvironment = MOCK)}), so the wiring is proved
 * without binding a real port.</p>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(WebServerProperties.class)
public class WebServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebServerApplication.class, args);
    }
}
