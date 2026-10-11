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
package org.jdesktop.lg3d.mandela;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Closures that reach past the function that made them.
 *
 * <p>A machine reaches a captured variable through the frame that created the
 * closure, so a name owned by a grandparent has to be handed down one function at a
 * time: the child of the owner captures the owner's boxed slot, and every function
 * deeper re-captures its own parent's cell. Getting that wrong is not a wrong answer
 * but a crash, which is why a script could only ever hit it at run time — a tool
 * written one level deep works, one written two levels deep dies. These cases are the
 * shapes a host script grows into when a builder function starts delegating.</p>
 *
 * <p>The last case pins the documented exception: Mandela shares a cell rather than
 * copying the value, so closures made in the same loop see one variable, exactly as
 * the guide says (see <code>docs/mandela-language.md</code>, "Closures capture
 * variables by reference"). It is written here because a future "fix" to that would
 * silently change every script that leans on it.</p>
 */
class ClosureCaptureTest {

    @Test
    @DisplayName("a closure reaches a local owned by its grandparent")
    void capturesFromGrandparent() {
        // 1 + 2 + 2: two bumps made inside mid, read back from outer, so the cell the
        // closure holds is outer's and not a copy taken when mid ran.
        assertEquals("5", Scripts.display("""
                fun outer() -> Int {
                  var n = 1
                  fun mid() -> Fun {
                    return fun() -> Int {
                      n += 2
                      return n
                    }
                  }
                  let step = mid()
                  step()
                  step()
                  return n
                }
                outer()"""));
    }

    @Test
    @DisplayName("a capture chains through every function between owner and user")
    void capturesChainThroughFourFrames() {
        assertEquals("101", Scripts.display("""
                fun level0() -> Int {
                  var n = 1
                  fun level1() -> Fun {
                    return fun() -> Fun {
                      return fun() -> Int {
                        n += 100
                        return n
                      }
                    }
                  }
                  level1()()()
                  return n
                }
                level0()"""));
    }

    @Test
    @DisplayName("two closures made in one function share the cell they were handed")
    void siblingClosuresShareOneCell() {
        // Both lambdas capture n from outer through mid, so mid holds one upvalue
        // that both of them read: the second bump sees the first one's work.
        assertEquals("11", Scripts.display("""
                fun outer() -> Int {
                  var n = 0
                  fun mid() -> Int {
                    let bump = fun() -> Int {
                      n += 1
                      return n
                    }
                    let big = fun() -> Int {
                      n += 10
                      return n
                    }
                    bump()
                    big()
                    return n
                  }
                  mid()
                }
                outer()"""));
    }

    @Test
    @DisplayName("the owner reads and writes its own boxed slot")
    void ownerReadsItsBoxedSlot() {
        // Once a nested function captures n, the owner goes through the box too, so
        // a later read in the owner must see what the closure left there.
        assertEquals("82", Scripts.display("""
                fun owner() -> Int {
                  var n = 0
                  fun peek() -> Int {
                    return n
                  }
                  n = 41
                  n + peek()
                }
                owner()"""));
    }

    @Test
    @DisplayName("a method's local survives as a field-writing closure")
    void closureFromAMethodCapturesBothLocalAndField() {
        // `local` is the method's own variable and `total` is a field, so the closure
        // needs the receiver and the frame local at once, and outlives the call that
        // made it.
        assertEquals("6", Scripts.display("""
                class Counter {
                  var total = 0
                  fun make() -> Fun {
                    var local = 5
                    return fun() -> Int {
                      local += 1
                      total += local
                      return total
                    }
                  }
                }
                let c = Counter()
                let step = c.make()
                step()"""));
        assertEquals("13", Scripts.display("""
                class Counter {
                  var total = 0
                  fun make() -> Fun {
                    var local = 5
                    return fun() -> Int {
                      local += 1
                      total += local
                      return total
                    }
                  }
                }
                let c = Counter()
                let step = c.make()
                step()
                step()"""));
    }

    @Test
    @DisplayName("closures made in a loop share one cell, as the guide says")
    void loopCellIsShared() {
        assertEquals("[4, 4, 4, 4]", Scripts.display("""
                var cells = []
                for i in 1..4 {
                  let k = i
                  cells.push(fun() -> Int {
                    return k
                  })
                }
                cells.map { c -> c() }"""));
    }
}
