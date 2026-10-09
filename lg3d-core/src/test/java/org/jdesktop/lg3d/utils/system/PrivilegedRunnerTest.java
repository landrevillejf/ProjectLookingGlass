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
package org.jdesktop.lg3d.utils.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of the {@link PrivilegedRunner} streaming overload: the
 * guard rails (empty command) behave identically with a line sink attached,
 * and the sink stays untouched when nothing can run. Whether {@code pkexec}
 * itself is installed is environment-specific, so live elevation is not
 * asserted here.
 */
class PrivilegedRunnerTest {

    @Test
    @DisplayName("empty command is an error and never touches the sink")
    void emptyCommandIsAnErrorWithoutElevation() {
        Queue<String> seen = new ConcurrentLinkedQueue<>();
        PrivilegedRunner.PrivilegedResult r = PrivilegedRunner.run(List.of(), seen::add);
        assertEquals(PrivilegedRunner.Status.ERROR, r.getStatus(), "empty command is an error");
        assertTrue(seen.isEmpty(), "the sink must not be called");
    }
}
