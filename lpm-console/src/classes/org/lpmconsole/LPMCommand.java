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
package org.lpmconsole;

/**
 * Enumeration of all LPM commands supported by the console.
 * Maps to the canonical command names in /usr/bin/lpm.
 */
public enum LPMCommand {
    LIST("list", false),
    SEARCH("search", false),
    INFO("info", false),
    WHY("why", false),
    INSTALL("install", true),
    REMOVE("remove", true),
    UPDATE("update", true),
    UPGRADE("upgrade", true),
    UPGRADABLE("upgradable", false),
    REINSTALL("reinstall", true),
    AUTOREMOVE("autoremove", true),
    HOLD("hold", true),
    UNHOLD("unhold", true),
    HOLDS("holds", false),
    HISTORY("history", false),
    VERIFY("verify", false),
    UPDATE_DB("update-db", true),
    CLEAN("clean", true),
    LIST_PROFILES("list-profiles", false),
    ADD_PROFILE("add-profile", true),
    KERNEL_DEPS("kernel-deps", false),
    REBUILD_KERNEL("rebuild-kernel", true),
    BUILD("build", true),
    VERSION("version", false),
    HELP("help", false);

    private final String commandName;
    private final boolean mutating;

    LPMCommand(String commandName, boolean mutating) {
        this.commandName = commandName;
        this.mutating = mutating;
    }

    public String getCommandName() {
        return commandName;
    }

    public boolean isMutating() {
        return mutating;
    }

    public boolean requiresConfirmation() {
        return mutating && (this == REMOVE || this == AUTOREMOVE || 
                           this == UPGRADE || this == REBUILD_KERNEL);
    }
}
