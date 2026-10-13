/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4 (text-chat): the module's WonderlandPlugin provider.
 *
 * Registered through META-INF/services and discovered by WonderlandPluginLoader.
 * Its module identity reuses the wonderland-common ModuleInfo metadata. On
 * initialize() it stands up the headless conversation; the Swing conversation
 * window the 2006 module attached here is deferred to M6/M7 and is NOT claimed.
 */
package org.jdesktop.wonderland.modules.chat;

import org.jdesktop.wonderland.common.modules.ModuleInfo;
import org.jdesktop.wonderland.modules.api.PluginContext;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;

/** The text-chat feature module. */
public final class ChatPlugin implements WonderlandPlugin {

    /** The module name this plugin is registered under. */
    public static final String MODULE_NAME = "text-chat";

    private final ModuleInfo info =
            new ModuleInfo(MODULE_NAME, 1, 0, 0, "Text chat between session participants");

    private ChatConversation conversation;

    @Override
    public ModuleInfo getModuleInfo() {
        return info;
    }

    @Override
    public void initialize(PluginContext context) {
        conversation = new ChatConversation();
    }

    @Override
    public void dispose() {
        conversation = null;
    }

    /** The live conversation, or {@code null} before initialize()/after dispose(). */
    public ChatConversation getConversation() {
        return conversation;
    }
}
