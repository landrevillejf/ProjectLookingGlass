/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7d (test): the recording backend shared by
 * SoftphoneControlTest and SoftphonePluginTest. It stands in for the external
 * softphone process in CI exactly like the recording rasteriser stood in for
 * PDFBox in M7b: it logs every stdin line the control writes, reports itself
 * alive once started, and lets a test fire typed events back through the
 * handler the control installed.
 */
package org.jdesktop.wonderland.modules.softphone;

import java.util.ArrayList;
import java.util.List;

/** A SoftphoneBackend that records commands and can inject events. */
class RecordingBackend implements SoftphoneBackend {

    final List<String> lines = new ArrayList<>();
    boolean alive;
    EventHandler handler;

    @Override
    public boolean start(String host, String user, int port, String number) {
        lines.add("START " + host + ':' + port + ' ' + user + ' ' + number);
        alive = true;
        return true;
    }

    @Override
    public boolean send(SoftphoneCommand command) {
        if (!alive) {
            return false;
        }
        lines.add(command.toLine());
        return true;
    }

    @Override
    public boolean isRunning() {
        return alive;
    }

    @Override
    public void stop() {
        alive = false;
    }

    /** Deliver one event through the wired handler, as the process reader
     *  thread would. */
    void fire(SoftphoneEvent event) {
        handler.onEvent(event);
    }
}
