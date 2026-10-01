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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of a backup or restore run: whether it finished, what archive it
 * produced or consumed, how much data moved, how long it took, and any
 * non-fatal warnings (skipped unreadable files, rejected Zip-Slip entries).
 *
 * <p>Immutable once built via {@link Builder}; the warnings list is defensive
 * and unmodifiable. A run that was cancelled reports {@code cancelled == true}
 * and {@code success == false}.</p>
 */
public final class BackupResult {

    private final boolean success;
    private final boolean cancelled;
    private final Path archive;
    private final int entryCount;
    private final long totalBytes;
    private final long durationMillis;
    private final String message;
    private final List<String> warnings;

    private BackupResult(Builder b) {
        this.success = b.success;
        this.cancelled = b.cancelled;
        this.archive = b.archive;
        this.entryCount = b.entryCount;
        this.totalBytes = b.totalBytes;
        this.durationMillis = b.durationMillis;
        this.message = b.message;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(b.warnings));
    }

    /** @return a fresh builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** @return true if the run completed without a fatal error or cancel. */
    public boolean isSuccess() {
        return success;
    }

    /** @return true if the run was cancelled by the user. */
    public boolean isCancelled() {
        return cancelled;
    }

    /** @return the archive written (backup) or read (restore), may be null. */
    public Path getArchive() {
        return archive;
    }

    /** @return the number of archive entries processed. */
    public int getEntryCount() {
        return entryCount;
    }

    /** @return the total uncompressed bytes processed. */
    public long getTotalBytes() {
        return totalBytes;
    }

    /** @return wall-clock duration of the run in milliseconds. */
    public long getDurationMillis() {
        return durationMillis;
    }

    /** @return a short human-readable summary, never null. */
    public String getMessage() {
        return (message == null) ? "" : message;
    }

    /** @return the non-fatal warnings, never null. */
    public List<String> getWarnings() {
        return warnings;
    }

    @Override
    public String toString() {
        return "BackupResult{success=" + success + ", cancelled=" + cancelled
                + ", entries=" + entryCount + ", bytes=" + totalBytes
                + ", ms=" + durationMillis + ", archive=" + archive + "}";
    }

    /** Mutable builder for {@link BackupResult}. */
    public static final class Builder {
        private boolean success;
        private boolean cancelled;
        private Path archive;
        private int entryCount;
        private long totalBytes;
        private long durationMillis;
        private String message = "";
        private final List<String> warnings = new ArrayList<>();

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder cancelled(boolean cancelled) {
            this.cancelled = cancelled;
            return this;
        }

        public Builder archive(Path archive) {
            this.archive = archive;
            return this;
        }

        public Builder entryCount(int entryCount) {
            this.entryCount = entryCount;
            return this;
        }

        public Builder totalBytes(long totalBytes) {
            this.totalBytes = totalBytes;
            return this;
        }

        public Builder durationMillis(long durationMillis) {
            this.durationMillis = durationMillis;
            return this;
        }

        public Builder message(String message) {
            this.message = (message == null) ? "" : message;
            return this;
        }

        public Builder addWarning(String warning) {
            if (warning != null && !warning.isBlank()) {
                this.warnings.add(warning);
            }
            return this;
        }

        public Builder addWarnings(List<String> warnings) {
            if (warnings != null) {
                for (String w : warnings) {
                    addWarning(w);
                }
            }
            return this;
        }

        /** @return the immutable result. */
        public BackupResult build() {
            return new BackupResult(this);
        }
    }
}
