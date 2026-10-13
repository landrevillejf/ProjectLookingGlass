/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4 (stickynote): the module's WonderlandPlugin provider.
 *
 * The headless half of the sticky-note feature module; it exposes a factory for
 * note content. The note cell renderer (the painted paper quad) is deferred to
 * M6.
 */
package org.jdesktop.wonderland.modules.stickynote;

import org.jdesktop.wonderland.common.modules.ModuleInfo;
import org.jdesktop.wonderland.modules.api.PluginContext;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;

/** The sticky-note feature module. */
public final class StickyNotePlugin implements WonderlandPlugin {

    /** The module name this plugin is registered under. */
    public static final String MODULE_NAME = "stickynote";

    private final ModuleInfo info = new ModuleInfo(
            MODULE_NAME, 1, 0, 0, "Attach a short text note to a cell");

    private boolean ready;

    @Override
    public ModuleInfo getModuleInfo() {
        return info;
    }

    @Override
    public void initialize(PluginContext context) {
        ready = true;
    }

    @Override
    public void dispose() {
        ready = false;
    }

    /** Create note content (a blank yellow note when every field is null). */
    public StickyNote newNote(String text, String author, StickyNote.Color color) {
        return new StickyNote(text, author, color);
    }

    /** Whether the module is initialised. */
    public boolean isReady() {
        return ready;
    }
}
