/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.contacts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One entry of the desktop-wide address book.
 *
 * <p>This is the single, shared contact model every desktop application reads
 * and writes: the production Contacts app ({@code lg3d-apps}), the Agenda
 * attendee picker ({@code lg3d-incubator}) and the Messenger / Video Conference
 * address books ({@code lg3d-apps}). It lives in {@code lg3d-core} because that
 * is the only module all of those consumers already depend on, so the address
 * book needs no new inter-module dependency and no reflective hand-off.</p>
 *
 * <p>A contact carries only identity the user entered — there is deliberately
 * <b>no seeded or demo data</b> anywhere in the address book. Multi-valued
 * fields (e-mails, phones, tags) are plain lists; the first element is the
 * "primary" one by convention. Passwords or other secrets must never be stored
 * here.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Contact {

    private String id = UUID.randomUUID().toString();
    private String firstName = "";
    private String lastName = "";
    private String nickname = "";
    private List<String> emails = new ArrayList<>();
    private List<String> phones = new ArrayList<>();
    private String organization = "";
    private String title = "";
    private String notes = "";
    private List<String> tags = new ArrayList<>();
    private boolean favorite;
    private long createdMillis = System.currentTimeMillis();
    private long updatedMillis = createdMillis;

    public Contact() {
    }

    /**
     * Creates a contact with a name and primary e-mail.
     *
     * @param firstName given name (may be empty)
     * @param lastName  family name (may be empty)
     * @param email     primary e-mail (may be empty)
     */
    public Contact(String firstName, String lastName, String email) {
        this.firstName = (firstName == null) ? "" : firstName;
        this.lastName = (lastName == null) ? "" : lastName;
        if (email != null && !email.isEmpty()) {
            this.emails.add(email);
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = (id == null) ? UUID.randomUUID().toString() : id; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String v) { this.firstName = (v == null) ? "" : v; }

    public String getLastName() { return lastName; }
    public void setLastName(String v) { this.lastName = (v == null) ? "" : v; }

    public String getNickname() { return nickname; }
    public void setNickname(String v) { this.nickname = (v == null) ? "" : v; }

    public List<String> getEmails() { return emails; }
    public void setEmails(List<String> v) { this.emails = (v == null) ? new ArrayList<>() : v; }

    public List<String> getPhones() { return phones; }
    public void setPhones(List<String> v) { this.phones = (v == null) ? new ArrayList<>() : v; }

    public String getOrganization() { return organization; }
    public void setOrganization(String v) { this.organization = (v == null) ? "" : v; }

    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = (v == null) ? "" : v; }

    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = (v == null) ? "" : v; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> v) { this.tags = (v == null) ? new ArrayList<>() : v; }

    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean v) { this.favorite = v; }

    public long getCreatedMillis() { return createdMillis; }
    public void setCreatedMillis(long v) { this.createdMillis = v; }

    public long getUpdatedMillis() { return updatedMillis; }
    public void setUpdatedMillis(long v) { this.updatedMillis = v; }

    /** The name to show in lists: full name, else nickname, else primary e-mail. */
    public String displayName() {
        String full = (firstName + " " + lastName).trim();
        if (!full.isEmpty()) {
            return full;
        }
        if (!nickname.isEmpty()) {
            return nickname;
        }
        return primaryEmail().isEmpty() ? "(unnamed)" : primaryEmail();
    }

    /** The first e-mail, or an empty string when the contact has none. */
    public String primaryEmail() {
        return emails.isEmpty() ? "" : emails.get(0);
    }

    /** The first phone number, or an empty string when the contact has none. */
    public String primaryPhone() {
        return phones.isEmpty() ? "" : phones.get(0);
    }

    @Override
    public String toString() {
        return displayName();
    }
}
