/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server P5.4: the world writes itself down.
 *
 * CellSnapshot was delivered as save/restore that nothing called -- a memory of
 * where furniture was that only a hand could invoke. This class is the policy
 * that runs it without one: an autosave that watches the WorldModel's revision
 * counter and, when it has moved AND a quiet interval has passed, writes the
 * snapshot. The clock is a parameter, never System.currentTimeMillis(), because
 * the whole judgement -- "is there anything new, has it settled" -- is exactly
 * the part a headless test must be able to drive at an arbitrary instant; the
 * thin @Scheduled glue bean is the only half that reads a real clock.
 *
 * Two rules make it safe to leave running unattended:
 *   - a save that fails (a disk full, a read-only mount) is LOGGED and the world
 *     stays dirty, so the next tick retries -- an autosave must never take the
 *     running server down over its own bookkeeping, and never claim a place was
 *     saved when it was not;
 *   - a save with an unchanged revision writes nothing, so a busy-but-unchanged
 *     world costs no disk traffic, and the very first tick on a world nobody has
 *     touched leaves no file at all (an empty world has nothing to remember).
 */
package org.jdesktop.wonderland.server.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Throttled write of the {@link CellSnapshot} when the world has changed. */
public final class WorldAutosaver {

    private static final Logger logger = Logger.getLogger(WorldAutosaver.class.getName());

    private final WorldModel model;
    private final Path file;
    private final long intervalMs;

    /** The revision the last successful save wrote, or -1 before any save. */
    private long savedRevision = -1;
    /** The instant of the last save attempt, so the interval throttles retries. */
    private long lastSaveMs;

    public WorldAutosaver(WorldModel model, Path file, long intervalMs) {
        if (intervalMs < 0) {
            throw new IllegalArgumentException("an autosave interval cannot be negative");
        }
        this.model = model;
        this.file = file;
        this.intervalMs = intervalMs;
        this.lastSaveMs = 0;
    }

    /** Whether a change is waiting that has not yet been written. */
    public boolean isDirty() {
        return model.revision() != savedRevision;
    }

    /**
     * One scheduled beat. Saves only when there is unsaved state AND at least
     * {@code intervalMs} has elapsed since the last attempt -- the throttle that
     * coalesces a burst of moves into one write and spaces out retries after a
     * failure. A tick that decides not to write is silent and costs nothing.
     */
    public void tick(long nowMs) {
        if (!isDirty()) {
            return;
        }
        if (nowMs - lastSaveMs < intervalMs) {
            return; // settled not yet; wait for the quiet interval
        }
        attemptSave(nowMs);
    }

    /**
     * Save now, ignoring the throttle -- for a clean shutdown that must not lose
     * the last second of moves. A no-op when nothing is dirty.
     */
    public void flush(long nowMs) {
        if (isDirty()) {
            attemptSave(nowMs);
        }
    }

    private void attemptSave(long nowMs) {
        lastSaveMs = nowMs;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            CellSnapshot.save(model, file);
            savedRevision = model.revision();
        } catch (IOException couldNotWrite) {
            // The world stays dirty (savedRevision untouched), so the next tick
            // retries; an autosave that dies here would silently stop remembering
            // where anything is, which is worse than a noisy retry.
            logger.log(Level.WARNING, "world snapshot not written; will retry: {0}",
                    file);
        }
    }
}
