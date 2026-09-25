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
package org.jdesktop.lg3d.scenemanager.utils.workspace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.lg3d.displayserver.desktop2d.WorkspaceModel;

/**
 * The native 3D desktop's workspace bookkeeping: the pure mapping from a window
 * id to "is this window shown right now", built on the desktop-agnostic
 * {@link WorkspaceModel} the 2D/Swing desktop already uses.
 *
 * <p>The registry holds no Java 3D and no Swing. A window is registered under a
 * stable string id together with a {@link VisibilitySink} — in the live desktop
 * that sink is {@code frame3d::setVisible}, so this class stays headless-testable
 * while the {@link WorkspacePlugin} keeps only the scene-graph glue. The rule is
 * a single sentence: <em>a registered window is visible exactly when it sits on
 * the current workspace</em>. Every mutator re-applies that rule, so a switch can
 * never leave a window shown on two workspaces or hidden on the one being
 * entered.</p>
 *
 * <p>Workspaces here are a <em>partition of the windows of the single
 * {@code StandardAppContainer}</em>, not multiple app containers. The
 * multi-container plumbing in {@code GlassySceneManager} was evaluated and
 * rejected: {@code SceneControl.setCurrentAppContainer} deliberately does not
 * add the container to the scene graph (its own comment says the caller must),
 * {@code AppContainer.setEnabled} only flips a flag, lazily-created container
 * slots can still be {@code null} when {@code setCurrentAppContainer} iterates
 * them, and — decisively — {@code StandardAppContainer.initialize()} registers
 * <em>global</em> {@code Frame3D} listeners (a to-front handler that migrates the
 * frame into <em>that</em> container's main layout) whose own comment warns that
 * instantiating the container more than once duplicates those actions. A second
 * workspace container would therefore steal frames back into the first.</p>
 *
 * <p>Indexes wrap and counts clamp exactly as {@link WorkspaceModel} defines, so
 * no call here can select a workspace that does not exist.</p>
 */
public final class WorkspaceRegistry {

    private static final Logger logger = Logger.getLogger("lg.scenemanager.workspace");

    /**
     * Shows or hides one registered window. The live desktop passes
     * {@code frame3d::setVisible}; tests pass a recording lambda. Implementations
     * should not throw — a throw is logged and the remaining windows are still
     * reconciled, so one broken window cannot strand the others.
     */
    public interface VisibilitySink {
        void setVisible(boolean visible);
    }

    /**
     * Notified after any change a pager should reflect: a workspace switch, or a
     * window joining/leaving/moving. Listeners re-read {@link #current()},
     * {@link #count()} and {@link #countOn(int)} rather than receiving a payload.
     */
    public interface Listener {
        void workspaceChanged();
    }

