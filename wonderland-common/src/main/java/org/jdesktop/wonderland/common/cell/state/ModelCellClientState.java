/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland M3b / Stage 2: the model cell's CLIENT state, and the wire slot
 * for its art.
 *
 * The 2006 model-jme module held a ModelCellClientState carrying the deployed
 * model's wla:// reference; the port had the class as an empty name-only stub,
 * so the URL lived only on the server (WorldCell.getDeployedModelURL(), exposed
 * over HTTP by /api/world/asset). Adding the field here is the deliberate
 * shared-wire-format step: a CellHierarchyMessage LOAD_CELL now carries the URL
 * to the client, whose WonderlandModelCell reads it back in setClientState().
 *
 * It is a plain Serializable field on the existing CellClientState, so it rides
 * the already-proven codec (CellMessagesCodecTest round-trips this type). An
 * explicit serialVersionUID freezes the shape: both ends co-build from this
 * repo, so a future field addition is a conscious wire change, not a silent
 * deserialization break.
 */
package org.jdesktop.wonderland.common.cell.state;

public class ModelCellClientState extends CellClientState {

    private static final long serialVersionUID = 1L;

    /** The deployed model's {@code wla://} reference, or null when unresolved. */
    private String deployedModelURL;

    public String getDeployedModelURL() {
        return deployedModelURL;
    }

    public void setDeployedModelURL(String deployedModelURL) {
        this.deployedModelURL = deployedModelURL;
    }
}
