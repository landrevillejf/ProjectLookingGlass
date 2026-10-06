/**
 * Project Looking Glass
 *
 * Copyright (c) 2026 Project Looking Glass contributors.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import java.util.Collections;
import java.util.EnumSet;

/**
 * The pure, X-free decision layer for the EWMH subset the compositor needs
 * (Phase D): {@code _NET_WM_STATE} change application, the default state for a
 * window type, the {@code _NET_WM_ALLOWED_ACTIONS} derivation, and the
 * {@code _NET_ACTIVE_WINDOW} focus-stealing decision.
 *
 * <p>Every method here is a pure function over enums and {@link EnumSet}s — no
 * {@code gnu.x11.Display}, no {@code Atom} ids — so the EWMH semantics are
 * unit-testable headlessly. {@link X11WindowManagerHints} owns the (Display-bound)
 * translation from these enums to atom ids and the {@code change_property}
 * round-trips; this class owns <em>what</em> the answer is.</p>
 *
 * <p>Each {@link State}/{@link Action} constant maps 1:1 to an atom name by the
 * {@link #stateAtomName}/{@link #actionAtomName} convention
 * ({@code "_NET_WM_STATE_" + name()} / {@code "_NET_WM_ACTION_" + name()}), which
 * is exactly the EWMH spelling, so no lookup table is needed.</p>
 *
 * @see X11WindowManagerHints
 */
public final class NetWmState {

    private NetWmState() {
        // pure static utility
    }

    /** The {@code _NET_WM_STATE_*} atoms this WM understands. */
    public enum State {
        MODAL, STICKY, MAXIMIZED_VERT, MAXIMIZED_HORZ, SHADED,
        SKIP_TASKBAR, SKIP_PAGER, HIDDEN, FULLSCREEN,
        ABOVE, BELOW, DEMANDS_ATTENTION
    }

    /** The {@code _NET_WM_ACTION_*} atoms this WM advertises. */
    public enum Action {
        MOVE, RESIZE, MINIMIZE, MAXIMIZE_VERT, MAXIMIZE_HORZ,
        FULLSCREEN, CLOSE, ABOVE, BELOW
    }

    /** The {@code _NET_WM_STATE} client-message action field (EWMH §5.9). */
    public enum ChangeAction {
        /** 0 — remove/unset the property. */
        REMOVE,
        /** 1 — add/set the property. */
        ADD,
        /** 2 — toggle the property. */
        TOGGLE;

        /** The wire value EWMH assigns to each action. */
        public int code() {
            return ordinal();
        }

        /**
         * Resolves a wire action code to its enum.
         *
         * @param code 0/1/2 per EWMH
         * @return the matching action, or {@code null} for an out-of-range code
         */
        public static ChangeAction fromCode(int code) {
            switch (code) {
                case 0: return REMOVE;
                case 1: return ADD;
                case 2: return TOGGLE;
                default: return null;
            }
        }
    }

    /** The {@code _NET_WM_WINDOW_TYPE_*} atoms, minus the bare type key. */
    public enum WindowType {
        NORMAL, DIALOG, SPLASH, DESKTOP, DOCK, TOOLBAR, MENU, UTILITY
    }

    /** How an activation request was initiated (EWMH {@code _NET_ACTIVE_WINDOW}). */
    public enum ActiveSource {
        /** 0 — no source indication; treated like a pager request. */
        NONE,
        /** 1 — the application itself asked (potential focus stealing). */
        APPLICATION,
        /** 2 — a pager/taskbar/user action asked. */
        PAGER;

        /** Resolves the wire source-indication value. */
        public static ActiveSource fromCode(int code) {
            switch (code) {
                case 1: return APPLICATION;
                case 2: return PAGER;
                default: return NONE;
            }
        }
    }

    /** The outcome of an {@code _NET_ACTIVE_WINDOW} request. */
    public enum ActiveDecision {
        /** Give the window the input focus and raise it. */
        ACTIVATE,
        /** Refuse to steal focus; flag the window instead. */
        DEMANDS_ATTENTION
    }

    /** The atom name for a state, e.g. {@code MAXIMIZED_VERT} &rarr; {@code _NET_WM_STATE_MAXIMIZED_VERT}. */
    public static String stateAtomName(State state) {
        return "_NET_WM_STATE_" + state.name();
    }

    /** The atom name for an action, e.g. {@code CLOSE} &rarr; {@code _NET_WM_ACTION_CLOSE}. */
    public static String actionAtomName(Action action) {
        return "_NET_WM_ACTION_" + action.name();
    }

