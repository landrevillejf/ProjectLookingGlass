/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4 (user-list): the present-user roster.
 *
 * The model behind the 2006 user-list window (that window's Swing/3D
 * presentation is deferred to M6/M7). It tracks who is present, keyed by the
 * stable id the session/avatar layer hands in, and notifies join/leave
 * listeners -- the same presence bookkeeping the chat and avatar modules fed.
 */
package org.jdesktop.wonderland.modules.userlist;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A roster of present users keyed by stable id. */
public final class UserList {

    /** Notified as users join and leave. */
    public interface PresenceListener {
        /** @param user the user that just became present */
        void userJoined(PresentUser user);

        /** @param user the user that just left */
        void userLeft(PresentUser user);
    }

    private final Map<String, PresentUser> users = new LinkedHashMap<>();
    private final List<PresenceListener> listeners = new ArrayList<>();

    /**
     * Add or refresh a present user. A repeat id updates the display name but
     * does not fire a second join.
     *
     * @param user the user to mark present
     */
    public void add(PresentUser user) {
        boolean isNew = !users.containsKey(user.getId());
        users.put(user.getId(), user);
        if (isNew) {
            for (PresenceListener listener : snapshot()) {
                listener.userJoined(user);
            }
        }
    }

    /**
     * Remove a user by id; an absent id is a no-op.
     *
     * @param id the user to mark gone
     * @return the removed user, or {@code null} when the id was not present
     */
    public PresentUser remove(String id) {
        PresentUser removed = users.remove(id);
        if (removed != null) {
            for (PresenceListener listener : snapshot()) {
                listener.userLeft(removed);
            }
        }
        return removed;
    }

    /**
     * Look a user up by id.
     *
     * @param id the presence key
     * @return the user, or {@code null} when absent
     */
    public PresentUser find(String id) {
        return users.get(id);
    }

    /** The roster sorted by display name (case-insensitive), as a snapshot. */
    public List<PresentUser> users() {
        List<PresentUser> sorted = new ArrayList<>(users.values());
        sorted.sort(Comparator.comparing(PresentUser::getName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(sorted);
    }

    /** The number of present users. */
    public int size() {
        return users.size();
    }

    /** Register a presence listener. */
    public void addListener(PresenceListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private List<PresenceListener> snapshot() {
        return new ArrayList<>(listeners);
    }
}
