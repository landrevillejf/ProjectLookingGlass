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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jdesktop.lg3d.displayserver.desktop2d.WorkspaceModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link WorkspaceRegistry}: the pure "a window is visible
 * exactly when it sits on the current workspace" rule, the wrapping switch, the
 * move-to-workspace hand-off, listener notification and the teardown path. Sinks
 * are recording lambdas, so no {@code Frame3D} and no Java 3D is touched — the
 * scene-graph glue in {@link WorkspacePlugin} is probe-verified separately.
 */
class WorkspaceRegistryTest {

    /** Records every visibility request instead of touching a scene graph. */
    private static final class RecordingSink implements WorkspaceRegistry.VisibilitySink {
        final List<Boolean> calls = new ArrayList<>();
        boolean visible = true;

        @Override
        public void setVisible(boolean v) {
            calls.add(v);
            visible = v;
        }
    }

    /** A sink that always throws, to prove one bad window cannot strand the rest. */
    private static final class ThrowingSink implements WorkspaceRegistry.VisibilitySink {
        int attempts;

        @Override
        public void setVisible(boolean v) {
            attempts++;
            throw new IllegalStateException("sink is broken");
        }
    }

    private static RecordingSink register(WorkspaceRegistry r, String id) {
        RecordingSink sink = new RecordingSink();
        assertTrue(r.register(id, sink), "register should accept " + id);
        return sink;
    }

    @Test
    @DisplayName("a fresh registry shows the first of the default workspaces")
    void defaultsToTheSharedWorkspaceCount() {
        WorkspaceRegistry registry = new WorkspaceRegistry();
        assertEquals(WorkspaceModel.DEFAULT_COUNT, registry.count());
        assertEquals(0, registry.current());
        assertEquals(0, registry.windowCount());
        assertTrue(registry.windowIds().isEmpty());
    }

    @Test
    @DisplayName("the requested count is clamped by the model, never invented")
    void countIsClamped() {
        assertEquals(WorkspaceModel.MAX_COUNT,
                new WorkspaceRegistry(WorkspaceModel.MAX_COUNT * 10).count());
        assertEquals(WorkspaceModel.MIN_COUNT, new WorkspaceRegistry(0).count());
    }

    @Test
    @DisplayName("a window id is the frame name made unique by identity hash")
    void windowIdIsStableAndUnique() {
        assertEquals("Lg3dHelp#1a2b3c", WorkspaceRegistry.windowId("Lg3dHelp", 0x1a2b3c));
        // The same app opened twice must not collide.
        assertFalse(WorkspaceRegistry.windowId("App", 1).equals(WorkspaceRegistry.windowId("App", 2)));
        // A missing or blank name still yields a usable id.
        assertEquals("Frame3D#7", WorkspaceRegistry.windowId(null, 7));
        assertEquals("Frame3D#7", WorkspaceRegistry.windowId("   ", 7));
        // Surrounding whitespace is trimmed so the id stays readable.
        assertEquals("App#7", WorkspaceRegistry.windowId("  App  ", 7));
    }

    @Test
    @DisplayName("registering puts the window on this workspace and shows it")
    void registerAssignsToCurrentAndShows() {
        WorkspaceRegistry registry = new WorkspaceRegistry(3);
        registry.switchTo(2);
        RecordingSink sink = register(registry, "a");

        assertEquals(2, registry.workspaceOf("a"));
        assertTrue(sink.visible);
        assertEquals(1, registry.windowCount());
        assertEquals(Set.of("a"), registry.windowIds());
        assertEquals(1, registry.countOn(2));
        assertEquals(0, registry.countOn(0));
    }

    @Test
    @DisplayName("registering rejects a null/empty id or a null sink")
    void registerRejectsBadArguments() {
        WorkspaceRegistry registry = new WorkspaceRegistry(2);
        RecordingSink sink = new RecordingSink();

        assertFalse(registry.register(null, sink));
        assertFalse(registry.register("", sink));
        assertFalse(registry.register("a", null));
        assertEquals(0, registry.windowCount());
    }

    @Test
    @DisplayName("re-registering the same id moves it here rather than duplicating")
    void registerAgainReassigns() {
        WorkspaceRegistry registry = new WorkspaceRegistry(3);
        register(registry, "a");
        registry.switchTo(1);
        RecordingSink replacement = new RecordingSink();
        assertTrue(registry.register("a", replacement));

        assertEquals(1, registry.windowCount());
        assertEquals(1, registry.workspaceOf("a"));
        assertEquals(1, registry.countOn(1));
        assertTrue(replacement.visible);
    }

    @Test
    @DisplayName("unregistering forgets the window without hiding it")
    void unregisterDropsTheSink() {
        WorkspaceRegistry registry = new WorkspaceRegistry(2);
        RecordingSink sink = register(registry, "a");
        int callsBefore = sink.calls.size();

        assertTrue(registry.unregister("a"));
        assertEquals(-1, registry.workspaceOf("a"));
        assertEquals(0, registry.windowCount());
        assertEquals(callsBefore, sink.calls.size(), "a closing window must not be hidden");

        assertFalse(registry.unregister("a"), "already gone");
        assertFalse(registry.unregister(null));
    }

    @Test
    @DisplayName("switching shows the target workspace and hides the one left")
    void switchToAppliesTheVisibilityRule() {
        WorkspaceRegistry registry = new WorkspaceRegistry(3);
        RecordingSink a = register(registry, "a");
        registry.switchTo(1);
        RecordingSink b = register(registry, "b");

        assertEquals(2, registry.switchTo(2));
        assertFalse(a.visible, "left behind on workspace 0");
        assertFalse(b.visible, "left behind on workspace 1");

        registry.switchTo(0);
        assertTrue(a.visible);
        assertFalse(b.visible);

        registry.switchTo(1);
        assertFalse(a.visible);
        assertTrue(b.visible);
    }

