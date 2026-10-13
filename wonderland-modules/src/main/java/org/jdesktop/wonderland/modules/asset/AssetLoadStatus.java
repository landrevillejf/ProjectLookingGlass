/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5c (assetmgr): the load-state vocabulary.
 *
 * The 2006 art loader walked a model through a small state machine (unloaded →
 * downloading the model → downloading textures → loading → done / error). This
 * collapses that to the states the headless pipeline actually reaches -- it
 * resolves and parses everything in one pass -- while keeping the LOADED vs
 * ERROR distinction the loader used to decide whether a cell is drawable. The
 * scene-graph build that a real LOADED state feeds is deferred to M6.
 */
package org.jdesktop.wonderland.modules.asset;

/** Where a model is in the resolve → parse pipeline. */
public enum AssetLoadStatus {
    /** Nothing resolved yet (the initial state before {@code ModelLoader} runs). */
    PENDING,
    /** The deployed model, loader data and Collada all resolved and parsed. */
    LOADED,
    /** A referenced asset was missing or a document failed to parse. */
    ERROR;

    /** Whether the pipeline has finished (either outcome). */
    public boolean isTerminal() {
        return this != PENDING;
    }
}
