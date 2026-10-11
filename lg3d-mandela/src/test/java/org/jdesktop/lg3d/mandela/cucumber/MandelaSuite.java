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
package org.jdesktop.lg3d.mandela.cucumber;

import io.cucumber.junit.platform.engine.Constants;
import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * The BDD surface of the language: every {@code .feature} under
 * {@code src/test/resources/features} is discovered and run by the Cucumber JUnit
 * Platform engine inside the module's ordinary {@code test} task.
 *
 * <p>Why a suite class rather than per-feature runners: the module holds exactly
 * one JUnit Platform entry point, so {@code ./gradlew :lg3d-mandela:test} runs the
 * JUnit 5 unit suites and the Gherkin scenarios together, and JaCoCo folds both into
 * one coverage report (a scenario that exercises the VM counts toward the same
 * numbers as a {@code @Test}). The {@code glue} package is this one, so every step
 * definition lives beside it; the {@code features} resource is scanned recursively.</p>
 *
 * <p>Publishing: {@code publishHtml} writes the human-readable Cucumber report
 * under the build output, which CI keeps with the JUnit and JaCoCo artifacts. The
 * plugin is declared here rather than in {@code cucumber.properties} so the whole
 * configuration is visible in one file.</p>
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = Constants.GLUE_PROPERTY_NAME,
        value = "org.jdesktop.lg3d.mandela.cucumber")
@ConfigurationParameter(key = Constants.PLUGIN_PROPERTY_NAME,
        value = "html:build-gradle/reports/cucumber/index.html, "
              + "json:build-gradle/reports/cucumber/cucumber.json, "
              + "summary")
public class MandelaSuite {
    // Discovery-only: the @Suite annotation is the whole program. Adding methods
    // here would make it both a suite and a test class, which is not what this is.
}
