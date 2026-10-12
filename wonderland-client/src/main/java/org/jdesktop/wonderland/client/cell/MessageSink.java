/*
 * M2d seam (docs/wonderland-migration.md, "rebuild the unrecoverable").
 *
 * The per-cell MessageChannel (M2c) is typed to CellMessage and belongs to a
 * live cell. The cell cache, however, sends session-level messages -- e.g. a
 * CellReparentMessage when it loads a child whose parent the cache does not (yet)
 * hold -- and those extend Message, not CellMessage (CellEditMessage and
 * CellMessage are siblings). The 2006 cache reached for
 * session.getConnection(CLIENT_TYPE).send(...) for exactly this one call; the
 * session is SGS-bound and unrecoverable headless, so the cache depends on this
 * minimal one-way sink instead. M3's real connection implements both this and
 * MessageChannel; nothing else in the cache talks to the network.
 */
package org.jdesktop.wonderland.client.cell;

import org.jdesktop.wonderland.common.messages.Message;

public interface MessageSink {
    void send(Message message);
}
