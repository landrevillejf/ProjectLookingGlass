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
import java.util.UUID;

/**
 * A person the user can invite to, or call directly in, a video conference.
 *
 * <p>A contact carries only the identity needed to reach them on a Jitsi Meet
 * deployment: a display name and an e-mail. The e-mail doubles as the
 * {@code userInfo.email} the meeting uses for gravatar/initials avatars and for
 * the invitation link, so inviting a contact is simply sharing the room URL
 * with them.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Contact {

    private String id = UUID.randomUUID().toString();
    private String name = "";
    private String email = "";
    private boolean favorite;
    private String notes = "";

    public Contact() {
    }

    /**
     * Creates a contact with a name and e-mail.
     *
     * @param name  display name
     * @param email contact e-mail (may be empty)
     */
    public Contact(String name, String email) {
        this.name = (name == null) ? "" : name;
        this.email = (email == null) ? "" : email;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = (name == null) ? "" : name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = (email == null) ? "" : email; }

    /** @return true when pinned to the top of the contact list. */
    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = (notes == null) ? "" : notes; }

    /** @return an independent copy of this contact. */
    public Contact copy() {
        Contact c = new Contact();
        c.id = this.id;
        c.name = this.name;
        c.email = this.email;
        c.favorite = this.favorite;
        c.notes = this.notes;
        return c;
    }

    @Override
    public String toString() {
        if (email == null || email.isEmpty()) {
            return (name == null || name.isEmpty()) ? "(unnamed)" : name;
        }
        return (name == null || name.isEmpty()) ? email : name + " <" + email + ">";
    }
}
