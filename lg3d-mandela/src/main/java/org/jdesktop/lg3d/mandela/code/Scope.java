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
package org.jdesktop.lg3d.mandela.code;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One function's compile-time scope: slot allocation, block structure, the
 * capture table, and the builder the body is being written into.
 *
 * <p>The compiler resolves a name by asking the innermost {@code Scope} first,
 * then walking outward through the enclosing function scopes, then the globals.
 * All of the bookkeeping for that walk lives here, so {@link Compiler} stays
 * concerned with emission rather than with slot arithmetic.</p>
 *
 * <p><b>Slot reuse.</b> A block releases its slots when it ends, which keeps a
 * frame small no matter how many blocks a function contains. One exception: a
 * slot that was captured lives as long as the closure that reads it, so the
 * reclamation watermark stops at the first captured slot of the block. Capturing
 * is therefore the only thing that costs a script an extra word per frame.</p>
 */
final class Scope {

    /**
     * A binding in this function.
     *
     * @param name    the declared name
     * @param slot    the frame slot
     * @param mutable false for {@code let}, which the compiler enforces on assignment
     * @param boxed   true when a closure captures it, so the slot holds a cell
     * @param param   true when it came from the parameter list
     */
    record Binding(String name, int slot, boolean mutable, boolean boxed, boolean param) { }

    /**
     * A variable this function reads from an enclosing one.
     *
     * @param name     the referenced name
     * @param fromLocal true when the owner function holds it in a slot, false
     *                  when the owner function itself captured it
     * @param index    the owner's slot index or its upvalue index
     */
    record Capture(String name, boolean fromLocal, int index) { }

    final CompiledFunction.Builder builder;
    final Scope parent;
    final boolean isMethod;

    private final List<Map<String, Binding>> blocks = new ArrayList<>();
    private final List<Capture> captures = new ArrayList<>();

    private int nextSlot;
    private int maxSlots;
    private int tryDepth;

    /**
     * Opens a function scope.
     *
     * @param parent    the enclosing function's scope, or null at the top level
     * @param name      the function name for diagnostics
     * @param source    the script name
     * @param line      the declaration line
     * @param method    true for a method or constructor, which reserves slot 0 for
     *                  {@code this} and slot 1 for the inherited member view
     */
    Scope(Scope parent, String name, String source, int line, boolean method) {
        this.parent = parent;
        this.isMethod = method;
        this.builder = new CompiledFunction.Builder(name, source, line);
        this.blocks.add(new HashMap<>());
        // Slot 0 and 1 belong to the machine, not to the source: a method frame
        // always has its receiver and its base class, whether or not the body
        // mentions them, so parameter slots start at 2.
        this.nextSlot = method ? 2 : 0;
        this.maxSlots = this.nextSlot;
        if (method) {
            this.builder.markMethod();
        }
    }

    /** The hidden name under which a method frame's receiver is bound. */
    static final String THIS = "\u0000this";

    /** The hidden name under which a method frame's base class is bound. */
    static final String SUPER = "\u0000super";

    /** Binds the two machine slots of a method frame. */
    void bindReceiver() {
        bindTo(THIS, 0, false);
        bindTo(SUPER, 1, false);
    }

    // -- slots --------------------------------------------------------------

    /** @return the slot index the next binding will take */
    int nextSlot() {
        return nextSlot;
    }

    /**
     * @return the high-water mark of slot use, which is the frame size the
     *         machine must allocate even though blocks gave their slots back
     */
    int slotsNeeded() {
        return maxSlots;
    }

    /** Reserves {@code count} slots without naming them, for temporaries. */
    int allocateTemp(int count) {
        int at = nextSlot;
        nextSlot += count;
        if (nextSlot > maxSlots) {
            maxSlots = nextSlot;
        }
        return at;
    }

    /** Declares {@code name} in the innermost block and returns its slot. */
    int declare(String name, boolean mutable, boolean param) {
        int slot = nextSlot++;
        if (nextSlot > maxSlots) {
            maxSlots = nextSlot;
        }
        blocks.get(blocks.size() - 1).put(name,
                new Binding(name, slot, mutable, false, param));
        return slot;
    }

