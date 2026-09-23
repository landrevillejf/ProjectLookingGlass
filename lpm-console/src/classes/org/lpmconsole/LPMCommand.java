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
