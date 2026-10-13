/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.comms),
 * seam-reduced (docs/wonderland-migration.md, "rebuild the unrecoverable").
 *
 * The 2006 class extended WonderlandSessionImpl -- the SGS SimpleClient login
 * machine -- and owned a LocalAvatar (the scene-graph body). Neither survives
 * headless: the login handshake is REBUILT in M3 on the Spring session, and
 * LocalAvatar/ViewCell land with the renderer milestone (M2f). What this class
 * genuinely DID for the cell layer is pure wiring, ported verbatim:
 *
 *   create the three cell connections + the cache, then cross-link them the
 *   way the 2006 createCellCache() did -- cacheConnection hears hierarchy
 *   messages and drives the cache (which implements that listener), the
 *   channel connection watches cache load/unload for delivery, the cache
 *   exposes the channel connection to every ChannelComponent a load creates,
 *   and the edit connection is the cache's session-level MessageSink.
 *
 * M2f lands the two members the cell/view layer reads off the session and that
 * the M2e slice had to omit while the avatar types were still deferred:
 *   - the session owns its LocalAvatar the way 2006 did (new LocalAvatar(this),
 *     handed to CellCacheConnection as the ClientView); getLocalAvatar() exposes
 *     it, and ViewCell drives viewCellConfigured through it;
 *   - getID() delegates to the WonderlandSession supplied to connect() -- in
 *     2006 CellClientSession *was* the session (it extended WonderlandSessionImpl
 *     which minted the id); headless the transport is injected, so the id is
 *     read through it, null until a session is connected (MovableComponent treats
 *     a null self-id as "not me", matching the original's null-sender path);
 *   - the cache is told who owns it (setSession(this)) so CellCache.getSession()
 *     -- which ViewCell and MovableComponent call -- resolves back here.
 *
 * The one deliberate divergence, because the 2006 component machinery is
 * scene-graph-adjacent: the original got each cell's channel from the
 * client-state's default component classes. The headless cache emits
 * cellLoaded BEFORE the status walk (the M2d ordering), so this session
 * installs the real ChannelComponent right there -- before the walk to ACTIVE
 * registers the cell's receivers on it. Same end state, no SGS component bag.
 */
package org.jdesktop.wonderland.client.comms;

import java.math.BigInteger;
import java.util.Properties;
import org.jdesktop.wonderland.client.cell.ChannelComponent;
import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.CellCache;
import org.jdesktop.wonderland.client.cell.CellCacheBasicImpl;
import org.jdesktop.wonderland.client.cell.CellCacheConnection;
import org.jdesktop.wonderland.client.cell.CellChannelConnection;
import org.jdesktop.wonderland.client.cell.CellEditChannelConnection;
import org.jdesktop.wonderland.client.cell.view.LocalAvatar;
import org.jdesktop.wonderland.common.InternalAPI;
import org.jdesktop.wonderland.common.cell.CellID;

@InternalAPI
public class CellClientSession {

    private final CellCacheConnection cellCacheConnection;
    private final LocalAvatar localAvatar;
    private final CellChannelConnection cellChannelConnection;
    private final CellEditChannelConnection cellEditChannelConnection;
    private final CellCache cellCache;
    private final Properties cellCacheProps = new Properties();
    private final Properties cellChannelProps = new Properties();
    private final Properties cellEditProps = new Properties();
    private WonderlandSession session;

    public CellClientSession() {
        this(null);
    }

    public CellClientSession(ClassLoader loader) {
        // the original created the avatar first and handed it to the cache
        // connection as the ClientView; the same object plays both roles here
        this.localAvatar = new LocalAvatar(this);
        this.cellCacheConnection = new CellCacheConnection(this.localAvatar);
        this.cellChannelConnection = new CellChannelConnection();
        this.cellEditChannelConnection = new CellEditChannelConnection();
        this.cellCache = createCellCache(loader);
    }

    public LocalAvatar getLocalAvatar() {
        return localAvatar;
    }

    /** 2006 getID() (from WonderlandSessionImpl); headless it delegates to the
     *  connected transport, null until connect() supplies one. */
    public BigInteger getID() {
        return session == null ? null : session.getID();
    }

    protected CellCache createCellCache(ClassLoader loader) {
        CellCacheBasicImpl cacheImpl = new CellCacheBasicImpl(loader);
        cacheImpl.setSession(this);
        cacheImpl.setMessageSink(cellEditChannelConnection);
        cacheImpl.setCellChannelConnection(cellChannelConnection);
        cellChannelConnection.setCellCache(cacheImpl);
        cellCacheConnection.addListener(cacheImpl);
        // the M2e channel installation hook, see header (fires before the
        // status walk, so the ACTIVE rung finds a real channel to register on)
        cacheImpl.addCellCacheListener(new CellCache.CellCacheListener() {
            @Override
            public void cellLoaded(CellID cellId, Cell cell) {
                cell.setMessageChannel(new ChannelComponent(cell));
            }

            @Override
            public void cellLoadFailed(CellID cellId, String className,
                    CellID parentCellID, Throwable cause) {
                // nothing to un-wire: the cell never made it into the tree
            }

            @Override
            public void cellUnloaded(CellID cellId, Cell cell) {
                // the channel dies with the cell's component teardown (M2c)
            }
        });
        return cacheImpl;
    }

    public CellCache getCellCache() {
        return cellCache;
    }

    public CellCacheConnection getCellCacheConnection() {
        return cellCacheConnection;
    }

    public Properties getCellCacheProperties() {
        return cellCacheProps;
    }

    public CellChannelConnection getCellChannelConnection() {
        return cellChannelConnection;
    }

    public Properties getCellChannelProperties() {
        return cellChannelProps;
    }

    public CellEditChannelConnection getCellEditChannelConnection() {
        return cellEditChannelConnection;
    }

    public Properties getCellEditProperties() {
        return cellEditProps;
    }

    /**
     * The 2006 login() connected the three connections in channel-cache-edit
     * order against the SGS session; headless, the caller supplies the
     * (loopback today, Spring in M3) session and the same order is kept.
     */
    public void connect(WonderlandSession session) throws ConnectionFailureException {
        this.session = session;
        cellChannelConnection.connect(session, cellChannelProps);
        cellCacheConnection.connect(session, cellCacheProps);
        cellEditChannelConnection.connect(session, cellEditProps);
    }

    public void disconnect() {
        cellEditChannelConnection.disconnect();
        cellCacheConnection.disconnect();
        cellChannelConnection.disconnect();
    }
}
