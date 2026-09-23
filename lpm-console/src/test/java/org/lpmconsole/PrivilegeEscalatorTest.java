package org.lpmconsole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link PrivilegeEscalator}'s command construction. Which escalator is
 * chosen (pkexec vs sudo) is fixed once at class load from {@code which pkexec},
 * so the assertions are written against {@link PrivilegeEscalator#getEscalationMethod()}
 * rather than a hardcoded tool, making them pass on any host. No process is ever
 * started: only the {@link ProcessBuilder} command list is inspected.
 */
class PrivilegeEscalatorTest {

    @Test
    @DisplayName("the escalation method is pkexec or sudo")
    void methodIsKnown() {
        String method = PrivilegeEscalator.getEscalationMethod();
        assertTrue(method.equals("pkexec") || method.equals("sudo"),
                "unexpected escalator: " + method);
    }

    @Test
    @DisplayName("escalate prefixes the chosen tool then command then args")
    void buildsEscalatedCommand() {
        String method = PrivilegeEscalator.getEscalationMethod();
        ProcessBuilder pb = PrivilegeEscalator.escalate(
                "/usr/bin/lpm", List.of("--no-color", "install", "firefox"));
        assertEquals(
                List.of(method, "/usr/bin/lpm", "--no-color", "install", "firefox"),
                pb.command());
    }

    @Test
    @DisplayName("escalate tolerates an empty argument list")
    void buildsEscalatedCommandWithoutArgs() {
        String method = PrivilegeEscalator.getEscalationMethod();
        ProcessBuilder pb = PrivilegeEscalator.escalate("/usr/bin/lpm", List.of());
        assertEquals(List.of(method, "/usr/bin/lpm"), pb.command());
    }

    @Test
    @DisplayName("pkexec selection implies escalation is available")
    void pkexecImpliesAvailable() {
        // isEscalationAvailable() is USE_PKEXEC || isSudoAvailable(); when the
        // chosen method is pkexec the first disjunct is true, so availability
        // must hold regardless of whether sudo is installed.
        if (PrivilegeEscalator.getEscalationMethod().equals("pkexec")) {
            assertTrue(PrivilegeEscalator.isEscalationAvailable());
        } else {
            // sudo path: just exercise the probe; its value depends on the host.
            PrivilegeEscalator.isEscalationAvailable();
        }
    }
}
