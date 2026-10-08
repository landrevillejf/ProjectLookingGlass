/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.utils.system;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SecurityPosture}, the tiny apps-publish / core-read seam that
 * carries the Security Center's letter grade to the taskbar shield tooltip. The
 * grade is global static state, so each test clears it before and after to stay
 * hermetic.
 */
class SecurityPostureTest {

    @BeforeEach
    void setUp() {
        SecurityPosture.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityPosture.clear();
    }

    @Test
    @DisplayName("an unassessed host reads as empty, never a guessed grade")
    void defaultIsEmpty() {
        assertEquals("", SecurityPosture.grade());
    }

    @Test
    @DisplayName("publish trims the grade and null / blank clears it")
    void publishTrims() {
        SecurityPosture.publish("B");
        assertEquals("B", SecurityPosture.grade());

        SecurityPosture.publish("  c  ");
        assertEquals("c", SecurityPosture.grade(), "surrounding whitespace is trimmed");

        SecurityPosture.publish(null);
        assertEquals("", SecurityPosture.grade(), "a null grade clears the assessment");

        SecurityPosture.publish("A");
        SecurityPosture.publish("   ");
        assertEquals("", SecurityPosture.grade(), "a blank grade clears the assessment");
    }

    @Test
    @DisplayName("clear resets the grade back to unassessed")
    void clearResets() {
        SecurityPosture.publish("F");
        assertEquals("F", SecurityPosture.grade());
        SecurityPosture.clear();
        assertEquals("", SecurityPosture.grade());
    }

    @Test
    @DisplayName("describe never invents a score for a blank grade")
    void describeIsHonest() {
        assertEquals("security grade not assessed yet", SecurityPosture.describe(null));
        assertEquals("security grade not assessed yet", SecurityPosture.describe(""));
        assertEquals("security grade not assessed yet", SecurityPosture.describe("   "));
        assertEquals("security grade B", SecurityPosture.describe("B"));
        assertEquals("security grade b", SecurityPosture.describe(" b "),
                "the letter is trimmed but not rewritten");
    }
}
