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
package org.jdesktop.lg3d.apps.gitgui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure {@code git} command-line builders. No process is started, so
 * the exact argument lists the app drives are asserted headless.
 */
class GitCommandsTest {

    private static boolean startsWithGit(List<String> cmd) {
        return !cmd.isEmpty() && GitCommands.GIT.equals(cmd.get(0));
    }

    @Test
    @DisplayName("read commands carry the parse-friendly global switches")
    void readSwitches() {
        List<String> cmd = GitCommands.status();
        assertTrue(startsWithGit(cmd));
        assertTrue(cmd.contains("core.quotePath=false"), "keep non-ASCII paths literal");
        assertTrue(cmd.contains("color.ui=false"), "no ANSI escapes in parsed output");
        assertTrue(cmd.contains("--no-optional-locks"), "reads must not take a lock");
    }

    @Test
    @DisplayName("status is porcelain v1 with the branch header")
    void statusCommand() {
        List<String> cmd = GitCommands.status();
        assertTrue(cmd.contains("status"));
        assertTrue(cmd.contains("--porcelain=v1"));
        assertTrue(cmd.contains("--branch"));
    }

    @Test
    @DisplayName("branches uses for-each-ref over local heads")
    void branchesCommand() {
        List<String> cmd = GitCommands.branches();
        assertTrue(cmd.contains("for-each-ref"));
        assertTrue(cmd.contains("refs/heads"));
        assertTrue(cmd.stream().anyMatch(a -> a.contains("%(HEAD)")));
    }

    @Test
    @DisplayName("log is bounded and uses the unit/record separator format")
    void logCommand() {
        List<String> cmd = GitCommands.log(25);
        assertTrue(cmd.contains("log"));
        assertTrue(cmd.contains("-n"));
        assertTrue(cmd.contains("25"));
        assertTrue(cmd.contains("--date=short"));
        assertTrue(cmd.stream().anyMatch(a -> a.contains(GitCommands.FIELD_SEP)));
    }

    @Test
    @DisplayName("log clamps a non-positive limit to one")
    void logClampsLimit() {
        assertTrue(GitCommands.log(0).contains("1"));
        assertTrue(GitCommands.log(-5).contains("1"));
    }

    @Test
    @DisplayName("diff selects cached or work-tree and scopes to a path")
    void diffCommand() {
        List<String> staged = GitCommands.diff("src/A.java", true);
        assertTrue(staged.contains("--cached"));
        assertTrue(staged.contains("--"));
        assertTrue(staged.contains("src/A.java"));

        List<String> worktree = GitCommands.diff("src/A.java", false);
        assertFalse(worktree.contains("--cached"));
        assertTrue(worktree.contains("--no-color"));
        assertTrue(worktree.contains("src/A.java"));

        List<String> whole = GitCommands.diff(null, false);
        assertFalse(whole.contains("--"), "a blank path diffs the whole tree");
    }

    @Test
    @DisplayName("add and unstage always pass an explicit path separator")
    void stageCommands() {
        List<String> add = GitCommands.add(List.of("a.txt", "b.txt"));
        assertTrue(add.contains("add"));
        assertTrue(add.contains("--"));
        assertTrue(add.contains("a.txt"));
        assertTrue(add.contains("b.txt"));

        List<String> unstage = GitCommands.unstage(List.of("a.txt"));
        assertTrue(unstage.contains("restore"));
        assertTrue(unstage.contains("--staged"));
        assertTrue(unstage.contains("a.txt"));
    }

    @Test
    @DisplayName("commit passes the message as a single argument")
    void commitCommand() {
        List<String> cmd = GitCommands.commit("fix: a thing\n\nbody");
        assertTrue(cmd.contains("commit"));
        assertTrue(cmd.contains("-m"));
        assertEquals("fix: a thing\n\nbody", cmd.get(cmd.size() - 1),
                "the message must be one argv entry, never shell-split");
    }

    @Test
    @DisplayName("branch and remote operations build the expected verbs")
    void branchAndRemoteCommands() {
        assertTrue(GitCommands.checkout("feat/x").contains("feat/x"));
        List<String> create = GitCommands.createBranch("feat/y");
        assertTrue(create.contains("switch"));
        assertTrue(create.contains("-c"));
        assertTrue(create.contains("feat/y"));
        assertTrue(GitCommands.pull().contains("--ff-only"));
        assertTrue(GitCommands.push().contains("push"));
        assertTrue(GitCommands.fetch().contains("--all"));
        assertTrue(GitCommands.init().contains("init"));
        assertTrue(GitCommands.remoteUrl().contains("origin"));
        assertTrue(GitCommands.currentBranch().contains("--abbrev-ref"));
        assertTrue(GitCommands.isRepository().contains("--is-inside-work-tree"));
        assertTrue(GitCommands.topLevel().contains("--show-toplevel"));
    }
}
