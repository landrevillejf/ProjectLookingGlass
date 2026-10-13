/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4: the five shipped feature-module providers, each on its
 * initialize / dispose lifecycle and its module identity. The presentation each
 * 2006 module attached is deferred (see the class javadocs); these tests pin the
 * headless contract the loader relies on: a stable module name and a live model
 * between initialize() and dispose().
 */
package org.jdesktop.wonderland.modules;

import org.jdesktop.wonderland.modules.api.PluginContext;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FeatureModulePluginsTest {

    private static final PluginContext EMPTY_CONTEXT =
            new PluginContext(java.util.Map.of());

    @Test
    void chatStandsUpAndTearsDownItsConversation() {
        WonderlandPlugin plugin = new org.jdesktop.wonderland.modules.chat.ChatPlugin();
        assertThat(plugin.getModuleInfo().getName()).isEqualTo("text-chat");

        var chat = (org.jdesktop.wonderland.modules.chat.ChatPlugin) plugin;
        assertThat(chat.getConversation()).isNull();
        plugin.initialize(EMPTY_CONTEXT);
        assertThat(chat.getConversation()).isNotNull();
        chat.getConversation().post("alice", "hi");
        plugin.dispose();
        assertThat(chat.getConversation()).isNull();
    }

    @Test
    void imageviewerStandsUpAndTearsDownItsModel() {
        var plugin = new org.jdesktop.wonderland.modules.imageviewer.ImageViewerPlugin();
        assertThat(plugin.getModuleInfo().getName()).isEqualTo("imageviewer");
        assertThat(plugin.getModel()).isNull();
        plugin.initialize(EMPTY_CONTEXT);
        assertThat(plugin.getModel()).isNotNull();
        plugin.dispose();
        assertThat(plugin.getModel()).isNull();
    }

    @Test
    void userListStandsUpAndTearsDownItsRoster() {
        var plugin = new org.jdesktop.wonderland.modules.userlist.UserListPlugin();
        assertThat(plugin.getModuleInfo().getName()).isEqualTo("user-list");
        assertThat(plugin.getUserList()).isNull();
        plugin.initialize(EMPTY_CONTEXT);
        assertThat(plugin.getUserList()).isNotNull();
        plugin.dispose();
        assertThat(plugin.getUserList()).isNull();
    }

    @Test
    void stickyNoteIsReadyAndFactoriesNotes() {
        var plugin = new org.jdesktop.wonderland.modules.stickynote.StickyNotePlugin();
        assertThat(plugin.getModuleInfo().getName()).isEqualTo("stickynote");
        assertThat(plugin.isReady()).isFalse();
        plugin.initialize(EMPTY_CONTEXT);
        assertThat(plugin.isReady()).isTrue();
        assertThat(plugin.newNote(null, null, null).isEmpty()).isTrue();
        plugin.dispose();
        assertThat(plugin.isReady()).isFalse();
    }

    @Test
    void whiteboardStandsUpAndTearsDownItsSurface() {
        var plugin = new org.jdesktop.wonderland.modules.whiteboard.WhiteboardPlugin();
        assertThat(plugin.getModuleInfo().getName()).isEqualTo("whiteboard");
        assertThat(plugin.getModel()).isNull();
        plugin.initialize(EMPTY_CONTEXT);
        assertThat(plugin.getModel()).isNotNull();
        plugin.dispose();
        assertThat(plugin.getModel()).isNull();
    }
}
