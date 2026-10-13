/*
 * wonderland-server M3a test fixtures: the concrete, cache-loadable cells the
 * loopback integration world seeds by NAME (the server stores class names as
 * inert data and the client cache reflects them open, so these must live where
 * the single test JVM's classpath reaches -- server test scope, public ctor
 * (CellID, CellCache) exactly as CellCacheBasicImpl.instantiateCell reflects).
 *
 * The client's own CellTestFixtures are package-private to client.cell, hence
 * this server-side twin: an EnvironmentCell concrete subclass (the abstract
 * base cannot be instantiated -- the 2006 server shipped the desktop's concrete
 * environment class as world data, and a test deployment does the same here),
 * and a content cell that carries a plain MovableComponent the way a furniture
 * cell did.
 */
package org.jdesktop.wonderland.server.testcells;

import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.CellCache;
import org.jdesktop.wonderland.client.cell.EnvironmentCell;
import org.jdesktop.wonderland.client.cell.MovableAvatarComponent;
import org.jdesktop.wonderland.client.cell.MovableComponent;
import org.jdesktop.wonderland.client.cell.view.ViewCell;
import org.jdesktop.wonderland.common.cell.CellID;

public final class ServerTestFixtures {

    private ServerTestFixtures() {
    }

    /** Concrete environment root: the cell the cache's EnvironmentCell cast hits. */
    public static class CacheableEnv extends EnvironmentCell {
        public CacheableEnv(CellID cellID, CellCache cellCache) {
            super(cellID, cellCache);
        }
    }

    /**
     * Movable content cell, the M2f fixture's pattern: the component is attached
     * in the ctor, before the channel exists; the session's cellLoaded hook then
     * installs the ChannelComponent ahead of the status walk, and the component's
     * ACTIVE rung registers its receiver on that real channel.
     */
    public static class MovableCell extends Cell {
        public final MovableComponent movable;

        public MovableCell(CellID cellID, CellCache cellCache) {
            super(cellID, cellCache);
            movable = new MovableComponent(this);
            addComponent(movable);
        }
    }

    /**
     * Avatar-capable view cell, the way the original desktop's view cell carried
     * a MovableAvatarComponent: ViewCell's ACTIVE rung looks the component up
     * under the MovableComponent lookup class, so LocalAvatar.localMoveRequest
     * has a live machine to drive -- the full avatar-command chain in one JVM.
     */
    public static class TestViewCell extends ViewCell {
        public final MovableAvatarComponent avatarMovable;

        public TestViewCell(CellID cellID, CellCache cellCache) {
            super(cellID, cellCache);
            avatarMovable = new MovableAvatarComponent(this);
            addComponent(avatarMovable, MovableComponent.class);
        }
    }
}
