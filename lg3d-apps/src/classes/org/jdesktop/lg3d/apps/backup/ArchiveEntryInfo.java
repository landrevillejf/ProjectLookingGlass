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
package org.jdesktop.lg3d.apps.backup;

/**
 * One entry of a backup archive, used to browse an archive's contents before
 * restoring it and to sanity-check what a backup produced.
 *
 * <p>Immutable value object.</p>
 */
public final class ArchiveEntryInfo {

    private final String name;
    private final long size;
    private final long compressedSize;
    private final boolean directory;
    private final long lastModifiedMillis;

    /**
     * @param name               the archive-relative entry name
     * @param size               the uncompressed size in bytes ({@code -1} if unknown)
     * @param compressedSize     the stored size in bytes ({@code -1} if unknown)
     * @param directory          true for a directory entry
     * @param lastModifiedMillis the entry timestamp in epoch millis ({@code 0} if unknown)
     */
    public ArchiveEntryInfo(String name, long size, long compressedSize,
                            boolean directory, long lastModifiedMillis) {
        this.name = (name == null) ? "" : name;
        this.size = size;
        this.compressedSize = compressedSize;
        this.directory = directory;
        this.lastModifiedMillis = lastModifiedMillis;
    }

    /** @return the archive-relative entry name, never null. */
    public String getName() {
        return name;
    }

    /** @return the uncompressed size in bytes, or {@code -1} if unknown. */
    public long getSize() {
        return size;
    }

    /** @return the stored (compressed) size in bytes, or {@code -1} if unknown. */
    public long getCompressedSize() {
        return compressedSize;
    }

    /** @return true if this entry is a directory. */
    public boolean isDirectory() {
        return directory;
    }

    /** @return the entry timestamp in epoch millis, or {@code 0} if unknown. */
    public long getLastModifiedMillis() {
        return lastModifiedMillis;
    }

    @Override
    public String toString() {
        return name + (directory ? "/" : " (" + size + " B)");
    }
}
