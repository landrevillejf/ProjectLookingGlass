/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server P5.4: the running half of the autosave -- the bean that
 * gives the pure WorldAutosaver a real clock and a lifecycle. It is deliberately
 * THIN: every judgement (is the world dirty, has it settled, did the write fail)
 * lives in WorldAutosaver and is unit-tested against an injected instant; this
 * class only reads a Spring-managed one and hooks the container's own start and
 * stop.
 *
 * Off by default. Autosave writes to disk, so it stays inert until an operator
 * names a snapshot file (wonderland.server.snapshot.path); an unset path means
 * no restore, no scheduled beat and no file, exactly the pre-P5.4 server. A
 * torn snapshot on boot is logged and skipped rather than failing the boot --
 * the freshly-seeded module world is a safe place to be, and refusing to start
 * over a memory the operator cannot read is worse than forgetting it aloud.
 */
package org.jdesktop.wonderland.server.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WorldSnapshotService {

    private static final Logger logger = Logger.getLogger(WorldSnapshotService.class.getName());

    private final WorldModel model;
    private final String snapshotPath;
    private final long intervalMs;

    /** null until an operator names a path -- the switch that keeps this inert. */
    private volatile WorldAutosaver autosaver;

    public WorldSnapshotService(WorldModel model,
            @Value("${wonderland.server.snapshot.path:}") String snapshotPath,
            @Value("${wonderland.server.snapshot.interval:30000}") long intervalMs) {
        this.model = model;
        this.snapshotPath = snapshotPath;
        this.intervalMs = intervalMs;
    }

    @PostConstruct
    void start() {
        if (snapshotPath == null || snapshotPath.isBlank()) {
            return; // autosave off: no restore, no beat, no file
        }
        Path file = Path.of(snapshotPath.trim());
        try {
            int restored = CellSnapshot.restore(model, file);
            if (restored > 0) {
                logger.log(Level.INFO, "restored {0} cell placements from {1}",
                        new Object[] {restored, file});
            }
        } catch (IOException unreadable) {
            // A torn or foreign snapshot is forgotten rather than fatal: the world
            // boots from its module art with nothing restored, and the log says so.
            logger.log(Level.WARNING,
                    "world snapshot unreadable, starting from a fresh world: {0}", file);
        }
        // The just-restored placements are a revision bump, so mark them saved now
        // and only rewrite when someone moves something this session.
        autosaver = new WorldAutosaver(model, file, intervalMs);
        autosaver.flush(0L);
    }

    @Scheduled(fixedDelayString = "${wonderland.server.snapshot.interval:30000}")
    void beat() {
        WorldAutosaver saver = autosaver;
        if (saver != null) {
            saver.tick(System.currentTimeMillis());
        }
    }

    @PreDestroy
    void stop() {
        WorldAutosaver saver = autosaver;
        if (saver != null) {
            saver.flush(System.currentTimeMillis());
        }
    }
}
