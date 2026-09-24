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
package org.jdesktop.lg3d.dbmanager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Application-wide preferences for the Database Manager, persisted as JSON by
 * {@link ProfileStore}.
 *
 * <p>Every field has a sane default so a fresh install (no settings file yet)
 * works out of the box. The values here are the global defaults; a
 * {@link ConnectionProfile} may override the row limit per connection.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppSettings {

    /** Global hard cap on rows fetched for a result set; {@code <=0} disables it. */
    private int maxRows = 1000;
    /** Rows loaded per page in the results grid. */
    private int pageSize = 200;
    /** JDBC fetch size hint passed to statements. */
    private int fetchSize = 200;
    /** Text shown for SQL NULL values in the grid. */
    private String nullText = "(null)";
    /** Per-query timeout in seconds; {@code 0} means no timeout. */
    private int queryTimeoutSeconds = 60;
    /** Login/connection timeout in seconds; {@code 0} means driver default. */
    private int connectTimeoutSeconds = 15;
    /** Default auto-commit state for new connections. */
    private boolean autoCommitDefault = true;
    /** Whether profiles are allowed to persist (obfuscated) passwords. */
    private boolean allowSavePasswords = false;
    /** Ask for confirmation before committing data edits. */
    private boolean confirmOnWrite = true;
    /** Editor font size in points. */
    private int editorFontSize = 13;
    /** Number of recent SQL statements kept in history. */
    private int historyLimit = 50;

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getFetchSize() {
        return fetchSize;
    }

    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }

    public String getNullText() {
        return nullText;
    }

    public void setNullText(String nullText) {
        this.nullText = (nullText == null) ? "" : nullText;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public boolean isAutoCommitDefault() {
        return autoCommitDefault;
    }

    public void setAutoCommitDefault(boolean autoCommitDefault) {
        this.autoCommitDefault = autoCommitDefault;
    }

    public boolean isAllowSavePasswords() {
        return allowSavePasswords;
    }

    public void setAllowSavePasswords(boolean allowSavePasswords) {
        this.allowSavePasswords = allowSavePasswords;
    }

    public boolean isConfirmOnWrite() {
        return confirmOnWrite;
    }

    public void setConfirmOnWrite(boolean confirmOnWrite) {
        this.confirmOnWrite = confirmOnWrite;
    }

    public int getEditorFontSize() {
        return editorFontSize;
    }

    public void setEditorFontSize(int editorFontSize) {
        this.editorFontSize = editorFontSize;
    }

    public int getHistoryLimit() {
        return historyLimit;
    }

    public void setHistoryLimit(int historyLimit) {
        this.historyLimit = historyLimit;
    }

    /**
     * The effective row cap for a query: the tighter of the global
     * {@link #getMaxRows()} and a per-connection limit.
     *
     * @param profileRowLimit the profile's row limit ({@code <=0} = uncapped)
     * @return the number of rows to fetch; {@code 0} means no cap
     */
    public int effectiveRowLimit(int profileRowLimit) {
        if (maxRows <= 0) {
            return Math.max(profileRowLimit, 0);
        }
        if (profileRowLimit <= 0) {
            return maxRows;
        }
        return Math.min(maxRows, profileRowLimit);
    }
}