    @Test
    @DisplayName("next and previous wrap instead of running off the end")
    void steppingWraps() {
        WorkspaceRegistry registry = new WorkspaceRegistry(3);
        assertEquals(1, registry.next());
        assertEquals(2, registry.next());
        assertEquals(0, registry.next(), "wraps to the first");
        assertEquals(2, registry.previous(), "wraps to the last");
        assertEquals(1, registry.previous());
    }

    @Test
    @DisplayName("an out-of-range switch is wrapped by the model, never invented")
    void switchToWrapsOutOfRange() {
        WorkspaceRegistry registry = new WorkspaceRegistry(4);
        assertEquals(1, registry.switchTo(5));
        assertEquals(3, registry.switchTo(-1));
    }

    @Test
    @DisplayName("moving a window to another workspace hides it from this one")
    void moveToHidesTheMovedWindow() {
        WorkspaceRegistry registry = new WorkspaceRegistry(3);
        RecordingSink a = register(registry, "a");
        RecordingSink b = register(registry, "b");

        registry.moveTo("a", 2);
        assertFalse(a.visible, "moved away from the current workspace");
        assertTrue(b.visible, "untouched");
        assertEquals(2, registry.workspaceOf("a"));
        assertEquals(1, registry.countOn(2));

        registry.switchTo(2);
        assertTrue(a.visible);
        assertFalse(b.visible);
    }

    @Test
    @DisplayName("moving an unknown or null window is ignored")
    void moveToRejectsUnknownWindows() {
        WorkspaceRegistry registry = new WorkspaceRegistry(3);
        RecordingSink a = register(registry, "a");

        registry.moveTo("nope", 2);
        registry.moveTo(null, 2);
        assertEquals(0, registry.workspaceOf("a"));
        assertTrue(a.visible);
    }

    @Test
    @DisplayName("isOnCurrent only reports registered windows")
    void isOnCurrentRequiresRegistration() {
        WorkspaceRegistry registry = new WorkspaceRegistry(2);
        register(registry, "a");
        assertTrue(registry.isOnCurrent("a"));
        assertFalse(registry.isOnCurrent("unknown"));
        assertFalse(registry.isOnCurrent(null));
    }

    @Test
    @DisplayName("applyVisibility is idempotent and re-asserts the rule")
    void applyVisibilityIsIdempotent() {
        WorkspaceRegistry registry = new WorkspaceRegistry(3);
        RecordingSink a = register(registry, "a");
        registry.switchTo(1);
        assertFalse(a.visible);

        int before = a.calls.size();
        registry.applyVisibility();
        registry.applyVisibility();
        assertFalse(a.visible, "the rule is re-asserted, not flipped");
        assertEquals(before + 2, a.calls.size(), "each pass touches every window once");
    }

    @Test
    @DisplayName("a throwing sink is contained and the rest still reconcile")
    void throwingSinkDoesNotStrandOtherWindows() {
        WorkspaceRegistry registry = new WorkspaceRegistry(2);
        ThrowingSink bad = new ThrowingSink();
        assertTrue(registry.register("bad", bad));
        RecordingSink good = register(registry, "good");

        registry.switchTo(1);
        assertTrue(bad.attempts > 0, "the broken sink was still attempted");
        assertFalse(good.visible, "the healthy window was reconciled anyway");
    }

    @Test
    @DisplayName("listeners hear every change, are de-duplicated and are contained")
    void listenersAreNotified() {
        WorkspaceRegistry registry = new WorkspaceRegistry(3);
        List<String> heard = new ArrayList<>();
        WorkspaceRegistry.Listener listener = () -> heard.add("tick");
        WorkspaceRegistry.Listener throwing = () -> {
            throw new IllegalStateException("listener is broken");
        };

        registry.addListener(listener);
        registry.addListener(listener);
        registry.addListener(throwing);
        registry.addListener(null);

        register(registry, "a");
        assertEquals(1, heard.size(), "duplicate registration must not double-notify");
        registry.next();
        assertEquals(2, heard.size());
        registry.unregister("a");
        assertEquals(3, heard.size());

        registry.removeListener(listener);
        registry.next();
        assertEquals(3, heard.size(), "a removed listener is silent");
        registry.removeListener(null);
    }

    @Test
    @DisplayName("releaseAll shows every window again and empties the registry")
    void releaseAllRestoresASingleWorkspace() {
        WorkspaceRegistry registry = new WorkspaceRegistry(3);
        RecordingSink a = register(registry, "a");
        registry.switchTo(1);
        RecordingSink b = register(registry, "b");
        assertFalse(a.visible);

        registry.releaseAll();
        assertTrue(a.visible, "a hidden window must not stay hidden after teardown");
        assertTrue(b.visible);
        assertEquals(0, registry.windowCount());
        assertEquals(0, registry.current());
        assertEquals(-1, registry.workspaceOf("a"));
        assertEquals(0, registry.countOn(1));

        // A second teardown on an empty registry is a harmless no-op.
        registry.releaseAll();
        assertEquals(0, registry.windowCount());
    }

    @Test
    @DisplayName("the registry survives being used with a single workspace")
    void singleWorkspaceDisablesPaging() {
        WorkspaceRegistry registry = new WorkspaceRegistry(1);
        RecordingSink a = register(registry, "a");

        assertEquals(0, registry.next());
        assertEquals(0, registry.previous());
        assertEquals(0, registry.switchTo(7));
        assertTrue(a.visible, "the only workspace is always the current one");
    }
}
