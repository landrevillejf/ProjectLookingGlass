/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4 (user-list): one present user.
 *
 * A user is identified by a stable id (the session / avatar id the 2006 module
 * keyed presence on) with a display name shown in the roster.
 */
package org.jdesktop.wonderland.modules.userlist;

import java.util.Objects;

/** One entry in the present-user roster. */
public final class PresentUser {

    private final String id;
    private final String name;

    /**
     * @param id stable presence key, non-blank
     * @param name display name, non-blank
     */
    public PresentUser(String id, String name) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("user id must not be blank");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("user name must not be blank");
        }
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PresentUser)) {
            return false;
        }
        return id.equals(((PresentUser) obj).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return name + " (" + id + ")";
    }
}
