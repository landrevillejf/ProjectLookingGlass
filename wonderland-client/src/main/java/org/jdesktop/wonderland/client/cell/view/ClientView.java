package org.jdesktop.wonderland.client.cell.view;

import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.messages.ViewCreateResponseMessage;

/**
 * Client-side handle on a server view. The three hooks are the whole view
 * lifecycle the cell layer drives: an identity, the server's reply to the
 * create request, and the per-cell configuration that follows. Depends only on
 * the {@code wonderland-common} wire types (already round-trip tested in M1),
 * so it is a headless contract -- the Java 3D view implementation arrives with
 * the {@code Cell} base class in M2c.
 */
public interface ClientView {
   String getViewID();

   void serverInitialized(ViewCreateResponseMessage var1);

   void viewCellConfigured(CellID var1);
}
