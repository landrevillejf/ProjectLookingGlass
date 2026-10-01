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

/**
 * User-facing, customisable preferences for the Messenger, persisted as JSON by
 * {@link MessengerStore}. Every setter clamps or null-guards so a hand-edited or
 * corrupt settings file can never put the UI into an invalid state.
 */
public class MessengerSettings {

    /** Fallback protocol when none is chosen. */
    public static final String DEFAULT_PROTOCOL = "irc";

    private boolean showTimestamps = true;
    private boolean showJoinsParts;
    private boolean showSystemMessages = true;
    private boolean notifyOnMessage = true;
    private boolean colorNicknames = true;
    private int fontSize = 13;
    private int historyLimit = 500;
    private String defaultProtocolId = DEFAULT_PROTOCOL;
    private boolean autoReconnect = true;
    private int reconnectDelaySeconds = 10;

    public boolean isShowTimestamps() { return showTimestamps; }
    public void setShowTimestamps(boolean b) { this.showTimestamps = b; }

    public boolean isShowJoinsParts() { return showJoinsParts; }
    public void setShowJoinsParts(boolean b) { this.showJoinsParts = b; }

    public boolean isShowSystemMessages() { return showSystemMessages; }
    public void setShowSystemMessages(boolean b) { this.showSystemMessages = b; }

    public boolean isNotifyOnMessage() { return notifyOnMessage; }
    public void setNotifyOnMessage(boolean b) { this.notifyOnMessage = b; }

    public boolean isColorNicknames() { return colorNicknames; }
    public void setColorNicknames(boolean b) { this.colorNicknames = b; }

    public int getFontSize() { return fontSize; }
    public void setFontSize(int fontSize) { this.fontSize = Math.max(8, Math.min(32, fontSize)); }

    public int getHistoryLimit() { return historyLimit; }
    public void setHistoryLimit(int historyLimit) { this.historyLimit = Math.max(0, historyLimit); }

    public String getDefaultProtocolId() { return defaultProtocolId; }
    public void setDefaultProtocolId(String id) {
        this.defaultProtocolId = (id == null || id.isBlank()) ? DEFAULT_PROTOCOL : id;
    }

    public boolean isAutoReconnect() { return autoReconnect; }
    public void setAutoReconnect(boolean b) { this.autoReconnect = b; }

    public int getReconnectDelaySeconds() { return reconnectDelaySeconds; }
    public void setReconnectDelaySeconds(int s) {
        this.reconnectDelaySeconds = Math.max(1, Math.min(300, s));
    }

    public MessengerSettings copy() {
        MessengerSettings c = new MessengerSettings();
        c.showTimestamps = this.showTimestamps;
        c.showJoinsParts = this.showJoinsParts;
        c.showSystemMessages = this.showSystemMessages;
        c.notifyOnMessage = this.notifyOnMessage;
        c.colorNicknames = this.colorNicknames;
        c.fontSize = this.fontSize;
        c.historyLimit = this.historyLimit;
        c.defaultProtocolId = this.defaultProtocolId;
        c.autoReconnect = this.autoReconnect;
        c.reconnectDelaySeconds = this.reconnectDelaySeconds;
        return c;
    }
}
