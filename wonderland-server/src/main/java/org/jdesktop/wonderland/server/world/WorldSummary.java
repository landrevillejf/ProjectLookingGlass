/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-server (world library): one row of the world catalog -- what a
 * caller listing "all available worlds" sees. It is deliberately a description
 * of a loadable world, not a loaded one: the catalog never seeds, it only says
 * which worlds exist, their human title/description (read from module.xml, not
 * the directory name), where they come from, and how many cells they carry. The
 * source distinguishes a bundled classpath world (the outpatient clinic) from a
 * stock world jar dropped into the configured worlds directory, so a reader can
 * tell which worlds ship with the build and which the deployment added. The
 * {@code hosted} flag is the one loaded-world fact the catalog carries: the
 * controller sets it on the single entry whose module the live
 * {@link WorldModel} was seeded from, so a browser can tell "listed" from
 * "running" without a second request.
 */
package org.jdesktop.wonderland.server.world;

/** A described, loadable world the server can offer. */
public record WorldSummary(String module, String title, String description,
        String source, int cellCount, boolean hosted) {

    /** The bundled, on-classpath origin (e.g. the outpatient clinic). */
    public static final String SOURCE_CLASSPATH = "classpath";

    /** A world module jar found in the configured worlds directory. */
    public static final String SOURCE_JAR = "jar";

    /** A listed, not-currently-hosted world: what the catalog itself builds. */
    public WorldSummary(String module, String title, String description,
            String source, int cellCount) {
        this(module, title, description, source, cellCount, false);
    }
}
