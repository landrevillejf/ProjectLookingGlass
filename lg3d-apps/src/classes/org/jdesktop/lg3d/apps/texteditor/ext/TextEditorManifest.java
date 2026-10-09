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
package org.jdesktop.lg3d.apps.texteditor.ext;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable metadata an extension publishes through
 * {@link TextEditorExtension#manifest()}: its identity, a human-readable blurb and
 * the {@link TextEditorPermission permissions} it needs. The manager displays this and
 * gates enabling on the user approving the declared permissions.
 */
public final class TextEditorManifest {

    private final String id;
    private final String name;
    private final String version;
    private final String description;
    private final String author;
    private final Set<TextEditorPermission> permissions;

    /**
     * Builds a manifest, normalising blank fields to safe defaults.
     *
     * @param id          stable unique identifier (e.g. {@code lg3d.text-tools})
     * @param name        display name
     * @param version     semantic-ish version string
     * @param description one-line blurb shown in the manager
     * @param author      author / vendor (may be null)
     * @param permissions the permissions the extension needs (may be null/empty)
     */
    public TextEditorManifest(String id, String name, String version,
                              String description, String author, Set<TextEditorPermission> permissions) {
        this.id = (id == null || id.isBlank()) ? "unknown" : id.trim();
        this.name = (name == null || name.isBlank()) ? this.id : name.trim();
        this.version = (version == null || version.isBlank()) ? "0.0.0" : version.trim();
        this.description = (description == null) ? "" : description.trim();
        this.author = (author == null) ? "" : author.trim();
        this.permissions = (permissions == null || permissions.isEmpty())
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(permissions));
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getDescription() { return description; }
    public String getAuthor() { return author; }

    /** @return the declared permissions, never null and immutable. */
    public Set<TextEditorPermission> getPermissions() { return permissions; }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TextEditorManifest)) {
            return false;
        }
        TextEditorManifest that = (TextEditorManifest) o;
        return id.equals(that.id) && version.equals(that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version);
    }

    @Override
    public String toString() {
        return name + " " + version + " [" + id + "] " + permissions;
    }
}
