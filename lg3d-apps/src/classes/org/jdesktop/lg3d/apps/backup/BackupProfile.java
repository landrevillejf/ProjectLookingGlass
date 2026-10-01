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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * A saved backup configuration: which folders/files to archive, where to put
 * the archive, and how to build it. Profiles are plain Jackson beans persisted
 * by {@link BackupStore}; the class stays serialisable (no-arg constructor plus
 * getters/setters, no live handles) so a config file survives a restart.
 *
 * <p>Sources are stored as absolute path strings. Archive entry names are
 * derived relative to each source's parent, so backing up {@code ~/Documents}
 * yields a top-level {@code Documents/} folder inside the archive and a restore
 * into {@code /target} recreates {@code /target/Documents/...}.</p>
 *
 * <p>Exclude patterns are glob expressions matched against the archive-relative
 * entry path and the bare file name (e.g. {@code *.tmp}, {@code *.log},
 * {@code **}{@code /cache/**}). A directory whose relative path matches is
 * pruned wholesale.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BackupProfile {

    /** Default DEFLATE level: a good size/speed balance. */
    public static final int DEFAULT_COMPRESSION = 6;

    private String name = "New Backup";
    private List<String> sources = new ArrayList<>();
    private String destinationDir = System.getProperty("user.home");
    private String archiveBaseName = "backup";
    private int compressionLevel = DEFAULT_COMPRESSION;
    private boolean includeHidden = false;
    private boolean timestampArchiveName = true;
    private List<String> excludePatterns = new ArrayList<>();

    /** No-arg constructor for Jackson. */
    public BackupProfile() {
    }

    /**
     * Convenience constructor.
     *
     * @param name            the profile display name
     * @param destinationDir  the folder the archive is written into
     * @param archiveBaseName the archive base name (a timestamp and
     *                        {@code .zip} are appended when
     *                        {@link #isTimestampArchiveName()} is set)
     */
    public BackupProfile(String name, String destinationDir, String archiveBaseName) {
        if (name != null) {
            this.name = name;
        }
        if (destinationDir != null) {
            this.destinationDir = destinationDir;
        }
        if (archiveBaseName != null) {
            this.archiveBaseName = archiveBaseName;
        }
    }

    /** @return a deep copy of this profile. */
    public BackupProfile copy() {
        BackupProfile p = new BackupProfile();
        p.name = this.name;
        p.sources = new ArrayList<>(this.sources);
        p.destinationDir = this.destinationDir;
        p.archiveBaseName = this.archiveBaseName;
        p.compressionLevel = this.compressionLevel;
        p.includeHidden = this.includeHidden;
        p.timestampArchiveName = this.timestampArchiveName;
        p.excludePatterns = new ArrayList<>(this.excludePatterns);
        return p;
    }

    // ------------------------------------------------------------------
    // Name
    // ------------------------------------------------------------------

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = (name == null || name.isBlank()) ? "New Backup" : name;
    }

    // ------------------------------------------------------------------
    // Sources
    // ------------------------------------------------------------------

    /** @return the absolute source paths (never null). */
    public List<String> getSources() {
        if (sources == null) {
            sources = new ArrayList<>();
        }
        return sources;
    }

    public void setSources(List<String> sources) {
        this.sources = (sources == null) ? new ArrayList<>() : new ArrayList<>(sources);
    }

    // ------------------------------------------------------------------
    // Destination / archive naming
    // ------------------------------------------------------------------

    public String getDestinationDir() {
        return destinationDir;
    }

    public void setDestinationDir(String destinationDir) {
        this.destinationDir = (destinationDir == null || destinationDir.isBlank())
                ? System.getProperty("user.home")
                : destinationDir;
    }

    public String getArchiveBaseName() {
        return archiveBaseName;
    }

    public void setArchiveBaseName(String archiveBaseName) {
        this.archiveBaseName = (archiveBaseName == null || archiveBaseName.isBlank())
                ? "backup"
                : archiveBaseName;
    }

    // ------------------------------------------------------------------
    // Compression
    // ------------------------------------------------------------------

    /** @return the DEFLATE level, clamped to {@code 0..9}. */
    public int getCompressionLevel() {
        return compressionLevel;
    }

    public void setCompressionLevel(int compressionLevel) {
        this.compressionLevel = Math.max(0, Math.min(9, compressionLevel));
    }

    // ------------------------------------------------------------------
    // Options
    // ------------------------------------------------------------------

    /** @return true to archive dot-files; false (default) to skip them. */
    public boolean isIncludeHidden() {
        return includeHidden;
    }

    public void setIncludeHidden(boolean includeHidden) {
        this.includeHidden = includeHidden;
    }

    /**
     * @return true to append a {@code yyyyMMdd-HHmmss} timestamp to the archive
     *         name (default), so successive runs never overwrite; false to write
     *         a fixed {@code <base>.zip}.
     */
    public boolean isTimestampArchiveName() {
        return timestampArchiveName;
    }

    public void setTimestampArchiveName(boolean timestampArchiveName) {
        this.timestampArchiveName = timestampArchiveName;
    }

    /** @return the glob exclude patterns (never null). */
    public List<String> getExcludePatterns() {
        if (excludePatterns == null) {
            excludePatterns = new ArrayList<>();
        }
        return excludePatterns;
    }

    public void setExcludePatterns(List<String> excludePatterns) {
        this.excludePatterns = (excludePatterns == null) ? new ArrayList<>() : new ArrayList<>(excludePatterns);
    }

    /**
     * Sets the exclude patterns from a single comma-separated field.
     *
     * @param csv patterns separated by commas; blank entries are dropped
     */
    public void setExcludePatternsFromCsv(String csv) {
        List<String> list = new ArrayList<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) {
                    list.add(t);
                }
            }
        }
        this.excludePatterns = list;
    }

    /** @return the exclude patterns as a comma-separated string. */
    public String getExcludePatternsAsCsv() {
        return String.join(", ", getExcludePatterns());
    }

    @Override
    public String toString() {
        return (name == null) ? "(unnamed)" : name;
    }
}
