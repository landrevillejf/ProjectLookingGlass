/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7a (videoplayer): the transport lifecycle states.
 *
 * Mirrors the OpenJFX MediaPlayer Status vocabulary the live JfxMediaSurface
 * maps onto (PLAYING / PAUSED / STOPPED / DISPOSED / ENDED), collapsed to the
 * player-meaningful five. ENDED is terminal until play() or load() restarts.
 */
package org.jdesktop.wonderland.modules.videoplayer;

/** The lifecycle of a {@link VideoTransport}. */
public enum TransportState {

    /** Nothing loaded, or freshly loaded and not yet started. */
    IDLE,

    /** Media is advancing. */
    PLAYING,

    /** Media is suspended at the current position. */
    PAUSED,

    /** Media halted; the position is back at zero. */
    STOPPED,

    /** Media reached its duration; terminal until restarted. */
    ENDED
}
