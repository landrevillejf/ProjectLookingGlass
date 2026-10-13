/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell.view),
 * headless half. AvatarCell is the view cell that carries an avatar body. The
 * three trigger* entry points -- action, collision, goto -- are the message
 * surface MovableAvatarComponent folds the server reply into, and they are
 * ported verbatim: each still guards on the renderer being the scene-graph one
 * and dispatches to a CellRenderer that implements AvatarActionTrigger.
 *
 * Seam for the unrecoverable renderer half (docs/wonderland-migration.md):
 *   - ClientContext.getRendererType() -> Cell.setCurrentRendererType()/
 *     getRendererType(), the M2c static holder already in this tree.
 *   - createCellRenderer() in the original built an AvatarJME through
 *     ClientContextJME / AvatarRenderManager / ViewManager -- the com.jme.scene
 *     scene-graph stack that was never bundled. The M6b slice (this PR) puts
 *     the scene-graph avatar behind an AvatarRendererInstaller slot in this
 *     module: wonderland-renderer installs the live one at desktop boot, and
 *     nothing installed -- the headless-test case and the pre-M6b desktop
 *     -- keeps the M2f quirk that createCellRenderer returns null and the
 *     trigger dispatch guards therefore stay inert. When the live installer
 *     IS registered, the returned renderer implements AvatarActionTrigger
 *     and the action / collision / goto dispatches become effective.
 */
package org.jdesktop.wonderland.client.cell.view;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.CellCache;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.state.AvatarCellServerState;
import org.jdesktop.wonderland.common.cell.state.CellClientState;

public class AvatarCell extends ViewCell {

    private boolean selectedForInput = false;

    /**
     * M6b: the AvatarCellServerState the cell was created from. The port kept
     * this narrow (typed, on AvatarCell only) rather than pushing a generic
     * serverState onto the Cell base -- nothing else in the headless client
     * reads it, and wonderland-renderer's AvatarRendererFactory needs exactly
     * one field: the avatarModelUrl hint. Null is legal (the pre-M6b
     * default); the renderer's load resolver then falls back to the
     * AvatarPool default name.
     */
    private AvatarCellServerState serverState;

    public AvatarCell(CellID cellID, CellCache cellCache) {
        super(cellID, cellCache);
    }

    public AvatarCellServerState getServerState() {
        return this.serverState;
    }

    public void setServerState(AvatarCellServerState serverState) {
        this.serverState = serverState;
    }

    public void triggerAction(int trigger, boolean pressed, String animationName) {
        if (Cell.getRendererType() == RendererType.RENDERER_JME) {
            CellRenderer rend = this.getCellRenderer(RendererType.RENDERER_JME);
            if (rend instanceof AvatarActionTrigger) {
                ((AvatarActionTrigger) rend).trigger(trigger, pressed, animationName);
            }
        }
    }

    public void triggerCollision(float height, boolean collision) {
        if (Cell.getRendererType() == RendererType.RENDERER_JME) {
            CellRenderer rend = this.getCellRenderer(RendererType.RENDERER_JME);
            if (rend instanceof AvatarActionTrigger) {
                ((AvatarActionTrigger) rend).triggerCollision(height, collision);
            }
        }
    }

    public void triggerGoto(Vector3f worldPosition, Quaternion look) {
        if (Cell.getRendererType() == RendererType.RENDERER_JME) {
            CellRenderer rend = this.getCellRenderer(RendererType.RENDERER_JME);
            if (rend instanceof AvatarActionTrigger) {
                ((AvatarActionTrigger) rend).triggerGoto(worldPosition, look);
            }
        }
    }

    @Override
    public void setClientState(CellClientState cellClientState) {
        super.setClientState(cellClientState);
    }

    @Override
    protected CellRenderer createCellRenderer(RendererType rendererType) {
        // M6b: the scene-graph avatar renderer installs itself through
        // AvatarRendererInstallers; a headless test JVM (or a desktop before
        // the renderer bootstrap runs) sees null here, which is exactly the
        // M2f no-renderer quirk the cell layer has always supported.
        AvatarRendererInstaller installer = AvatarRendererInstallers.get();
        return installer == null ? null : installer.create(this, rendererType);
    }

    public boolean isSelectedForInput() {
        return this.selectedForInput;
    }

    public void setSelectedForInput(boolean selectedForInput) {
        this.selectedForInput = selectedForInput;
    }

    public interface AvatarActionTrigger {
        void trigger(int trigger, boolean pressed, String animationName);

        void triggerGoto(Vector3f worldPosition, Quaternion look);

        void triggerCollision(float height, boolean collision);
    }
}
