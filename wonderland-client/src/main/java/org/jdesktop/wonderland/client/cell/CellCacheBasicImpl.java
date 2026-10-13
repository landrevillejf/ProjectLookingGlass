/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell).
 *
 * The default CellCache: the process-wide CellID -> Cell registry the server's
 * hierarchy messages drive. loadCell reflects a Cell open from its class name,
 * links it into the tree, applies its bounds/transform/client-state and walks it
 * up to the right status; configureCell / unloadCell / changeParent mutate the
 * live tree; every transition is stepped one CellStatus ordinal at a time
 * through setCellStatus (so components/renderers see INACTIVE before ACTIVE),
 * exactly as the Cell base class expects.
 *
 * M2d seam notes (docs/wonderland-migration.md, "rebuild the unrecoverable"):
 *   - The 2006 constructor took (WonderlandSession, ClassLoader, connection x2)
 *     and called ClientContext.registerCellCache(). None of that is headless:
 *     ClientContext lands with the login/renderer milestone, so the cache is
 *     built from just a ClassLoader (defaults to its own loader -- the same M2c
 *     classloader seam) and reaches the network through two seams the M2e
 *     connection layer now makes real: a MessageSink (the CellEditChannel-
 *     Connection) for the single session-level send -- a CellReparentMessage
 *     when a load names a parent the cache does not (yet) hold -- and the
 *     CellChannelConnection accessor the ChannelComponent ctor reads (M2e
 *     restores it; CellClientSession wires both, as the 2006 ctor chain did).
 *     ClientContext registration is deferred (documented, not stubbed).
 *   - The original deferred every status change to a ParallelCellStatusManager --
 *     a cached thread pool that also URL-downloaded AssetPreloader cells before
 *     activating them. Live URL downloads are not testable headless, so this
 *     slice applies status changes synchronously (deterministic, fully covered).
 *     The async/asset-preload manager is an M2e concern.
 *   - setViewCell(ViewCell)/getSession() (the camera cell + its owning session)
 *     were reduced to a boolean "has view" flag while ViewCell and the session
 *     were still deferred; M2f lands ViewCell and CellClientSession, so the real
 *     fields are restored: setViewCell() keeps the original's unconditional
 *     roots-to-VISIBLE walk (it fires even when handed null -- the 2006 code has
 *     no null guard) and getSession() returns the CellClientSession that the
 *     session wires in via setSession().
 * The status ordinal walk is the Cell base class's contract, reused verbatim.
 */
package org.jdesktop.wonderland.client.cell;

import com.jme.bounding.BoundingVolume;
import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.wonderland.client.cell.view.ViewCell;
import org.jdesktop.wonderland.client.comms.CellClientSession;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.MultipleParentException;
import org.jdesktop.wonderland.common.cell.messages.CellReparentMessage;
import org.jdesktop.wonderland.common.cell.state.CellClientState;

