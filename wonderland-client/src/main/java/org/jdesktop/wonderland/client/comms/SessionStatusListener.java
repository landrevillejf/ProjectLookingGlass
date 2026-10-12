/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.comms),
 * verbatim. One observer method; the session seam fires it on every
 * WonderlandSession.Status transition, exactly as the 2006 SGS handler did.
 */
package org.jdesktop.wonderland.client.comms;

import org.jdesktop.wonderland.common.ExperimentalAPI;

@ExperimentalAPI
public interface SessionStatusListener {

    void sessionStatusChanged(WonderlandSession session, WonderlandSession.Status status);
}
