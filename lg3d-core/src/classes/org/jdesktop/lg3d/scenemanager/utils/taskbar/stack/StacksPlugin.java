/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.scenemanager.utils.taskbar.stack;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.lg3d.scenemanager.utils.SceneControl;
import org.jdesktop.lg3d.scenemanager.utils.plugin.SceneManagerPlugin;
import org.jdesktop.lg3d.scenemanager.utils.taskbar.TaskbarItemConfig;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Tapp;
import org.jdesktop.lg3d.wg.event.LgEventConnector;

/**
 * Puts the Documents and Downloads folder stacks on the right side of the
 * taskbar, immediately before the Exit icon.
 *
 * <p>Each stack is posted as a {@link TaskbarItemConfig} with a negative item
 * index: Documents is {@code -4} and Downloads is {@code -3}, so with the
 * background icon at {@code -2} and Exit at {@code -1} the right-hand group
 * reads {@code [Documents] [Downloads] [Background] [Exit]}.</p>
 *
 * <p>Registered from {@code glassy.lgcfg}. The stacks read
 * {@code ~/Documents} and {@code ~/Downloads}; a missing folder simply shows an
 * empty stack.</p>
 */
public class StacksPlugin implements SceneManagerPlugin {
    private static final Logger logger = Logger.getLogger("lg.scenemanager");

    /** Taskbar indices (negative = counted from the right). */
    private static final int INDEX_DOCUMENTS = -4;
    private static final int INDEX_DOWNLOADS = -3;

    /** Fallback icon if a folder glyph is somehow missing from the resources. */
    private static final String FALLBACK_ICON = "resources/images/icon/star.png";

    /** The stacks this plugin posted, in creation order. */
    private final List<FolderStack> stacks =
            Collections.synchronizedList(new ArrayList<FolderStack>());

    public StacksPlugin() {
    }

    /** The folder stacks this plugin posted to the taskbar. */
    public List<FolderStack> getStacks() {
        return stacks;
    }

    @Override
    public void initialize(SceneControl sceneControl) {
        String home = System.getProperty("user.home");
        postStack("Documents", Paths.get(home, "Documents"),
                "resources/images/icon/folder-documents.png", INDEX_DOCUMENTS);
        postStack("Downloads", Paths.get(home, "Downloads"),
                "resources/images/icon/folder-downloads.png", INDEX_DOWNLOADS);
    }

    private void postStack(final String name, final Path dir,
                           final String iconResource, final int index) {
        final URL iconUrl = resolveIcon(iconResource);
        if (iconUrl == null) {
            logger.log(Level.WARNING,
                    "No icon for stack {0}; not posting it", name);
            return;
        }
        LgEventConnector.getLgEventConnector().postEvent(
            new TaskbarItemConfig() {
                @Override
                public Tapp createItem() {
                    FolderStack stack = new FolderStack(dir, name, iconUrl);
                    stacks.add(stack);
                    return stack;
                }
                @Override
                public int getItemIndex() {
                    return index;
                }
            }, null);
    }

    private URL resolveIcon(String resource) {
        ClassLoader cl = getClass().getClassLoader();
        URL url = cl.getResource(resource);
        if (url == null) {
            url = cl.getResource(FALLBACK_ICON);
        }
        return url;
    }

    @Override
    public void destroy() {
        // The taskbar owns the posted items; nothing to clean up here.
    }

    @Override
    public boolean isRemovable() {
        return true;
    }

    @Override
    public Component3D getPluginRoot() {
        return null;
    }
}
