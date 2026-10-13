/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d (softphone): the speaker meter listener of the
 * 2006 client, the mirror of MicrophoneInfoListener; same String contract,
 * filled from VuMeter for the playback side of the Java Sound bridge.
 */
package org.jdesktop.wonderland.modules.softphone;

/** The speaker meter; the mirror of the microphone listener. */
public interface SpeakerInfoListener {

    /** A meter reading, e.g. "Speaker level: 17%". */
    void vuMeterValue(String value);

    /** A volume report. */
    void volume(String value);
}
