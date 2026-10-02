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
package org.jdesktop.lg3d.apps.webbrowser;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Application-wide browser preferences, persisted as JSON by
 * {@link BrowserStore} and applied to every {@code WebEngine} the panel creates.
 *
 * <p>The defaults aim for a usable, private-ish modern browser: DuckDuckGo as
 * the search fallback, JavaScript and cookies on, no custom user agent (so sites
 * see the real WebKit one), and history capped at {@value #DEFAULT_HISTORY_LIMIT}
 * entries. The search engine is stored by name (not as an enum) so a hand-edited
 * or newer settings file with an unknown engine degrades to
 * {@link SearchEngine#DUCKDUCKGO} instead of failing to parse.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BrowserSettings {

    /** Page loaded by the Home button and on a fresh start. */
    public static final String DEFAULT_HOME_PAGE = "https://duckduckgo.com";

    /** Default cap on retained history entries. */
    public static final int DEFAULT_HISTORY_LIMIT = 500;

    /** Default zoom (1.0 = 100%). */
    public static final double DEFAULT_ZOOM = 1.0d;

    private String homePage = DEFAULT_HOME_PAGE;
    private String searchEngineName = SearchEngine.DUCKDUCKGO.name();
    /** Empty means "use the WebEngine's own user agent". */
    private String userAgent = "";
    private boolean javaScriptEnabled = true;
    private boolean cookiesEnabled = true;
    private boolean privateBrowsing;
    private boolean restoreSession = true;
    private int historyLimit = DEFAULT_HISTORY_LIMIT;
    private double zoom = DEFAULT_ZOOM;
    /** Directory downloads are saved to; empty means the user's Downloads. */
    private String downloadDir = "";

    public String getHomePage() {
        return (homePage == null || homePage.isBlank()) ? DEFAULT_HOME_PAGE : homePage.trim();
    }
    public void setHomePage(String homePage) { this.homePage = homePage; }

    /** @return the configured search engine, never null. */
    @JsonIgnore
    public SearchEngine getSearchEngine() {
        return SearchEngine.fromName(searchEngineName);
    }

    /** @param engine the search engine (null resets to {@link SearchEngine#DUCKDUCKGO}). */
    @JsonIgnore
    public void setSearchEngine(SearchEngine engine) {
        this.searchEngineName = (engine == null ? SearchEngine.DUCKDUCKGO : engine).name();
    }

    /** @return the raw persisted engine name (for Jackson). */
    public String getSearchEngineName() { return searchEngineName; }
    public void setSearchEngineName(String searchEngineName) {
        this.searchEngineName = (searchEngineName == null || searchEngineName.isBlank())
                ? SearchEngine.DUCKDUCKGO.name()
                : searchEngineName;
    }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = (userAgent == null) ? "" : userAgent; }

    public boolean isJavaScriptEnabled() { return javaScriptEnabled; }
    public void setJavaScriptEnabled(boolean b) { this.javaScriptEnabled = b; }

    public boolean isCookiesEnabled() { return cookiesEnabled; }
    public void setCookiesEnabled(boolean b) { this.cookiesEnabled = b; }

    public boolean isPrivateBrowsing() { return privateBrowsing; }
    public void setPrivateBrowsing(boolean b) { this.privateBrowsing = b; }

    public boolean isRestoreSession() { return restoreSession; }
    public void setRestoreSession(boolean b) { this.restoreSession = b; }

    public int getHistoryLimit() { return historyLimit; }
    public void setHistoryLimit(int historyLimit) {
        this.historyLimit = (historyLimit <= 0) ? DEFAULT_HISTORY_LIMIT : historyLimit;
    }

    public double getZoom() { return zoom; }
    public void setZoom(double zoom) {
        this.zoom = (zoom <= 0.0d) ? DEFAULT_ZOOM : zoom;
    }

    public String getDownloadDir() { return downloadDir; }
    public void setDownloadDir(String downloadDir) { this.downloadDir = (downloadDir == null) ? "" : downloadDir; }

    /** @return an independent copy of these settings. */
    public BrowserSettings copy() {
        BrowserSettings s = new BrowserSettings();
        s.homePage = this.homePage;
        s.searchEngineName = this.searchEngineName;
        s.userAgent = this.userAgent;
        s.javaScriptEnabled = this.javaScriptEnabled;
        s.cookiesEnabled = this.cookiesEnabled;
        s.privateBrowsing = this.privateBrowsing;
        s.restoreSession = this.restoreSession;
        s.historyLimit = this.historyLimit;
        s.zoom = this.zoom;
        s.downloadDir = this.downloadDir;
        return s;
    }
}
