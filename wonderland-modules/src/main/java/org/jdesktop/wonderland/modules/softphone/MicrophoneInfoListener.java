/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d (softphone): the microphone meter listener of the
 * 2006 client. The String payloads were already the contract — the port
 * fills them from VuMeter.valueString() ("Microphone level: 42%") rather
 * than from the external binary that painted its own needle.
 */
package org.jdesktop.wonderland.modules.softphone;

/** The microphone meter, in the 2006 one-String-per-event shape. */
public interface MicrophoneInfoListener {

    /** A meter reading, e.g. "Microphone level: 42%". */
    void vuMeterValue(String value);

    /** A volume report; the 2006 protocol kept this separate from the meter
     *  because the preference panel showed input volume, not the needle. */
    void volume(String value);
}
