package org.jdesktop.wonderland.client.cell;

import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;

/**
 * The renderer contract a {@code Cell} delegates its visual half to: feed it a
 * transform update, drive its status, and read the status back. This is the
 * <em>interface</em> only -- it names no scene-graph type, so it is a headless
 * contract. The Jogamp {@code org.jogamp.java3d} implementation
 * ({@code client/jme} + {@code cellrenderer}) is milestone M2c and is verified
 * with the in-JVM probe, not asserted here.
 */
@ExperimentalAPI
public interface CellRenderer {
   void cellTransformUpdate(CellTransform var1);

   void setStatus(CellStatus var1, boolean var2);

   CellStatus getStatus();
}
