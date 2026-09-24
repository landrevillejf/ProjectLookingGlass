/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.orgchart.ui.agenda;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persists the user-created agenda to the {@link Preferences} tree, mirroring
 * the way {@code Contact3D} stores contacts under {@code /contacts}. Each
 * appointment is a child node of {@link #ROOT} named by its id.
 *
 * <p>The store is deliberately independent of the per-app
 * {@code ServiceContext}: both {@code Agenda3D} and {@code Contact3D} run in
 * the same JVM and share the same user {@code Preferences} root, so the agenda
 * written here survives across launches and sits beside the shared contact
 * data the agenda invites from.</p>
 */
public class AppointmentStore {

    /** Absolute user-preferences path holding one child node per appointment. */
    public static final String ROOT = "/agenda/appointments";

    private static final Logger logger
            = Logger.getLogger(AppointmentStore.class.getName());

    private final Preferences root;

    public AppointmentStore() {
        this.root = Preferences.userRoot().node(ROOT);
    }

    /** Loads every persisted appointment, ordered by id for stable display. */
    public List<Appointment> load() {
        List<Appointment> list = new ArrayList<Appointment>();
        try {
            String[] ids = root.childrenNames();
            java.util.Arrays.sort(ids);
            for (String id : ids) {
                list.add(Appointment.readFrom(id, root.node(id)));
            }
        } catch (BackingStoreException e) {
            logger.log(Level.WARNING, "Error loading agenda from " + ROOT, e);
        }
        return list;
    }

    /** Writes (or rewrites) a single appointment and flushes it to disk. */
    public void save(Appointment a) {
        try {
            a.writeTo(root.node(a.getId()));
            root.flush();
        } catch (BackingStoreException e) {
            logger.log(Level.WARNING, "Error saving appointment " + a.getId(), e);
        }
    }

    /** Removes a single appointment node. */
    public void delete(String id) {
        try {
            if (root.nodeExists(id)) {
                root.node(id).removeNode();
                root.flush();
            }
        } catch (BackingStoreException e) {
            logger.log(Level.WARNING, "Error deleting appointment " + id, e);
        }
    }

    /** Generates a unique, sort-stable id for a new appointment. */
    public String newId() {
        String base = String.format("%013d", System.currentTimeMillis());
        String id = base;
        int n = 0;
        try {
            while (root.nodeExists(id)) {
                id = base + "-" + (n++);
            }
        } catch (BackingStoreException e) {
            logger.log(Level.WARNING, "Error checking id availability", e);
        }
        return id;
    }
}
