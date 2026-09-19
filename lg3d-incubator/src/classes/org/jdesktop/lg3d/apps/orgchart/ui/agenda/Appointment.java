/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
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
import java.util.prefs.Preferences;

/**
 * A single user-created agenda entry: a titled block on the week grid that
 * optionally invites one or more contacts from the shared {@code /contacts}
 * store populated by {@code Contact3D}.
 *
 * <p>Appointments are persisted as a {@link Preferences} node under
 * {@link AppointmentStore#ROOT}, keyed by {@link #id}. Attendees are stored as
 * a comma-separated list of contact {@code uid}s so the agenda can be reloaded
 * in a later session and resolved back against the live contact directory.</p>
 */
public class Appointment {

    /** Preference keys. */
    static final String KEY_TITLE = "title";
    static final String KEY_DAY = "day";
    static final String KEY_START = "startHour";
    static final String KEY_DURATION = "durationHours";
    static final String KEY_ATTENDEES = "attendees";

    /** Preset titles cycled by the "Title" button (no free-text input in 3D). */
    static final String[] PRESET_TITLES = {
        "Meeting", "1:1", "Review", "Sync", "Lunch", "Call", "Focus",
        "Appointment"
    };

    private final String id;
    private String title;
    private int day;         // 0 == Monday .. 6 == Sunday
    private int startHour;   // first slot of the block
    private int duration;    // whole hours, >= 1
    private final List<String> attendees = new ArrayList<String>();

    public Appointment(String id, String title, int day, int startHour,
            int duration) {
        this.id = id;
        this.title = title;
        this.day = day;
        this.startHour = startHour;
        this.duration = Math.max(1, duration);
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    /** Cycles to the next preset title (wrapping). */
    public void nextTitle() {
        int idx = 0;
        for (int i = 0; i < PRESET_TITLES.length; i++) {
            if (PRESET_TITLES[i].equals(title)) {
                idx = i;
                break;
            }
        }
        title = PRESET_TITLES[(idx + 1) % PRESET_TITLES.length];
    }

    public int getDay() {
        return day;
    }

    public void setDay(int day) {
        this.day = day;
    }

    public int getStartHour() {
        return startHour;
    }

    public void setStartHour(int startHour) {
        this.startHour = startHour;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = Math.max(1, duration);
    }

    public List<String> getAttendees() {
        return attendees;
    }

    public boolean hasAttendee(String uid) {
        return attendees.contains(uid);
    }

    public void addAttendee(String uid) {
        if (uid != null && !attendees.contains(uid)) {
            attendees.add(uid);
        }
    }

    public void removeAttendee(String uid) {
        attendees.remove(uid);
    }

    /** Writes this appointment into the given (already node-scoped) prefs. */
    void writeTo(Preferences node) {
        node.put(KEY_TITLE, title);
        node.putInt(KEY_DAY, day);
        node.putInt(KEY_START, startHour);
        node.putInt(KEY_DURATION, duration);
        node.put(KEY_ATTENDEES, join(attendees));
    }

    /** Rebuilds an appointment from a prefs node; {@code id} is the node name. */
    static Appointment readFrom(String id, Preferences node) {
        Appointment a = new Appointment(
                id,
                node.get(KEY_TITLE, PRESET_TITLES[0]),
                node.getInt(KEY_DAY, 0),
                node.getInt(KEY_START, AgendaGrid.START_HOUR),
                node.getInt(KEY_DURATION, 1));
        String csv = node.get(KEY_ATTENDEES, "");
        for (String uid : csv.split(",")) {
            String trimmed = uid.trim();
            if (trimmed.length() > 0) {
                a.addAttendee(trimmed);
            }
        }
        return a;
    }

    static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    public String toString() {
        return "[Appointment " + id + " " + title + " day=" + day
                + " " + startHour + "h +" + duration + "h attendees="
                + attendees + "]";
    }
}
