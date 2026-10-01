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
package org.jdesktop.lg3d.apps.videoconference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Application-wide video-conference preferences, persisted as JSON by
 * {@link VideoConferenceStore} and consulted by {@link JitsiUrlBuilder} when a
 * room does not override an option.
 *
 * <p>The defaults target the public {@value #DEFAULT_DOMAIN} Jitsi Meet
 * deployment; a self-hosted Jitsi (or any Jitsi-compatible server) is reached by
 * changing {@link #getDefaultDomain()}. Meetings are launched either in the
 * system browser ({@link LaunchMode#BROWSER}) or through an external command
 * template ({@link LaunchMode#EXTERNAL_COMMAND}, e.g. a Jitsi desktop or SIP
 * client), with {@code %URL} substituted for the meeting URL.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VideoConferenceSettings {

    /** The public Jitsi Meet deployment used when no domain is configured. */
    public static final String DEFAULT_DOMAIN = "meet.jit.si";

    /** Placeholder in the external command template replaced by the meeting URL. */
    public static final String URL_TOKEN = "%URL";

    /** How the meeting session is launched. */
    public enum LaunchMode {
        /** Hand the URL to {@code java.awt.Desktop.browse} (system browser). */
        BROWSER,
        /** Run {@link VideoConferenceSettings#getExternalCommand()}. */
        EXTERNAL_COMMAND
    }

    private String defaultDomain = DEFAULT_DOMAIN;
    private String displayName = "";
    private String email = "";
    private LaunchMode launchMode = LaunchMode.BROWSER;
    /** External command template; {@value #URL_TOKEN} is replaced by the URL. */
    private String externalCommand = "";

    private boolean startWithAudioMuted;
    private boolean startWithVideoMuted = true;
    /** Skip the pre-join device screen and enter the meeting immediately. */
    private boolean disablePrejoinPage = true;
    /** Show a live transcription / captions toggle hint in the meeting. */
    private boolean enableWelcomePage;
    /** Maximum entries retained in the recent-calls history. */
    private int historyLimit = 50;
    /** Preferred camera device id passed to the meeting (empty = default). */
    private String preferredCamera = "";
    /** Preferred microphone device id passed to the meeting (empty = default). */
    private String preferredMicrophone = "";

    public String getDefaultDomain() {
        return (defaultDomain == null || defaultDomain.isBlank()) ? DEFAULT_DOMAIN : defaultDomain.trim();
    }
    public void setDefaultDomain(String defaultDomain) { this.defaultDomain = defaultDomain; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = (displayName == null) ? "" : displayName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = (email == null) ? "" : email; }

    public LaunchMode getLaunchMode() {
        return (launchMode == null) ? LaunchMode.BROWSER : launchMode;
    }
    public void setLaunchMode(LaunchMode launchMode) { this.launchMode = launchMode; }

    public String getExternalCommand() { return externalCommand; }
    public void setExternalCommand(String externalCommand) {
        this.externalCommand = (externalCommand == null) ? "" : externalCommand;
    }

    public boolean isStartWithAudioMuted() { return startWithAudioMuted; }
    public void setStartWithAudioMuted(boolean b) { this.startWithAudioMuted = b; }

    public boolean isStartWithVideoMuted() { return startWithVideoMuted; }
    public void setStartWithVideoMuted(boolean b) { this.startWithVideoMuted = b; }

    public boolean isDisablePrejoinPage() { return disablePrejoinPage; }
    public void setDisablePrejoinPage(boolean b) { this.disablePrejoinPage = b; }

    public boolean isEnableWelcomePage() { return enableWelcomePage; }
    public void setEnableWelcomePage(boolean b) { this.enableWelcomePage = b; }

    public int getHistoryLimit() { return historyLimit; }
    public void setHistoryLimit(int historyLimit) {
        this.historyLimit = Math.max(0, historyLimit);
    }

    public String getPreferredCamera() { return preferredCamera; }
    public void setPreferredCamera(String preferredCamera) {
        this.preferredCamera = (preferredCamera == null) ? "" : preferredCamera;
    }

    public String getPreferredMicrophone() { return preferredMicrophone; }
    public void setPreferredMicrophone(String preferredMicrophone) {
        this.preferredMicrophone = (preferredMicrophone == null) ? "" : preferredMicrophone;
    }

    /** @return an independent copy of these settings. */
    public VideoConferenceSettings copy() {
        VideoConferenceSettings c = new VideoConferenceSettings();
        c.defaultDomain = this.defaultDomain;
        c.displayName = this.displayName;
        c.email = this.email;
        c.launchMode = this.launchMode;
        c.externalCommand = this.externalCommand;
        c.startWithAudioMuted = this.startWithAudioMuted;
        c.startWithVideoMuted = this.startWithVideoMuted;
        c.disablePrejoinPage = this.disablePrejoinPage;
        c.enableWelcomePage = this.enableWelcomePage;
        c.historyLimit = this.historyLimit;
        c.preferredCamera = this.preferredCamera;
        c.preferredMicrophone = this.preferredMicrophone;
        return c;
    }
}
