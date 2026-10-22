/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jdesktop.lg3d.displayserver.desktop2d.AppProcessSupervisor.Decision;
import org.jdesktop.lg3d.displayserver.desktop2d.AppProcessSupervisor.Policy;
import org.jdesktop.lg3d.displayserver.desktop2d.ChildProcessLauncher.ChildProfile;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DAppRegistry.Kind;

/**
 * Step definitions for {@code process-isolation.feature}: the observable
 * contract of QNX-style child-process isolation, driven entirely through the
 * headless-safe seams - {@link Desktop2DAppRegistry#classify},
 * {@link ChildProcessLauncher}'s command/environment builders and
 * {@link AppProcessSupervisor#decide}. No JVM is forked and no display is
 * touched, so the scenarios run in the ordinary headless {@code test} task.
 */
public class ProcessIsolationSteps {

    private static final String TOR_PROXY = "socks5h://127.0.0.1:9050";

    private String command;
    private Kind kind;
    private String childMain;
    private List<String> cmd;
    private Map<String, String> env;
    private Policy policy;
    private Decision decision;
    private String panelClass;
    private String hostMain;
    private List<String> hostArgs;
    private List<String> jvmArgs;
    private String configKey;
    private String configValue;
    private AppProcessSupervisor supervisor;
    private StubStarter stubStarter;
    private StubNotifier stubNotifier;

    // ------------------------------------------------------------------
    // Classification
    // ------------------------------------------------------------------

    @Given("the start-menu command {string}")
    public void theStartMenuCommand(final String value) {
        this.command = value;
    }

    @When("the desktop classifies the command")
    public void theDesktopClassifiesTheCommand() {
        this.kind = Desktop2DAppRegistry.classify(command);
        this.childMain = Desktop2DAppRegistry.childMainClass(command);
    }

    @Then("the kind is {string}")
    public void theKindIs(final String expected) {
        assertEquals(expected, kind.name());
    }

    @Then("its child main class is {string}")
    public void itsChildMainClassIs(final String expected) {
        assertEquals(expected, childMain);
    }

    @Then("it has no in-JVM hosted panel")
    public void itHasNoInJvmHostedPanel() {
        assertNull(Desktop2DAppRegistry.panelClass(command));
    }

    // ------------------------------------------------------------------
    // Launch command
    // ------------------------------------------------------------------

    @Given("an isolated child app {string}")
    public void anIsolatedChildApp(final String mainClass) {
        this.childMain = mainClass;
    }

    @When("the desktop builds its launch command")
    public void theDesktopBuildsItsLaunchCommand() {
        this.cmd = ChildProcessLauncher.buildCommand(
                ChildProcessLauncher.defaultJavaBin(),
                ChildProcessLauncher.currentClasspath(),
                ChildProcessLauncher.browserJvmArgs(),
                childMain, Collections.emptyList());
    }

    @Then("the command starts with this JVM's java launcher")
    public void theCommandStartsWithThisJvmsJavaLauncher() {
        assertEquals(ChildProcessLauncher.defaultJavaBin(), cmd.get(0));
    }

    @Then("the command runs the child main class on the desktop classpath")
    public void theCommandRunsTheChildMainClassOnTheDesktopClasspath() {
        int cp = cmd.indexOf("-cp");
        assertTrue(cp >= 0, "the command passes a classpath");
        assertEquals(ChildProcessLauncher.currentClasspath(), cmd.get(cp + 1));
        assertEquals(childMain, cmd.get(cp + 2));
    }

    @Then("the JVM arguments bound the child heap with {string}")
    public void theJvmArgumentsBoundTheChildHeapWith(final String flag) {
        assertTrue(cmd.contains(flag), "the heap is bounded by " + flag);
    }

    // ------------------------------------------------------------------
    // Child environment
    // ------------------------------------------------------------------

    @Given("an isolated browser child")
    public void anIsolatedBrowserChild() {
        // The browser child is the reference migration; nothing to set up.
    }

