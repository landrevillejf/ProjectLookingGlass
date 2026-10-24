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
package org.jdesktop.lg3d.utils.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jdesktop.lg3d.utils.system.InitSystemService.InitSystem;
import org.jdesktop.lg3d.utils.system.InitSystemService.Operation;
import org.jdesktop.lg3d.utils.system.ServerService.Probes;
import org.jdesktop.lg3d.utils.system.ServerService.ServerDef;

/**
 * Step definitions for {@code server-services.feature}: the observable contract
 * of the §4.9 server-services catalog, driven entirely through the headless-safe
 * pure seam - {@link ServerService#isInstalled}, {@link ServerService#installedServers},
 * {@link ServerService#lifecycleCommand}, {@link ServerService#isSupported} and
 * {@link ServerService#readConfig}. No server is installed and no process is
 * spawned, so the scenarios run in the ordinary headless {@code test} task.
 */
public class ServerServiceSteps {

    private Probes probes;
    private ServerDef def;
    private InitSystem init;
    private boolean installed;
    private boolean supported;
    private List<ServerDef> installedList;
    private List<String> vector;
    private ConfigFileReader.Content content;

    private static ServerDef byUnit(final String unit) {
        for (ServerDef d : ServerService.catalog()) {
            if (d.getServiceUnit().equals(unit)) {
                return d;
            }
        }
        throw new IllegalArgumentException("no catalog entry for unit " + unit);
    }

    private static Set<String> toSet(final String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv != null && !csv.isBlank()) {
            for (String s : csv.split(",")) {
                out.add(s.trim());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Detection

    @Given("server probes with binaries {string} and configs {string}")
    public void serverProbes(final String binaries, final String configs) {
        this.probes = new Probes(toSet(binaries), toSet(configs), InitSystem.SYSTEMD);
    }

    @When("the catalog entry {string} is checked for installation")
    public void checkInstalled(final String unit) {
        this.def = byUnit(unit);
        this.installed = ServerService.isInstalled(probes, def);
    }

    @Then("the server is installed")
    public void thenInstalled() {
        assertTrue(installed);
    }

    @Then("the server is not installed")
    public void thenNotInstalled() {
        assertFalse(installed);
    }

    @When("the installed servers are listed")
    public void listInstalled() {
        this.installedList = ServerService.installedServers(probes);
    }

    @Then("the installed servers are {string} in order")
    public void thenInstalledOrder(final String expectedCsv) {
        List<String> names = new ArrayList<>();
        for (ServerDef d : installedList) {
            names.add(d.getDisplayName());
        }
        assertEquals(List.of(expectedCsv.split(",")), names);
    }

    // ------------------------------------------------------------------
    // Command-vector delegation

    @Given("the catalog entry {string}")
    public void theCatalogEntry(final String unit) {
        this.def = byUnit(unit);
    }

    @When("the {string} vector is built for init {string}")
    public void buildVector(final String op, final String initName) {
        this.vector = ServerService.lifecycleCommand(
                def, InitSystem.valueOf(initName), Operation.valueOf(op));
    }

    @Then("the vector is {string}")
    public void thenVector(final String expectedCsv) {
        assertEquals(List.of(expectedCsv.split(",")), vector);
    }

    @Then("the vector is empty")
    public void thenVectorEmpty() {
        assertTrue(vector.isEmpty());
    }

    @Given("the init system {string}")
    public void theInitSystem(final String initName) {
        this.init = InitSystem.valueOf(initName);
    }

    @When("support for the {string} operation is queried")
    public void querySupport(final String op) {
        this.supported = ServerService.isSupported(init, Operation.valueOf(op));
    }

    @Then("the operation is not supported")
    public void thenNotSupported() {
        assertFalse(supported);
    }

    // ------------------------------------------------------------------
    // Config view

    @When("its config view is resolved")
    public void resolveConfig() {
        this.content = ServerService.readConfig(def);
    }

    @Then("the config is reported {string}")
    public void thenConfigDescribe(final String expected) {
        assertEquals(expected, content.describe());
    }
}
