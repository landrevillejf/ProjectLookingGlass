/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell).
 * Notified by Cell whenever its world or local transform changes; the source
 * distinguishes a local edit from a remote update from a server adjustment so a
 * listener (an avatar constraint, a camera follow) can avoid feedback loops.
 */
package org.jdesktop.wonderland.client.cell;

public interface TransformChangeListener {

    void transformChanged(Cell cell, ChangeSource source);

    enum ChangeSource {
        LOCAL,
        REMOTE,
        SERVER_ADJUST
    }
}