    @When("the desktop builds its environment over the privacy base")
    public void theDesktopBuildsItsEnvironmentOverThePrivacyBase() {
        this.env = ChildProcessLauncher.browserEnv(
                Map.of("ALL_PROXY", TOR_PROXY), ":0");
    }

    @Then("the environment disables WebKit compositing and DMABUF")
    public void theEnvironmentDisablesWebkitCompositingAndDmabuf() {
        assertEquals("1", env.get("WEBKIT_DISABLE_COMPOSITING_MODE"));
        assertEquals("1", env.get("WEBKIT_DISABLE_DMABUF_RENDERER"));
    }

    @Then("the environment targets the lg3d display")
    public void theEnvironmentTargetsTheLg3dDisplay() {
        assertEquals(":0", env.get("DISPLAY"));
    }

    @Then("a privacy-critical base variable is never overwritten")
    public void aPrivacyCriticalBaseVariableIsNeverOverwritten() {
        assertEquals(TOR_PROXY, env.get("ALL_PROXY"));
    }

    // ------------------------------------------------------------------
    // Restart policy
    // ------------------------------------------------------------------

    @Given("a restart policy allowing {int} attempts over a {long} ms stable window")
    public void aRestartPolicy(final int maxAttempts, final long stableMillis) {
        this.policy = new Policy(maxAttempts, stableMillis);
    }

    @When("a child exits with code {int} after {long} ms having taken {int} attempts")
    public void aChildExits(final int exitCode, final long livedMillis,
            final int priorAttempts) {
        this.decision = AppProcessSupervisor.decide(
                exitCode, livedMillis, priorAttempts, policy);
    }

    @Then("the supervisor decides to relaunch it")
    public void theSupervisorDecidesToRelaunchIt() {
        assertEquals(Decision.RELAUNCH, decision);
    }

    @Then("the supervisor decides to stop cleanly")
    public void theSupervisorDecidesToStopCleanly() {
        assertEquals(Decision.STOP_CLEAN, decision);
    }

    @Then("the supervisor decides to relaunch on a fresh budget")
    public void theSupervisorDecidesToRelaunchOnAFreshBudget() {
        assertEquals(Decision.RELAUNCH_RESET, decision);
    }

    @Then("the supervisor decides to give up")
    public void theSupervisorDecidesToGiveUp() {
        assertEquals(Decision.STOP_EXHAUSTED, decision);
    }

    // ------------------------------------------------------------------
    // Generic panel host
    // ------------------------------------------------------------------

    @After
    public void clearConfigurationProperty() {
        if (configKey != null) {
            System.clearProperty(configKey);
        }
    }

    @Given("the leaf panel class {string}")
    public void theLeafPanelClass(final String value) {
        this.panelClass = value;
    }

    @When("the desktop builds its panel-host recipe")
    public void theDesktopBuildsItsPanelHostRecipe() {
        this.hostMain = Desktop2DAppRegistry.panelHostMain();
        this.hostArgs = Desktop2DAppRegistry.panelHostArgs(panelClass);
    }

    @Then("the child main is the generic panel host")
    public void theChildMainIsTheGenericPanelHost() {
        assertEquals("org.jdesktop.lg3d.apps.host.PanelHostApp", hostMain);
    }

    @Then("the host arguments name the panel class")
    public void theHostArgumentsNameThePanelClass() {
        assertEquals(List.of("--panel", panelClass), hostArgs);
    }

    // ------------------------------------------------------------------
    // Child configuration inheritance
    // ------------------------------------------------------------------

    @Given("a desktop configuration property {string} set to {string}")
    public void aDesktopConfigurationProperty(final String key, final String value) {
        this.configKey = key;
        this.configValue = value;
        System.setProperty(key, value);
    }

    @When("the desktop builds the child JVM arguments for the {string} profile")
    public void theDesktopBuildsTheChildJvmArguments(final String profile) {
        this.jvmArgs = ChildProcessLauncher.jvmArgsFor(ChildProfile.valueOf(profile));
    }

