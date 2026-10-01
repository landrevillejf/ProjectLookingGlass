/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.messenger;

import java.util.List;

/**
 * The callback surface a {@link MessengerProtocol} uses to push asynchronous
 * events back to the client. All methods are invoked on the protocol's own I/O
 * thread, <em>never</em> on the Swing EDT, so an implementation (the panel)
 * must marshal into the EDT itself before touching any widget.
 *
 * <p>The {@code default} no-ops keep optional events (roster, status) from
 * forcing every listener to implement methods it does not care about.</p>
 */
public interface ProtocolListener {

    /** The connection completed registration and is ready for traffic. */
    void onConnected(AccountConfig account);

    /** The connection ended (cleanly or not). */
    void onDisconnected(AccountConfig account, String reason);

    /** A chat/presence/system event arrived for a conversation. */
    void onMessage(AccountConfig account, ChatMessage message);

    /** A channel's membership list changed. */
    default void onRosterUpdate(AccountConfig account, String channel, List<String> members) {
        // optional
    }

    /** A transient, human-readable status line (connecting, MOTD, etc.). */
    default void onStatus(AccountConfig account, String status) {
        // optional
    }

    /** A recoverable error the user should see but that did not drop the link. */
    default void onError(AccountConfig account, String error) {
        // optional
    }
}
