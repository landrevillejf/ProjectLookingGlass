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
package org.jdesktop.lg3d.apps.passwordmanager;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One credential in the vault: a title, the login / username, the secret itself,
 * an optional URL and free-form notes, filed under a category. An entry is a
 * plain Jackson bean so {@link PasswordVault} can be serialised to JSON and then
 * sealed as a whole by {@link VaultCrypto}; the entry object only ever exists in
 * memory while the vault is unlocked, and its {@code password} field is never
 * written to disk in the clear (the entire vault JSON is what gets encrypted).
 *
 * <p>Every setter normalises its input (null becomes empty, strings are trimmed)
 * so a vault round-trip can never produce a null field the UI would trip on.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PasswordEntry {

    private String title = "";
    private String username = "";
    private String password = "";
    private String url = "";
    private String notes = "";
    private String category = "";
    private long createdMillis = System.currentTimeMillis();
    private long modifiedMillis = System.currentTimeMillis();

    /** No-arg constructor for Jackson. */
    public PasswordEntry() {
    }

    /**
     * Convenience constructor.
     *
     * @param title    the entry's display name
     * @param username the login / user name
     * @param password the secret
     */
    public PasswordEntry(String title, String username, String password) {
        setTitle(title);
        setUsername(username);
        setPassword(password);
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = (title == null) ? "" : title.trim();
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = (username == null) ? "" : username.trim();
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = (password == null) ? "" : password;
        this.modifiedMillis = System.currentTimeMillis();
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = (url == null) ? "" : url.trim();
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = (notes == null) ? "" : notes;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = (category == null) ? "" : category.trim();
    }

    public long getCreatedMillis() {
        return createdMillis;
    }

    public void setCreatedMillis(long createdMillis) {
        this.createdMillis = createdMillis;
    }

    public long getModifiedMillis() {
        return modifiedMillis;
    }

    public void setModifiedMillis(long modifiedMillis) {
        this.modifiedMillis = modifiedMillis;
    }

    /**
     * Touches the modification stamp; call after editing any field so the list can
     * sort by most-recently-changed.
     */
    public void touch() {
        this.modifiedMillis = System.currentTimeMillis();
    }

    /**
     * True when {@code query} (case-insensitive) appears in the title, username,
     * URL or category. The secret is deliberately <em>not</em> searched, so a
     * filter never forces a password into a comparison.
     *
     * @param query the search text (null/blank matches everything)
     * @return true when this entry matches
     */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        return title.toLowerCase(java.util.Locale.ROOT).contains(q)
                || username.toLowerCase(java.util.Locale.ROOT).contains(q)
                || url.toLowerCase(java.util.Locale.ROOT).contains(q)
                || category.toLowerCase(java.util.Locale.ROOT).contains(q);
    }

    @Override
    public String toString() {
        return title.isBlank() ? "(untitled)" : title;
    }
}
