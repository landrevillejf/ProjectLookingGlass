/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.displayserver.desktop2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure {@link WorkspaceModel} seam headless: count clamping, current
 * workspace switching with wraparound, window assignment/removal, per-workspace
 * filtering and the defensive, unmodifiable view of the assignments. No Swing,
 * no windows and no desktop are involved, so every branch is deterministic.
 */
class WorkspaceModelTest {

    @Test
    @DisplayName("the constructor clamps the count into range and starts on the first")
    void constructorClamps() {
        assertEquals(WorkspaceModel.MIN_COUNT, new WorkspaceModel(0).count());
        assertEquals(WorkspaceModel.MIN_COUNT, new WorkspaceModel(-7).count());
        assertEquals(WorkspaceModel.MAX_COUNT, new WorkspaceModel(99).count());
        assertEquals(4, new WorkspaceModel(4).count());
        assertEquals(0, new WorkspaceModel(4).current(), "a fresh model shows workspace 0");
    }

    @Test
    @DisplayName("clampCount constrains any request into MIN..MAX")
    void clampCountStatic() {
        assertEquals(WorkspaceModel.MIN_COUNT, WorkspaceModel.clampCount(Integer.MIN_VALUE));
        assertEquals(WorkspaceModel.MIN_COUNT, WorkspaceModel.clampCount(0));
        assertEquals(5, WorkspaceModel.clampCount(5));
        assertEquals(WorkspaceModel.MAX_COUNT, WorkspaceModel.clampCount(WorkspaceModel.MAX_COUNT + 1));
        assertEquals(WorkspaceModel.MAX_COUNT, WorkspaceModel.clampCount(Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("switchTo wraps both a too-high and a negative index into range")
    void switchToWraps() {
        WorkspaceModel model = new WorkspaceModel(3);
        assertEquals(2, model.switchTo(5), "5 mod 3 lands on workspace 2");
        assertEquals(2, model.switchTo(-1), "floorMod keeps a negative index in range");
        assertEquals(0, model.switchTo(3), "3 mod 3 wraps back to the first");
        assertEquals(0, model.current());
    }

    @Test
    @DisplayName("next and previous step and wrap around the ends")
    void nextPreviousWrap() {
        WorkspaceModel model = new WorkspaceModel(3);
        assertEquals(1, model.next());
        assertEquals(2, model.next());
        assertEquals(0, model.next(), "next wraps from the last to the first");
        assertEquals(2, model.previous(), "previous wraps from the first to the last");
        assertEquals(1, model.previous());
    }

    @Test
    @DisplayName("a single workspace makes next/previous/switchTo all stay put")
    void singleWorkspaceIsInert() {
        WorkspaceModel model = new WorkspaceModel(1);
        assertEquals(0, model.next());
        assertEquals(0, model.previous());
        assertEquals(0, model.switchTo(4));
        assertEquals(1, model.count());
    }

    @Test
    @DisplayName("setCount clamps and pulls the current index back into range")
    void setCountShrinksCurrent() {
        WorkspaceModel model = new WorkspaceModel(4);
        model.switchTo(3);
        model.setCount(2);
        assertEquals(2, model.count());
        assertEquals(1, model.current(), "the current index is pulled to the new last workspace");
        model.setCount(100);
        assertEquals(WorkspaceModel.MAX_COUNT, model.count());
        model.setCount(0);
        assertEquals(WorkspaceModel.MIN_COUNT, model.count());
    }

    @Test
    @DisplayName("shrinking the count clamps out-of-range assignments rather than dropping them")
    void setCountClampsAssignments() {
        WorkspaceModel model = new WorkspaceModel(4);
        model.assign("far", 3);
        model.assign("near", 0);
        model.setCount(2);
        assertEquals(1, model.workspaceOf("far"), "the stranded window moves to the new last workspace");
        assertEquals(0, model.workspaceOf("near"), "an in-range window is untouched");
    }

    @Test
    @DisplayName("assign ignores a null id and wraps an out-of-range index")
    void assignGuards() {
        WorkspaceModel model = new WorkspaceModel(3);
        model.assign(null, 0);
        assertTrue(model.assignments().isEmpty(), "a null window id is never recorded");
        model.assign("a", 4);
        assertEquals(1, model.workspaceOf("a"), "4 mod 3 lands on workspace 1");
        model.assign("b", -1);
        assertEquals(2, model.workspaceOf("b"), "a negative index wraps with floorMod");
    }

    @Test
    @DisplayName("re-assigning a window moves it instead of duplicating it")
    void assignMovesNotDuplicates() {
        WorkspaceModel model = new WorkspaceModel(3);
        model.assign("a", 0);
        model.assign("a", 2);
        assertEquals(2, model.workspaceOf("a"));
        assertEquals(1, model.assignments().size(), "the window is recorded once");
    }

    @Test
    @DisplayName("unassign removes a window and is a safe no-op for null or unknown ids")
    void unassign() {
        WorkspaceModel model = new WorkspaceModel(3);
        model.assign("a", 0);
        model.assign("b", 1);
        model.unassign(null);
        model.unassign("never-assigned");
        assertEquals(2, model.assignments().size(), "unknown and null removals change nothing");
        model.unassign("a");
        assertEquals(-1, model.workspaceOf("a"), "a removed window is no longer assigned");
        assertEquals(1, model.assignments().size());
    }

    @Test
    @DisplayName("workspaceOf reports -1 for an unknown or null id")
    void workspaceOfUnknown() {
        WorkspaceModel model = new WorkspaceModel(3);
        assertEquals(-1, model.workspaceOf("ghost"));
        assertEquals(-1, model.workspaceOf(null));
    }

    @Test
    @DisplayName("isOnCurrent tracks the current workspace as it changes")
    void isOnCurrent() {
        WorkspaceModel model = new WorkspaceModel(3);
        model.assign("a", 0);
        assertTrue(model.isOnCurrent("a"));
        model.switchTo(1);
        assertFalse(model.isOnCurrent("a"), "a window on workspace 0 is not on the now-current 1");
        assertFalse(model.isOnCurrent("ghost"), "an unassigned window is on no workspace");
    }

    @Test
    @DisplayName("windowsOn returns the workspace's ids in assignment order and wraps the index")
    void windowsOnOrdersAndWraps() {
        WorkspaceModel model = new WorkspaceModel(3);
        model.assign("a", 0);
        model.assign("b", 1);
        model.assign("c", 0);
        assertEquals(List.of("a", "c"), List.copyOf(model.windowsOn(0)),
                "assignment order is preserved, not sorted");
        assertEquals(List.of("b"), List.copyOf(model.windowsOn(1)));
        assertEquals(List.of("a", "c"), List.copyOf(model.windowsOn(3)), "index 3 wraps to workspace 0");
    }

    @Test
    @DisplayName("an empty workspace reports no windows and a zero count")
    void windowsOnEmpty() {
        WorkspaceModel model = new WorkspaceModel(3);
        model.assign("a", 0);
        assertTrue(model.windowsOn(2).isEmpty());
        assertEquals(0, model.countOn(2));
        assertEquals(1, model.countOn(0));
    }

    @Test
    @DisplayName("countOn counts each workspace's windows")
    void countOn() {
        WorkspaceModel model = new WorkspaceModel(3);
        model.assign("a", 0);
        model.assign("b", 0);
        model.assign("c", 1);
        assertEquals(2, model.countOn(0));
        assertEquals(1, model.countOn(1));
        assertEquals(0, model.countOn(2));
    }

    @Test
    @DisplayName("assignments() is an unmodifiable defensive copy")
    void assignmentsAreUnmodifiableCopy() {
        WorkspaceModel model = new WorkspaceModel(3);
        model.assign("a", 0);
        var snapshot = model.assignments();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", 1));
        model.assign("b", 1);
        assertEquals(1, snapshot.size(), "the earlier snapshot does not track later changes");
        assertEquals(2, model.assignments().size());
    }
}
