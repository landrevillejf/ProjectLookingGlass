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
package org.jdesktop.lg3d.ftpclient.net;

import java.util.Objects;

/**
 * One entry in a remote directory listing: the protocol-neutral view the UI and
 * the transfer logic work with, whether it came from an FTP {@code LIST}/{@code MLSD}
 * or an SFTP {@code ChannelSftp.ls}.
 *
 * <p>Instances are immutable value objects. {@link #getModifiedMillis()} and
 * {@link #getSize()} may be {@code 0} / {@code -1} when the server did not report
 * them, so callers must treat those as "unknown" rather than "empty".</p>
 */
public final class RemoteEntry {

    /** Sentinel for an unknown file size. */
    public static final long UNKNOWN_SIZE = -1L;

    private final String name;
    private final long size;
    private final long modifiedMillis;
    private final boolean directory;
    private final String permissions;
    private final String linkTarget;

    /**
     * Creates a listing entry.
     *
     * @param name           the entry's base name (never the full path)
     * @param size           the file size in bytes, or {@link #UNKNOWN_SIZE}
     * @param modifiedMillis the last-modified time in epoch millis, or {@code 0}
     * @param directory      {@code true} for a directory (or a symlink to one)
     * @param permissions    a permission string such as {@code rwxr-xr-x}, or {@code ""}
     * @param linkTarget     the symlink target, or {@code null} when not a link
     */
    public RemoteEntry(String name, long size, long modifiedMillis,
                       boolean directory, String permissions, String linkTarget) {
        this.name = Objects.requireNonNullElse(name, "");
        this.size = size;
        this.modifiedMillis = modifiedMillis;
        this.directory = directory;
        this.permissions = Objects.requireNonNullElse(permissions, "");
        this.linkTarget = linkTarget;
    }

    /**
     * Creates a plain file or directory entry with no permission or link detail.
     *
     * @param name           the entry's base name
     * @param size           the file size in bytes, or {@link #UNKNOWN_SIZE}
     * @param modifiedMillis the last-modified time in epoch millis, or {@code 0}
     * @param directory      {@code true} for a directory
     */
    public RemoteEntry(String name, long size, long modifiedMillis, boolean directory) {
        this(name, size, modifiedMillis, directory, "", null);
    }

    public String getName() {
        return name;
    }

    /** @return the file size in bytes, or {@link #UNKNOWN_SIZE} when not reported. */
    public long getSize() {
        return size;
    }

    /** @return the last-modified time in epoch millis, or {@code 0} when unknown. */
    public long getModifiedMillis() {
        return modifiedMillis;
    }

    public boolean isDirectory() {
        return directory;
    }

    /** @return the permission string, possibly empty; never {@code null}. */
    public String getPermissions() {
        return permissions;
    }

    /** @return the symlink target, or {@code null} when this is not a link. */
    public String getLinkTarget() {
        return linkTarget;
    }

    /** @return {@code true} when the name begins with a dot (a Unix hidden file). */
    public boolean isHidden() {
        return name.startsWith(".") && !name.equals(".") && !name.equals("..");
    }

    /** @return {@code true} for the {@code .} and {@code ..} navigation entries. */
    public boolean isSelfOrParent() {
        return name.equals(".") || name.equals("..");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RemoteEntry other)) {
            return false;
        }
        return size == other.size
                && modifiedMillis == other.modifiedMillis
                && directory == other.directory
                && name.equals(other.name)
                && permissions.equals(other.permissions)
                && Objects.equals(linkTarget, other.linkTarget);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, size, modifiedMillis, directory, permissions, linkTarget);
    }

    @Override
    public String toString() {
        return (directory ? "[D] " : "[F] ") + name + " (" + size + ")";
    }
}
