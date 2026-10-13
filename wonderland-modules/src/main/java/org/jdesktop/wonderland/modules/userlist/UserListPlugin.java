/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4 (user-list): the module's WonderlandPlugin provider.
 *
 * The headless half of the present-user roster. Its list window is deferred to
 * M6/M7.
 */
package org.jdesktop.wonderland.modules.userlist;

import org.jdesktop.wonderland.common.modules.ModuleInfo;
import org.jdesktop.wonderland.modules.api.PluginContext;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;

/** The user-list feature module. */
public final class UserListPlugin implements WonderlandPlugin {

    /** The module name this plugin is registered under. */
    public static final String MODULE_NAME = "user-list";

    private final ModuleInfo info = new ModuleInfo(
            MODULE_NAME, 1, 0, 0, "Show which users are present in the world");

    private UserList userList;

    @Override
    public ModuleInfo getModuleInfo() {
        return info;
    }

    @Override
    public void initialize(PluginContext context) {
        userList = new UserList();
    }

    @Override
    public void dispose() {
        userList = null;
    }

    /** The live roster, or {@code null} before initialize()/after dispose(). */
    public UserList getUserList() {
        return userList;
    }
}