    /** Binds an existing slot to a name, used by destructuring and loop patterns. */
    void bindTo(String name, int slot, boolean mutable) {
        blocks.get(blocks.size() - 1).put(name,
                new Binding(name, slot, mutable, false, false));
    }

    /**
     * Looks a name up in this function only, innermost block first.
     *
     * @param name the identifier
     * @return the binding, or null when this function does not bind it
     */
    Binding find(String name) {
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Binding found = blocks.get(i).get(name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** @return true when this block level already binds {@code name} */
    boolean shadows(String name) {
        return !blocks.isEmpty() && blocks.get(blocks.size() - 1).containsKey(name);
    }

    /** Opens a nested block level; pair it with {@link #endBlock(int)}. */
    int beginBlock() {
        blocks.add(new HashMap<>());
        return nextSlot;
    }

    /**
     * Closes the block level opened at {@code mark} and reclaims its slots.
     * The watermark stops in front of the first captured slot of the level, so a
     * variable a closure still reads is never handed out twice.
     */
    void endBlock(int mark) {
        blocks.remove(blocks.size() - 1);
        int firstLive = Integer.MAX_VALUE;
        for (Map<String, Binding> level : blocks) {
            for (Binding b : level.values()) {
                if (b.boxed() && b.slot() >= mark && b.slot() < firstLive) {
                    firstLive = b.slot();
                }
            }
        }
        int watermark = (firstLive == Integer.MAX_VALUE) ? mark : firstLive;
        nextSlot = Math.min(nextSlot, watermark);
    }

    // -- captures -----------------------------------------------------------

    /**
     * Marks a slot as captured, so the frame stores a cell there, and rewrites
     * the instructions already emitted for it.
     *
     * @param slot the local's frame slot
     * @return true when this call is the one that boxed it
     */
    boolean box(int slot) {
        boolean changed = false;
        for (int i = 0; i < blocks.size(); i++) {
            for (Binding b : new ArrayList<>(blocks.get(i).values())) {
                if (b.slot() == slot && !b.boxed()) {
                    blocks.get(i).put(b.name(),
                            new Binding(b.name(), slot, b.mutable(), true, b.param()));
                    changed = true;
                }
            }
        }
        if (changed) {
            builder.boxSlot(slot);
        }
        return changed;
    }

    /** @return true when a binding of this function is already captured */
    boolean isBoxed(String name) {
        Binding b = find(name);
        return b != null && b.boxed();
    }

    /** @return the slot of {@code name} if this function binds it, else -1 */
    int slotOf(String name) {
        Binding b = find(name);
        return b == null ? -1 : b.slot();
    }

    /** @return the mutability of a binding, true when {@code name} is a {@code let} */
    boolean isImmutable(String name) {
        Binding b = find(name);
        return b != null && !b.mutable();
    }

    /**
     * Returns the capture index for {@code name}, adding the entry when this is
     * the first reference.
     *
     * @param name      the referenced variable
     * @param fromLocal true when the owner function holds it in a slot
     * @param index     the owner's slot or upvalue index
     * @return this function's upvalue index
     */
    int capture(String name, boolean fromLocal, int index) {
        for (int i = 0; i < captures.size(); i++) {
            if (captures.get(i).name().equals(name)) {
                return i;
            }
        }
        captures.add(new Capture(name, fromLocal, index));
        return captures.size() - 1;
    }

    /** @return this function's capture list, in upvalue index order */
    List<Capture> captures() {
        return captures;
    }

    /** @return the number of captures taken by closures made here */
    int captureCount() {
        return captures.size();
    }

    // -- frame --------------------------------------------------------------

    /** Records the try scopes currently open, for {@code break} and {@code return}. */
    int tryDepth() {
        return tryDepth;
    }

    /** @return the try-depth delta a jump out of a loop must unwind */
    int handlerDrop(int loopTryDepth) {
        return Math.max(0, tryDepth - loopTryDepth);
    }

    void enterTry() {
        tryDepth++;
    }

    void leaveTryScope() {
        tryDepth = Math.max(0, tryDepth - 1);
    }

    /** Writes the frame shape into the builder and returns the finished body. */
    CompiledFunction finish(int locals) {
        builder.frame(locals, captures.size());
        return builder.build();
    }
}