public class CellCacheBasicImpl implements CellCache,
        CellCacheConnection.CellCacheMessageListener {

    private static final Logger logger = Logger.getLogger(CellCacheBasicImpl.class.getName());

    private final Map<CellID, Cell> cells =
            Collections.synchronizedMap(new HashMap<>());
    private final Set<Cell> rootCells =
            Collections.synchronizedSet(new HashSet<>());
    private final Set<CellCacheListener> listeners = new CopyOnWriteArraySet<>();
    private final CellStatistics stats = new CellStatistics();

    private final ClassLoader classLoader;
    private MessageSink messageSink;
    private CellChannelConnection cellChannelConnection;
    private CellClientSession session;
    private ViewCell viewCell = null;

    private static final ThreadLocal<Cell> currentCell = new ThreadLocal<>();

    public CellCacheBasicImpl() {
        this(CellCacheBasicImpl.class.getClassLoader());
    }

    public CellCacheBasicImpl(ClassLoader classLoader) {
        this.classLoader = classLoader == null
                ? CellCacheBasicImpl.class.getClassLoader() : classLoader;
        logger.warning("Create cell cache");
    }

    /** M3 wires the real session sink here; null keeps the reparent send local. */
    public void setMessageSink(MessageSink messageSink) {
        this.messageSink = messageSink;
    }

    /**
     * M2e: the 2006 ctor chain ran (cellCacheConnection.addListener(cache),
     * cellChannelConnection.setCellCache(cache)) from CellClientSession;
     * this setter is the second half of that wiring and returns nothing, it
     * only stores -- the caller feeds the cache to the connection itself, so
     * construction order stays with the session that owns both.
     */
    public void setCellChannelConnection(CellChannelConnection cellChannelConnection) {
        this.cellChannelConnection = cellChannelConnection;
    }

    @Override
    public CellChannelConnection getCellChannelConnection() {
        return cellChannelConnection;
    }

    /** M2f: the session that owns this cache; the 2006 ctor passed it in. */
    public void setSession(CellClientSession session) {
        this.session = session;
    }

    @Override
    public CellClientSession getSession() {
        return session;
    }

    /**
     * M2f restores the original setViewCell: store the camera cell, then walk
     * every parentless cell up to VISIBLE -- unconditionally, exactly as the
     * 2006 code does (there is no null guard: setting the view to null still
     * makes the loaded tree visible, the quirk the M2f tests pin).
     */
    @Override
    public void setViewCell(ViewCell viewCell) {
        this.viewCell = viewCell;
        synchronized (cells) {
            for (Cell cell : cells.values()) {
                if (cell.getParent() == null) {
                    changeCellTreeStatus(cell, CellStatus.VISIBLE);
                }
            }
        }
    }

    @Override
    public ViewCell getViewCell() {
        return viewCell;
    }

    @Override
    public Cell getCell(CellID cellId) {
        return cells.get(cellId);
    }

    @Override
    public Cell[] getCells() {
        synchronized (cells) {
            return cells.values().toArray(new Cell[cells.size()]);
        }
    }

    @Override
    public EnvironmentCell getEnvironmentCell() {
        return (EnvironmentCell) getCell(CellID.getEnvironmentCellID());
    }

    @Override
    public Cell loadCell(CellID cellId, String className, BoundingVolume localBounds,
            CellID parentCellID, CellTransform cellTransform, CellClientState setup,
            String cellName) {
        long startTime = System.currentTimeMillis();
        try {
            Cell cell;
            synchronized (cells) {
                if (cells.containsKey(cellId)) {
                    logger.severe("Attempt to create cell that already exists " + cellId);
                    return null;
                }
                logger.info("creating cell " + className + " " + cellId);
                cell = instantiateCell(className, cellId);
                if (cell == null) {
                    return null;
                }
                cells.put(cellId, cell);
            }

            currentCell.set(cell);
            cell.setName(cellName);

            Cell parent = cells.get(parentCellID);
            if (parent != null) {
                try {
                    parent.addChild(cell);
                } catch (MultipleParentException ex) {
                    logger.log(Level.SEVERE, "Failed to load cell", ex);
                }
            } else if (parentCellID != null) {
                logger.warning("Failed to find parent " + parentCellID + " for child " + cellId);
                if (messageSink != null) {
                    messageSink.send(new CellReparentMessage(cell.getCellID(),
                            parentCellID, cell.getLocalTransform()));
                }
                return null;
            }

            cell.setLocalBounds(localBounds);
            cell.setLocalTransform(cellTransform, TransformChangeListener.ChangeSource.SERVER_ADJUST);

            if (parent == null && !cellId.equals(CellID.getEnvironmentCellID())) {
                rootCells.add(cell);
            }

            if (setup != null) {
                cell.setClientState(setup);
            } else {
                logger.warning("Cell has null setup " + className + "  " + cell);
            }

            fireCellLoaded(cell);
            if (viewCell != null) {
                changeCellStatus(cell, CellStatus.VISIBLE);
            } else if (cell instanceof ViewCell
                    || cellId.equals(CellID.getEnvironmentCellID())) {
                changeCellStatus(cell, CellStatus.ACTIVE);
            }

            long time = System.currentTimeMillis() - startTime;
            CellStatistics.TimeCellStat loadStat =
                    new CellStatistics.TimeCellStat("LoadTime", "Cell Load Time");
            loadStat.setValue(time);
            stats.add(cell, loadStat);
            return cell;
        } catch (Exception e) {
            fireCellLoadFailed(cellId, className, parentCellID, e);
            logger.log(Level.SEVERE, "Failed to loadCell", e);
            return null;
        } finally {
            currentCell.remove();
        }
    }

    @Override
    public void configureCell(CellID cellID, CellClientState clientState, String cellName) {
        Cell cell = cells.get(cellID);
        if (cell == null) {
            logger.warning("Received a CONFIGURE_CELL message to a non-existent cell with id "
                    + cellID);
            return;
        }
        try {
            currentCell.set(cell);
            if (cellName != null && !cellName.equals(cell.getName())) {
                cell.setName(cellName);
            }
            if (clientState != null) {
                cell.setClientState(clientState);
            }
        } finally {
            currentCell.remove();
        }
    }

    @Override
    public void unloadCell(CellID cellId) {
        Cell cell = cells.remove(cellId);
        if (cell == null) {
            logger.log(Level.WARNING, "Unloading unknown cell " + cellId);
            return;
        }
        logger.fine("UNLOADING CELL " + cell.getName());
        try {
            currentCell.set(cell);
            fireCellUnloaded(cell);
            changeCellStatus(cell, CellStatus.DISK);
            if (cell.getParent() == null) {
                rootCells.remove(cell);
            } else {
                cell.getParent().removeChild(cell);
            }
        } finally {
            currentCell.remove();
        }
    }

    @Override
    public void deleteCell(CellID cellId) {
        unloadCell(cellId);
    }

    @Override
    public void changeParent(CellID cellID, CellID parentCellID, CellTransform cellTransform) {
        Cell cell = cells.get(cellID);
        if (cell == null) {
            logger.warning("Unable to find Cell in Cache with ID " + cellID);
            return;
        }
        try {
            currentCell.set(cell);
            Cell parentCell = cell.getParent();
            if (parentCell != null) {
                parentCell.removeChild(cell);
            } else {
                rootCells.remove(cell);
            }

            Cell newParentCell = cells.get(parentCellID);
            if (newParentCell != null) {
                try {
                    newParentCell.addChild(cell);
                } catch (MultipleParentException excp) {
                    logger.log(Level.WARNING, "Multiple parents are set for Cell with ID "
                            + cellID, excp);
                }
            } else if (!cellID.equals(CellID.getEnvironmentCellID())) {
                rootCells.add(cell);
            }

            cell.setLocalTransform(cellTransform, TransformChangeListener.ChangeSource.REMOTE);
        } finally {
            currentCell.remove();
        }
    }

    @Override
    public Collection<Cell> getRootCells() {
        return new LinkedList<>(rootCells);
    }

    @Override
    public void addCellCacheListener(CellCacheListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeCellCacheListener(CellCacheListener listener) {
        listeners.remove(listener);
    }

    @Override
    public CellStatistics getStatistics() {
        return stats;
    }

    public static Cell getCurrentActiveCell() {
        return currentCell.get();
    }

    protected void fireCellLoaded(Cell cell) {
        for (CellCacheListener listener : listeners) {
            listener.cellLoaded(cell.getCellID(), cell);
        }
    }

    protected void fireCellLoadFailed(CellID cellID, String className, CellID parentCellID,
            Throwable cause) {
        for (CellCacheListener listener : listeners) {
            listener.cellLoadFailed(cellID, className, parentCellID, cause);
        }
    }

    protected void fireCellUnloaded(Cell cell) {
        for (CellCacheListener listener : listeners) {
            listener.cellUnloaded(cell.getCellID(), cell);
        }
    }

    private Cell instantiateCell(String className, CellID cellId) {
        try {
            Class<?> clazz = Class.forName(className, true, classLoader);
            Constructor<? extends Cell> constructor =
                    clazz.asSubclass(Cell.class).getConstructor(CellID.class, CellCache.class);
            return constructor.newInstance(cellId, this);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Problem instantiating cell " + className, e);
            return null;
        }
    }

    private void changeCellTreeStatus(Cell root, CellStatus status) {
        if (root.getStatus().ordinal() < status.ordinal()) {
            changeCellStatus(root, status);
        }
        for (Cell child : root.getChildren()) {
            changeCellTreeStatus(child, status);
        }
    }

    /** Synchronous stand-in for the 2006 status manager (see header on M2e). */
    private void changeCellStatus(Cell cell, CellStatus status) {
        setCellStatus(cell, status);
    }

    private void setCellStatus(Cell cell, CellStatus status) {
        synchronized (cell) {
            int currentStatus = cell.getStatus().ordinal();
            int requiredStatus = status.ordinal();
            if (currentStatus == requiredStatus) {
                return;
            }
            int dir = requiredStatus > currentStatus ? 1 : -1;
            boolean increasing = dir == 1;
            while (currentStatus != requiredStatus) {
                currentStatus += dir;
                CellStatus nextStatus = CellStatus.values()[currentStatus];
                long startTime = System.currentTimeMillis();
                CellStatistics.TimeCellStat loadStat = getLoadStat(cell, nextStatus);
                cell.setStatus(nextStatus, increasing);
                loadStat.changeValue(System.currentTimeMillis() - startTime);
                cell.fireCellStatusChanged(nextStatus);
            }
        }
    }

    private CellStatistics.TimeCellStat getLoadStat(Cell cell, CellStatus status) {
        String statId = status.name() + "-time";
        synchronized (stats) {
            CellStatistics.CellStat existing = stats.get(cell, statId);
            if (existing instanceof CellStatistics.TimeCellStat) {
                return (CellStatistics.TimeCellStat) existing;
            }
            CellStatistics.TimeCellStat loadStat = new CellStatistics.TimeCellStat(statId);
            stats.add(cell, loadStat);
            return loadStat;
        }
    }
}
