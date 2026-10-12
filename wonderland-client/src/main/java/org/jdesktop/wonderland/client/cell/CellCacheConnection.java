/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell),
 * verbatim (docs/wonderland-migration.md: this class had no SGS reference in
 * its message path -- the only seam is which session it registers the view
 * against, and that is the M2e WonderlandSession interface).
 *
 * The server's cell-hierarchy protocol lands here: every CellHierarchyMessage
 * (and MessageList batch of them) is decoded to one of the five
 * CellCacheMessageListener operations, which the CellCache implements directly
 * -- the nested interface's signatures are the cache's own vocabulary, which
 * is why M2d could port the registry against exactly this contract.
 *
 * Faithful 2006 quirks kept:
 *   - the DELETE_CELL case FALLS THROUGH to CHANGE_PARENT (whose body is
 *     empty), so a delete also triggers nothing else; kept verbatim because
 *     the CHANGE_PARENT wire path arrives with the editor milestone and a
 *     "fix" would silently change what the server queue means;
 *   - an unrecognized non-hierarchy message THROWS RuntimeException -- the
 *     cache connection only ever speaks hierarchy, so a routing bug upstream
 *     is a hard failure, not a log line;
 *   - connect() registers the view by sendAndWait(newSetAvatarMessage(viewID))
 *     and hands the ViewCreateResponseMessage to the ClientView, storing the
 *     view cell id -- all of which runs on the loopback session today.
 */
package org.jdesktop.wonderland.client.cell;

import com.jme.bounding.BoundingVolume;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.wonderland.client.cell.view.ClientView;
import org.jdesktop.wonderland.client.comms.BaseConnection;
import org.jdesktop.wonderland.client.comms.ConnectionFailureException;
import org.jdesktop.wonderland.client.comms.WonderlandSession;
import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.cell.CellCacheConnectionType;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.messages.CellHierarchyMessage;
import org.jdesktop.wonderland.common.cell.messages.ViewCreateResponseMessage;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.jdesktop.wonderland.common.comms.ConnectionType;
import org.jdesktop.wonderland.common.messages.Message;
import org.jdesktop.wonderland.common.messages.MessageList;

@ExperimentalAPI
public class CellCacheConnection extends BaseConnection {

    private static final Logger logger = Logger.getLogger(CellCacheConnection.class.getName());

    private final List<CellCacheMessageListener> listeners = new ArrayList<>();
    private final ClientView clientView;
    private CellID viewCellID = null;

    public CellCacheConnection(ClientView clientView) {
        this.clientView = clientView;
    }

    @Override
    public ConnectionType getConnectionType() {
        return CellCacheConnectionType.CLIENT_TYPE;
    }

    public void addListener(CellCacheMessageListener listener) {
        listeners.add(listener);
    }

    private ViewCreateResponseMessage registerView(String viewID) {
        try {
            return (ViewCreateResponseMessage) sendAndWait(
                    CellHierarchyMessage.newSetAvatarMessage(viewID));
        } catch (InterruptedException ex) {
            Logger.getLogger(CellCacheConnection.class.getName())
                    .log(Level.SEVERE, null, ex);
            return null;
        }
    }

    @Override
    public void handleMessage(Message message) {
        if (message instanceof MessageList) {
            // common's MessageList.getMessages() is a raw List (verbatim M1
            // port), so each element is read back as Message explicitly
            for (Object m : ((MessageList) message).getMessages()) {
                handleMessage((Message) m);
            }
            return;
        }
        if (!(message instanceof CellHierarchyMessage)) {
            throw new RuntimeException("Unexpected message type "
                    + message.getClass().getName());
        }
        CellHierarchyMessage msg = (CellHierarchyMessage) message;
        switch (msg.getActionType()) {
            case LOAD_CELL:
                for (CellCacheMessageListener l : listeners) {
                    l.loadCell(msg.getCellID(), msg.getCellClassName(), msg.getLocalBounds(),
                            msg.getParentID(), msg.getCellTransform(), msg.getSetupData(),
                            msg.getCellName());
                }
                break;
            case CONFIGURE_CELL:
                for (CellCacheMessageListener l : listeners) {
                    l.configureCell(msg.getCellID(), msg.getSetupData(), msg.getCellName());
                }
                break;
            case UNLOAD_CELL:
                for (CellCacheMessageListener l : listeners) {
                    l.unloadCell(msg.getCellID());
                }
                break;
            case DELETE_CELL:
                for (CellCacheMessageListener l : listeners) {
                    l.deleteCell(msg.getCellID());
                }
                // falls through to CHANGE_PARENT in the 2006 bytecode; kept
                // verbatim -- the CHANGE_PARENT body is empty there too
            case CHANGE_PARENT:
                break;
            default:
                logger.warning("Message type not implemented " + msg.getActionType());
        }
    }

    @Override
    public void connect(WonderlandSession session, Properties props)
            throws ConnectionFailureException {
        super.connect(session, props);
        ViewCreateResponseMessage msg = registerView(clientView.getViewID());
        clientView.serverInitialized(msg);
        viewCellID = msg.getViewCellID();
    }

    public CellID getViewCellID() {
        return viewCellID;
    }

    @Override
    public void disconnected() {
        listeners.clear();
    }

    @ExperimentalAPI
    public interface CellCacheMessageListener {

        Cell loadCell(CellID cellId, String className, BoundingVolume localBounds,
                CellID parentCellID, CellTransform cellTransform, CellClientState setup,
                String cellName);

        void configureCell(CellID cellId, CellClientState clientState, String cellName);

        void unloadCell(CellID cellId);

        void deleteCell(CellID cellId);

        void changeParent(CellID cellId, CellID parentCellID, CellTransform cellTransform);
    }
}
