package org.jdesktop.lg3d.displayserver.crash;

import io.cucumber.junit.platform.engine.Constants;
import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * BDD entry point: discovers every {@code .feature} under
 * {@code src/test/resources/features} and runs them through the Cucumber
 * JUnit Platform engine inside the module's ordinary {@code test} task.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = Constants.GLUE_PROPERTY_NAME,
        value = "org.jdesktop.lg3d.displayserver.crash")
@ConfigurationParameter(key = Constants.PLUGIN_PROPERTY_NAME,
        value = "html:build-gradle/reports/cucumber/index.html, summary")
public class CucumberSuite {
    // Discovery-only: the @Suite annotation is the whole program.
}