    /**
     * Applies one {@code _NET_WM_STATE} client-message change.
     *
     * @param current the window's current state set (not modified)
     * @param action  REMOVE / ADD / TOGGLE
     * @param state   the state atom the message names
     * @return a new set reflecting the change; {@code current} unchanged if
     *         {@code action} is null
     */
    public static EnumSet<State> applyChange(EnumSet<State> current,
            ChangeAction action, State state) {
        EnumSet<State> next = (current == null)
            ? EnumSet.noneOf(State.class)
            : EnumSet.copyOf(current);
        if (action == null || state == null) {
            return next;
        }
        switch (action) {
            case REMOVE: next.remove(state); break;
            case ADD:    next.add(state);    break;
            case TOGGLE:
                if (next.contains(state)) {
                    next.remove(state);
                } else {
                    next.add(state);
                }
                break;
            default: break;
        }
        return next;
    }

    /**
     * The default {@code _NET_WM_STATE} for a window type. Per EWMH, transient /
     * non-taskbar types (dialog, splash, utility, toolbar, menu) start with
     * {@code SKIP_TASKBAR}; a modal dialog is also {@code MODAL}. Everything else
     * starts with no state.
     *
     * @param type the window type (null is treated as {@link WindowType#NORMAL})
     * @return a fresh, possibly empty, state set
     */
    public static EnumSet<State> defaultStateFor(WindowType type) {
        WindowType t = (type != null) ? type : WindowType.NORMAL;
        EnumSet<State> set = EnumSet.noneOf(State.class);
        switch (t) {
            case DIALOG:
                set.add(State.SKIP_TASKBAR);
                set.add(State.MODAL);
                break;
            case SPLASH:
            case UTILITY:
            case TOOLBAR:
            case MENU:
                set.add(State.SKIP_TASKBAR);
                break;
            default:
                break;
        }
        return set;
    }

    /**
     * Derives the {@code _NET_WM_ALLOWED_ACTIONS} set from a window's state and
     * capabilities, following the EWMH rules:
     * <ul>
     *   <li>{@code CLOSE} iff closable; {@code MOVE} is always offered;</li>
     *   <li>{@code RESIZE} only if resizable and neither maximized nor fullscreen;</li>
     *   <li>{@code MAXIMIZE_VERT/HORZ} only if maximizable and not fullscreen;</li>
     *   <li>{@code MINIMIZE} unless the window skips the taskbar;</li>
     *   <li>{@code FULLSCREEN} iff fullscreen-capable;</li>
     *   <li>{@code ABOVE}/{@code BELOW} are mutually exclusive with the current
     *       state: an ABOVE window is offered BELOW and vice versa, otherwise
     *       both.</li>
     * </ul>
     *
     * @param state            the window's current state (null = empty)
     * @param resizable        whether the client is resizable
     * @param maximizable      whether the client is maximizable
     * @param closable         whether the client accepts a delete/close
     * @param fullscreenCapable whether fullscreen is offered
     * @return the allowed-action set (never null)
     */
    public static EnumSet<Action> allowedActions(EnumSet<State> state,
            boolean resizable, boolean maximizable, boolean closable,
            boolean fullscreenCapable) {
        EnumSet<State> s = (state == null) ? EnumSet.noneOf(State.class) : state;
        EnumSet<Action> actions = EnumSet.noneOf(Action.class);

        boolean maximized = s.contains(State.MAXIMIZED_VERT)
            || s.contains(State.MAXIMIZED_HORZ);
        boolean fullscreen = s.contains(State.FULLSCREEN);

        if (closable) {
            actions.add(Action.CLOSE);
        }
        actions.add(Action.MOVE);
        if (resizable && !maximized && !fullscreen) {
            actions.add(Action.RESIZE);
        }
        if (maximizable && !fullscreen) {
            actions.add(Action.MAXIMIZE_VERT);
            actions.add(Action.MAXIMIZE_HORZ);
        }
        if (!s.contains(State.SKIP_TASKBAR)) {
            actions.add(Action.MINIMIZE);
        }
        if (fullscreenCapable) {
            actions.add(Action.FULLSCREEN);
        }
        if (s.contains(State.ABOVE)) {
            actions.add(Action.BELOW);
        } else if (s.contains(State.BELOW)) {
            actions.add(Action.ABOVE);
        } else {
            actions.add(Action.ABOVE);
            actions.add(Action.BELOW);
        }
        return actions;
    }

    /**
     * Decides how to honour an {@code _NET_ACTIVE_WINDOW} request. A pager/user
     * activation (or an unspecified source) is honoured; an application-initiated
     * activation is <em>not</em> allowed to steal focus and instead flags the
     * window {@code DEMANDS_ATTENTION} (EWMH focus-stealing prevention).
     *
     * @param source the request's source indication
     * @return the decision; never null
     */
    public static ActiveDecision decideActive(ActiveSource source) {
        if (source == ActiveSource.APPLICATION) {
            return ActiveDecision.DEMANDS_ATTENTION;
        }
        return ActiveDecision.ACTIVATE;
    }

    /** An unmodifiable, deterministic (enum-order) view of a state set. */
    public static java.util.Set<State> readOnly(EnumSet<State> state) {
        if (state == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(state);
    }
}
