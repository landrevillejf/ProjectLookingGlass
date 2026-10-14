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
 * tell which worlds ship with the build and which the deployment added.
 */
package org.jdesktop.wonderland.server.world;

/** A described, loadable world the server can offer. */
public record WorldSummary(String module, String title, String description,
        String source, int cellCount) {

    /** The bundled, on-classpath origin (e.g. the outpatient clinic). */
    public static final String SOURCE_CLASSPATH = "classpath";

    /** A world module jar found in the configured worlds directory. */
    public static final String SOURCE_JAR = "jar";
}