    @Then("the arguments mark the child isolated")
    public void theArgumentsMarkTheChildIsolated() {
        assertEquals("-D" + ChildProcessLauncher.ISOLATED_CHILD_PROPERTY + "=true",
                jvmArgs.get(0));
    }

    @Then("the arguments carry the configuration property {string}")
    public void theArgumentsCarryTheConfigurationProperty(final String key) {
        assertTrue(jvmArgs.contains("-D" + key + "=" + configValue),
                "the child inherits " + key + " from the desktop JVM");
    }

    @Then("the arguments bound a Swing child heap with {string}")
    public void theArgumentsBoundASwingChildHeap(final String flag) {
        assertTrue(jvmArgs.contains(flag), "the Swing heap is bounded by " + flag);
    }

    @Then("the arguments carry no JavaFX pipeline")
    public void theArgumentsCarryNoJavafxPipeline() {
        assertTrue(jvmArgs.stream().noneMatch(a -> a.startsWith("-Dprism.order=")),
                "a plain-Swing child gets no JavaFX pipeline");
        assertFalse(jvmArgs.stream().anyMatch(a -> a.startsWith("-Dprism.")),
                "no Prism flag at all reaches a Swing child");
    }

    // ------------------------------------------------------------------
    // Graceful shutdown on desktop exit
    // ------------------------------------------------------------------

    @Given("an isolated child supervised by the desktop")
    public void anIsolatedChildSupervisedByTheDesktop() {
        this.stubStarter = new StubStarter();
        this.stubNotifier = new StubNotifier();
        this.supervisor = new AppProcessSupervisor("Child",
                List.of("java", "-cp", "x", "org.foo.Main"),
                Map.of("DISPLAY", ":0"), new Policy(2, 1000),
                stubStarter, () -> 0L, stubNotifier);
        supervisor.launch();
    }

    @When("the desktop shuts the child down")
    public void theDesktopShutsTheChildDown() {
        supervisor.shutdown();
    }

    @Then("the child process is no longer alive")
    public void theChildProcessIsNoLongerAlive() {
        assertFalse(supervisor.isAlive(), "shutdown destroyed the live child");
    }

    @Then("the child is not restarted")
    public void theChildIsNotRestarted() {
        assertEquals(1, stubStarter.started, "no replacement child was forked");
        assertEquals(0, stubNotifier.restarting, "a teardown is not a restart");
    }

    @Then("no stop is reported to the user")
    public void noStopIsReportedToTheUser() {
        assertEquals(0, stubNotifier.stopped, "the desktop going away is silent");
    }

    /** A {@link Process} whose exit {@code destroy()} triggers synchronously. */
    private static final class StubProcess extends Process {
        private final CompletableFuture<Process> exit = new CompletableFuture<>();
        private boolean alive = true;

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            exit.join();
            return 143;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException();
            }
            return 143;
        }

        @Override
        public void destroy() {
            alive = false;
            exit.complete(this);
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }

    /** Counts the children it forks; hands out one {@link StubProcess}. */
    private static final class StubStarter implements AppProcessSupervisor.Starter {
        int started;

        @Override
        public Process start(final List<String> command,
                final Map<String, String> env) {
            started++;
            return new StubProcess();
        }
    }

    /** Records the lifecycle transitions the desktop would surface. */
    private static final class StubNotifier implements AppProcessSupervisor.Notifier {
        int restarting;
        int stopped;

        @Override
        public void alreadyOpen() {
            // not exercised by the shutdown scenario
        }

        @Override
        public void restarting(final int attempt) {
            restarting++;
        }

        @Override
        public void stopped(final int exitCode) {
            stopped++;
        }

        @Override
        public void gaveUp(final int attempts) {
            // not exercised by the shutdown scenario
        }

        @Override
        public void startFailed() {
            // not exercised by the shutdown scenario
        }
    }
}
