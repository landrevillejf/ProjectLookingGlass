/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5c (assetmgr): a loaded world's inventory.
 *
 * The result of a {@link WorldLoader} pass over one module: its cells, each with
 * the decoded server-state (name, position, model component) and -- for model
 * cells -- the {@link ModelLoadResult} the asset pipeline produced. This is the
 * data the 2006 server fanned out to a client's cache; feeding it into the live
 * cell cache / scene graph is the deferred M6 desktop integration.
 */
package org.jdesktop.wonderland.modules.asset;

import java.util.List;
import org.jdesktop.wonderland.common.cell.state.CellServerState;

/** A world module's loaded cells and their model results. */
public final class WorldContents {

    private final String module;
    private final List<LoadedCell> cells;

    public WorldContents(String module, List<LoadedCell> cells) {
        this.module = module;
        this.cells = List.copyOf(cells);
    }

    public String module() {
        return module;
    }

    public List<LoadedCell> cells() {
        return cells;
    }

    public int cellCount() {
        return cells.size();
    }

    /** The cells whose model loaded successfully. */
    public long loadedModelCount() {
        return cells.stream().filter(LoadedCell::modelLoaded).count();
    }

    /** The total distinct missing-texture dependencies across the world. */
    public int missingTextureCount() {
        return cells.stream()
                .map(LoadedCell::model)
                .filter(java.util.Objects::nonNull)
                .mapToInt(m -> m.missingTextures().size())
                .sum();
    }

    /**
     * One world cell: its decoded server state and, for a model cell, its load
     * result. {@code path} and {@code parentPath} carry where the cell sat in the
     * module's directory tree, because that is where the world recorded its
     * parent -- see {@link WorldLoader#parentOf} and {@link WorldPlacement}.
     */
    public static final class LoadedCell {
        private final CellServerState state;
        private final ModelLoadResult model;
        private final String path;
        private final String parentPath;

        public LoadedCell(CellServerState state, ModelLoadResult model) {
            this(state, model, null, null);
        }

        public LoadedCell(CellServerState state, ModelLoadResult model,
                String path, String parentPath) {
            this.state = state;
            this.model = model;
            this.path = path;
            this.parentPath = parentPath;
        }

        public CellServerState state() {
            return state;
        }

        /** The module path this cell was read from, or null when it came from nowhere. */
        public String path() {
            return path;
        }

        /** The path of the cell this one sits inside, or null for a world root. */
        public String parentPath() {
            return parentPath;
        }

        /** The model load result, or null for a non-model cell. */
        public ModelLoadResult model() {
            return model;
        }

        public String name() {
            return state == null ? null : state.getName();
        }

        public boolean modelLoaded() {
            return model != null && model.isLoaded();
        }
    }
}
