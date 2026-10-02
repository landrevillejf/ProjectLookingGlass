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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link QuickLaunchModel}: the pin/un-pin/reorder mutations, the
 * de-dup-by-command rule, the {@link QuickLaunchModel#MAX_ENTRIES} cap, the
 * write-through persistence and the change notifications, plus loading (and
 * de-duplicating) a previously saved list. Exercised against an in-memory
 * {@link QuickLaunchStore}, so nothing touches the real user preferences and
 * the whole model runs headless.
 */
class QuickLaunchModelTest {

    private static ItemSpec item(String name, String command) {
        return new ItemSpec(name, command, null, null, null);
    }

    /** The commands currently pinned, in bar order. */
    private static List<String> commands(QuickLaunchModel model) {
        List<String> out = new ArrayList<>();
        for (QuickLaunchEntry entry : model.entries()) {
            out.add(entry.command());
        }
        return out;
    }

    @Test
    @DisplayName("a fresh model over an empty store is empty")
    void emptyByDefault() {
        QuickLaunchModel model = new QuickLaunchModel(new FakeStore());
        assertTrue(model.isEmpty());
        assertEquals(0, model.size());
        assertFalse(model.isSeeded());
    }

    @Test
    @DisplayName("pin appends, persists and reports success")
    void pinAppends() {
        FakeStore store = new FakeStore();
        QuickLaunchModel model = new QuickLaunchModel(store);
        assertTrue(model.pin(item("Mail", "java mail.Mail")));
        assertEquals(List.of("java mail.Mail"), commands(model));
        assertEquals(1, store.saveCount.get(), "a pin writes straight through");
    }

    @Test
    @DisplayName("pinning the same command twice is a no-op the second time")
    void pinDeDuplicatesByCommand() {
        QuickLaunchModel model = new QuickLaunchModel(new FakeStore());
        assertTrue(model.pin(item("Mail", "java mail.Mail")));
        assertFalse(model.pin(item("Mail again", "java mail.Mail")));
        assertEquals(1, model.size());
        assertTrue(model.isPinned("java mail.Mail"));
    }

    @Test
    @DisplayName("a null item or a blank command is never pinned")
    void pinRejectsBlank() {
        QuickLaunchModel model = new QuickLaunchModel(new FakeStore());
        assertFalse(model.pin(null));
        assertFalse(model.pin(item("No command", null)));
        assertFalse(model.pin(item("Blank", "   ")));
        assertTrue(model.isEmpty());
    }

    @Test
    @DisplayName("unpin removes by command and reports whether it did")
    void unpinRemoves() {
        FakeStore store = new FakeStore();
        QuickLaunchModel model = new QuickLaunchModel(store);
        model.pin(item("A", "java a.A"));
        model.pin(item("B", "java b.B"));
        int before = store.saveCount.get();
        assertTrue(model.unpin("java a.A"));
        assertEquals(List.of("java b.B"), commands(model));
        assertFalse(model.unpin("java a.A"));
        assertFalse(model.unpin("not-pinned"));
        assertFalse(model.unpin(null));
        assertEquals(before + 1, store.saveCount.get(),
                "only the successful unpin persists");
    }

    @Test
    @DisplayName("move reorders within the strip and clamps out-of-range targets")
    void moveReorders() {
        QuickLaunchModel model = new QuickLaunchModel(new FakeStore());
        model.pin(item("A", "java a.A"));
        model.pin(item("B", "java b.B"));
        model.pin(item("C", "java c.C"));

        assertTrue(model.move(0, 2));
        assertEquals(List.of("java b.B", "java c.C", "java a.A"), commands(model));
        // Clamped: a target past the end lands on the last slot (a no-op here).
        assertFalse(model.move(2, 99));
        assertEquals(List.of("java b.B", "java c.C", "java a.A"), commands(model));
        // Same index is a no-op.
        assertFalse(model.move(1, 1));
    }

    @Test
    @DisplayName("move on an empty strip is a no-op")
    void moveOnEmpty() {
        QuickLaunchModel model = new QuickLaunchModel(new FakeStore());
        assertFalse(model.move(0, 1));
    }

    @Test
    @DisplayName("the pinned list never grows past MAX_ENTRIES")
    void cappedAtMax() {
        QuickLaunchModel model = new QuickLaunchModel(new FakeStore());
        for (int i = 0; i < QuickLaunchModel.MAX_ENTRIES + 10; i++) {
            model.pin(item("App" + i, "java app.A" + i));
        }
        assertEquals(QuickLaunchModel.MAX_ENTRIES, model.size());
    }

    @Test
    @DisplayName("entries() is an unmodifiable snapshot")
    void entriesAreImmutable() {
        QuickLaunchModel model = new QuickLaunchModel(new FakeStore());
        model.pin(item("A", "java a.A"));
        List<QuickLaunchEntry> view = model.entries();
        assertThrows(UnsupportedOperationException.class,
                () -> view.add(new QuickLaunchEntry("B", "java b.B", null)));
        // Mutating the model afterwards does not change the earlier snapshot.
        model.pin(item("B", "java b.B"));
        assertEquals(1, view.size());
    }

    @Test
    @DisplayName("clear empties the strip and the store")
    void clearEmpties() {
        FakeStore store = new FakeStore();
        QuickLaunchModel model = new QuickLaunchModel(store);
        model.pin(item("A", "java a.A"));
        model.clear();
        assertTrue(model.isEmpty());
        assertTrue(model.entries().isEmpty());
        assertTrue(store.cleared.get() > 0);
    }

    @Test
    @DisplayName("listeners fire on every real mutation, never on a no-op")
    void listenersFire() {
        QuickLaunchModel model = new QuickLaunchModel(new FakeStore());
        AtomicInteger fired = new AtomicInteger();
        Runnable listener = fired::incrementAndGet;
        model.addListener(listener);

        model.pin(item("A", "java a.A"));       // fires
        assertEquals(1, fired.get());
        model.pin(item("A", "java a.A"));       // duplicate: no fire
        assertEquals(1, fired.get());
        model.move(0, 0);                        // no-op: no fire
        assertEquals(1, fired.get());
        model.unpin("java a.A");                 // fires
        assertEquals(2, fired.get());
        model.unpin("java a.A");                 // missing: no fire
        assertEquals(2, fired.get());

        model.removeListener(listener);
        model.pin(item("B", "java b.B"));       // listener gone: no fire
        assertEquals(2, fired.get());
    }

    @Test
    @DisplayName("a null listener is ignored")
    void nullListenerIgnored() {
        QuickLaunchModel model = new QuickLaunchModel(new FakeStore());
        model.addListener(null);
        model.pin(item("A", "java a.A"));       // must not throw
        assertEquals(1, model.size());
    }

    @Test
    @DisplayName("the seeded flag is delegated to the store")
    void seededFlagDelegates() {
        FakeStore store = new FakeStore();
        QuickLaunchModel model = new QuickLaunchModel(store);
        assertFalse(model.isSeeded());
        model.markSeeded();
        assertTrue(model.isSeeded());
        assertTrue(store.seeded);
    }

    @Test
    @DisplayName("a saved list is reloaded, de-duplicated and blank commands dropped")
    void reloadsFromStore() {
        FakeStore store = new FakeStore();
        // A previously persisted list with a duplicate command and a blank one.
        store.saved = QuickLaunchEntry.encodeList(List.of(
                new QuickLaunchEntry("A", "java a.A", "a.png"),
                new QuickLaunchEntry("B", "java b.B", null),
                new QuickLaunchEntry("A dup", "java a.A", "other.png"),
                new QuickLaunchEntry("Blank", "  ", null)));

        QuickLaunchModel model = new QuickLaunchModel(store);
        assertEquals(List.of("java a.A", "java b.B"), commands(model));
        // The first "java a.A" wins; the duplicate is dropped on load.
        assertEquals("A", model.entries().get(0).name());
    }

    /**
     * An in-memory {@link QuickLaunchStore}: keeps the encoded form so a reload
     * exercises the real round trip, and counts writes/clears for assertions.
     */
    private static final class FakeStore implements QuickLaunchStore {
        private final AtomicInteger saveCount = new AtomicInteger();
        private final AtomicInteger cleared = new AtomicInteger();
        private String saved = "";
        private boolean seeded;

        @Override
        public void save(List<QuickLaunchEntry> entries) {
            saveCount.incrementAndGet();
            saved = QuickLaunchEntry.encodeList(entries);
        }

        @Override
        public List<QuickLaunchEntry> load() {
            return QuickLaunchEntry.decodeList(saved);
        }

        @Override
        public void clear() {
            cleared.incrementAndGet();
            saved = "";
        }

        @Override
        public boolean isSeeded() {
            return seeded;
        }

        @Override
        public void markSeeded() {
            seeded = true;
        }
    }
}
