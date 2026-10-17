/*
 * Pins the CommandResult value object: ok() is exit-zero only, failure() carries
 * the reason on stderr with a negative exit (the "command never ran" signal the
 * Docker seam degrades on), and the accessors are null-safe.
 */
package org.jdesktop.lg3d.webserver.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommandResultTest {

    @Test
    void okIsTrueOnlyForZeroExit() {
        assertThat(new CommandResult(0, "out", "").ok()).isTrue();
        assertThat(new CommandResult(1, "", "err").ok()).isFalse();
        assertThat(CommandResult.failure("nope").ok()).isFalse();
    }

    @Test
    void failureCarriesReasonOnStderrWithNegativeExit() {
        CommandResult result = CommandResult.failure("could not run docker");
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).isEqualTo("could not run docker");
    }

    @Test
    void failureToleratesNullReason() {
        assertThat(CommandResult.failure(null).stderr()).isEmpty();
    }

    @Test
    void stdoutTrimmedHandlesNullAndWhitespace() {
        assertThat(new CommandResult(0, "  hi \n", "").stdoutTrimmed()).isEqualTo("hi");
        assertThat(new CommandResult(0, null, "").stdoutTrimmed()).isEmpty();
    }
}