    private final WorkspaceModel model;
    private final Map<String, VisibilitySink> sinks = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Builds a registry with {@link WorkspaceModel#DEFAULT_COUNT} workspaces. */
    public WorkspaceRegistry() {
        this(WorkspaceModel.DEFAULT_COUNT);
    }

    /** Builds a registry with {@code count} workspaces (clamped by the model). */
    public WorkspaceRegistry(int count) {
        this.model = new WorkspaceModel(count);
    }

    /**
     * The stable window id for a scene-graph frame: its name, made unique by an
     * identity hash so two instances of the same app never collide. The 2D desktop
     * can key its model on {@code Desktop2DWindow.getAppName()} because that is
     * already unique among open windows; a {@code Frame3D} name is not, so the
     * caller passes {@code System.identityHashCode(frame)}. Pure, so it is testable
     * without constructing any Java 3D object.
     */
    public static String windowId(String name, int identityHash) {
        String base = (name == null || name.isBlank()) ? "Frame3D" : name.trim();
        return base + '#' + Integer.toHexString(identityHash);
    }

    /** How many workspaces there are. */
    public int count() {
        return model.count();
    }

    /** The index of the workspace currently shown. */
    public int current() {
        return model.current();
    }

    /**
     * Registers {@code windowId} on the current workspace and shows it. A null or
     * empty id, or a null sink, is ignored and returns false; re-registering the
     * same id replaces the sink and re-assigns the window to the current
     * workspace.
     */
    public boolean register(String windowId, VisibilitySink sink) {
        if (windowId == null || windowId.isEmpty() || sink == null) {
            return false;
        }
        sinks.put(windowId, sink);
        model.assign(windowId, model.current());
        apply(windowId, true);
        fireChanged();
        return true;
    }

    /**
     * Forgets {@code windowId} (its window closed). Returns true when it was
     * registered. The sink is dropped without being called: the window that owned
     * it no longer exists, so there is nothing to hide.
     */
    public boolean unregister(String windowId) {
        if (windowId == null) {
            return false;
        }
        VisibilitySink removed = sinks.remove(windowId);
        model.unassign(windowId);
        if (removed == null) {
            return false;
        }
        fireChanged();
        return true;
    }

    /**
     * Shows the workspace at {@code index} (wrapped) and reconciles every
     * registered window against it. Returns the new current index.
     */
    public int switchTo(int index) {
        int now = model.switchTo(index);
        applyVisibility();
        fireChanged();
        return now;
    }

    /** Shows the next workspace, wrapping to the first. Returns its index. */
    public int next() {
        int now = model.next();
        applyVisibility();
        fireChanged();
        return now;
    }

    /** Shows the previous workspace, wrapping to the last. Returns its index. */
    public int previous() {
        int now = model.previous();
        applyVisibility();
        fireChanged();
        return now;
    }

    /**
     * Moves {@code windowId} to the workspace at {@code index} (wrapped) and
     * reconciles visibility — so moving a window to another workspace hides it
     * from this one, exactly as the 2D desktop's "move to workspace" does.
     * Unknown or null ids are ignored.
     */
    public void moveTo(String windowId, int index) {
        if (windowId == null || !sinks.containsKey(windowId)) {
            return;
        }
        model.assign(windowId, index);
        applyVisibility();
        fireChanged();
    }

    /** The workspace {@code windowId} is on, or -1 when it is not registered. */
    public int workspaceOf(String windowId) {
        return model.workspaceOf(windowId);
    }

    /** True when {@code windowId} is registered and shown on this workspace. */
    public boolean isOnCurrent(String windowId) {
        return sinks.containsKey(windowId) && model.isOnCurrent(windowId);
    }

    /** How many registered windows sit on the workspace at {@code index}. */
    public int countOn(int index) {
        return model.countOn(index);
    }

    /** Every registered window id, in registration order. Never null. */
    public Set<String> windowIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(sinks.keySet()));
    }

    /** How many windows are registered in total. */
    public int windowCount() {
        return sinks.size();
    }

    /**
     * Re-applies the visibility rule to every registered window. Idempotent; a
     * sink that throws is logged and skipped so the rest still reconcile.
     */
    public void applyVisibility() {
        int current = model.current();
        for (Map.Entry<String, VisibilitySink> entry : sinks.entrySet()) {
            apply(entry.getKey(), model.workspaceOf(entry.getKey()) == current);
        }
    }

    /**
     * Shows every registered window and forgets them all — the shutdown path. A
     * plugin that simply dropped its registry would leave the windows of every
     * other workspace invisible with nothing left to show them again, so teardown
     * must always restore the desktop to a single-workspace state first.
     */
    public void releaseAll() {
        for (String windowId : sinks.keySet()) {
            apply(windowId, true);
            model.unassign(windowId);
        }
        sinks.clear();
        model.switchTo(0);
        fireChanged();
    }

    private void apply(String windowId, boolean visible) {
        VisibilitySink sink = sinks.get(windowId);
        if (sink == null) {
            return;
        }
        try {
            sink.setVisible(visible);
        } catch (Throwable t) {
            logger.log(Level.WARNING,
                    "workspace visibility sink failed for window: " + windowId, t);
        }
    }

    /** Adds a pager/observer. Duplicate additions are ignored. */
    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /** Removes a pager/observer. */
    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void fireChanged() {
        for (Listener listener : listeners) {
            try {
                listener.workspaceChanged();
            } catch (Throwable t) {
                logger.log(Level.WARNING, "workspace listener failed", t);
            }
        }
    }
}
