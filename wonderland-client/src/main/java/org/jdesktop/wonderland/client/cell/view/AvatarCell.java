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
 *     scene-graph stack that was never bundled and is REBUILT on lg3d-core's
 *     Jogamp bridge at M6. Headless there is no renderer, so this returns null
 *     (the base Cell "no renderer" contract), and because no renderer is ever
 *     registered here the trigger* dispatches stay inert: the guards hold, the
 *     behaviour the cell layer needs (server replies reaching the AvatarCell)
 *     is exercised by the MovableAvatarComponent tests, and the visual payload
 *     is verified at M6 with the in-JVM probe -- not claimed here.
 */
package org.jdesktop.wonderland.client.cell.view;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import org.jdesktop.wonderland.client.cell.Cell;
import org.jdesktop.wonderland.client.cell.CellCache;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.state.CellClientState;

public class AvatarCell extends ViewCell {

    private boolean selectedForInput = false;

    public AvatarCell(CellID cellID, CellCache cellCache) {
        super(cellID, cellCache);
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
        // scene-graph avatar renderer rebuilt at M6; headless => none
        return null;
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
