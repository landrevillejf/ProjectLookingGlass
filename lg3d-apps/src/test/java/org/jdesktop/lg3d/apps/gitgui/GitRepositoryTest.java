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

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link GitRepository} against a {@link FakeGitRunner}: the executor
 * delegates to the right command, parses the canned output, and degrades to
 * empty / NOT_STARTED results when the backend is missing or a command fails -
 * all headless, with no repository and no git binary.
 */
class GitRepositoryTest {

    private static final File DIR = new File("/tmp/fake-repo");

    private static GitRepository repo(FakeGitRunner runner) {
        return new GitRepository(DIR, runner);
    }

    private static FakeGitRunner happyRepo() {
        return new FakeGitRunner()
                .when("--is-inside-work-tree", "true\n")
                .when("--show-toplevel", "/tmp/fake-repo\n")
                .when("--abbrev-ref", "main\n")
                .when("remote", "git@example.com:me/repo.git\n")
                .when("status", "## main\n M src/A.java\n")
                .when("for-each-ref", "*\tmain\n\tdev\n")
                .when("log", "abc" + GitCommands.FIELD_SEP + "A"
                        + GitCommands.FIELD_SEP + "d" + GitCommands.FIELD_SEP + "s"
                        + GitCommands.RECORD_SEP);
    }

    @Test
    @DisplayName("isRepository reads the rev-parse verdict")
    void detectsRepository() {
        assertTrue(repo(happyRepo()).isRepository());
        assertFalse(repo(new FakeGitRunner()
                .when("--is-inside-work-tree", "false\n")).isRepository());
        assertFalse(repo(new FakeGitRunner()
                .whenFails("--is-inside-work-tree", "not a repo")).isRepository());
    }

    @Test
    @DisplayName("a missing backend degrades every query and mutation")
    void missingGitDegrades() {
        FakeGitRunner runner = new FakeGitRunner().available(false);
        GitRepository r = repo(runner);
        assertFalse(r.isAvailable());
        assertFalse(r.isRepository());
        assertTrue(r.branches().isEmpty());
        assertTrue(r.log().isEmpty());
        assertEquals("", r.currentBranch());
        assertEquals("", r.remoteUrl());
        assertEquals(DIR.getPath(), r.topLevel(), "falls back to the directory");
        assertEquals(GitResult.NOT_STARTED, r.commit("x"));
        assertTrue(runner.commands().isEmpty(), "no process is even attempted");
    }

    @Test
    @DisplayName("status, branches and log are parsed from the runner output")
    void parsesViews() {
        GitRepository r = repo(happyRepo());
        GitParsers.Status status = r.status();
        assertEquals("main", status.branch());
        assertEquals(1, status.changes().size());
        assertEquals("src/A.java", status.changes().get(0).path());

        List<GitBranch> branches = r.branches();
        assertEquals(2, branches.size());
        assertTrue(branches.get(0).current());

        List<GitCommit> commits = r.log();
        assertEquals(1, commits.size());
        assertEquals("abc", commits.get(0).hash());
    }

    @Test
    @DisplayName("a failing read yields an empty view, not a throw")
    void failingReadsAreEmpty() {
        FakeGitRunner runner = new FakeGitRunner()
                .whenFails("status", "fatal: bad")
                .whenFails("for-each-ref", "fatal: bad")
                .whenFails("log", "fatal: bad");
        GitRepository r = repo(runner);
        assertTrue(r.status().changes().isEmpty());
        assertTrue(r.branches().isEmpty());
        assertTrue(r.log().isEmpty());
    }

    @Test
    @DisplayName("currentBranch blanks a detached HEAD")
    void detachedHead() {
        GitRepository detached = repo(new FakeGitRunner().when("--abbrev-ref", "HEAD\n"));
        assertEquals("", detached.currentBranch());
        GitRepository named = repo(new FakeGitRunner().when("--abbrev-ref", "main\n"));
        assertEquals("main", named.currentBranch());
    }

    @Test
    @DisplayName("diff and show return the patch, or the error text on failure")
    void diffAndShow() {
        FakeGitRunner runner = happyRepo()
                .when("diff", "- a\n+ b\n")
                .whenFails("show", "fatal: bad object zz");
        GitRepository r = repo(runner);
        assertEquals("- a\n+ b\n", r.diff("src/A.java", false));
        assertTrue(r.show("zz").contains("bad object"), "failure surfaces git's message");
        assertEquals("", r.show(null), "a blank hash never runs git");
    }

    @Test
    @DisplayName("mutations delegate to the right verb and report success")
    void mutations() {
        FakeGitRunner runner = happyRepo();
        GitRepository r = repo(runner);

        assertTrue(r.stage(List.of("a.txt")).isSuccess());
        assertTrue(runner.ran("add"));
        assertTrue(r.unstage(List.of("a.txt")).isSuccess());
        assertTrue(runner.ran("restore"));
        assertTrue(r.checkout("dev").isSuccess());
        assertTrue(runner.ran("checkout"));
        assertTrue(r.createBranch("feat/z").isSuccess());
        assertTrue(runner.ran("switch"));
        assertTrue(r.pull().isSuccess());
        assertTrue(runner.ran("pull"));
        assertTrue(r.push().isSuccess());
        assertTrue(runner.ran("push"));
        assertTrue(r.fetch().isSuccess());
        assertTrue(runner.ran("fetch"));
        assertTrue(r.init().isSuccess());
        assertTrue(runner.ran("init"));
    }

    @Test
    @DisplayName("mutations guard blank input before touching git")
    void mutationGuards() {
        FakeGitRunner runner = happyRepo();
        GitRepository r = repo(runner);
        assertFalse(r.commit("  ").isSuccess(), "an empty message is refused");
        assertFalse(r.checkout("").isSuccess());
        assertFalse(r.createBranch(null).isSuccess());
        assertFalse(runner.ran("commit"));
        assertFalse(runner.ran("checkout"));
        assertFalse(runner.ran("switch"));
    }

    @Test
    @DisplayName("a failing mutation reports git's stderr")
    void failingMutation() {
        GitRepository r = repo(new FakeGitRunner().whenFails("push", "rejected: non-fast-forward"));
        GitResult result = r.push();
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("non-fast-forward"));
    }

    @Test
    @DisplayName("GitResult distinguishes started, success and message")
    void resultSemantics() {
        assertTrue(new GitResult(true, 0, "", "").isSuccess());
        assertFalse(new GitResult(true, 1, "", "").isSuccess());
        assertFalse(GitResult.NOT_STARTED.isStarted());
        assertEquals("git is not available", GitResult.NOT_STARTED.getMessage());
        assertEquals("boom", new GitResult(true, 1, "", "boom").getMessage());
        assertEquals("out", new GitResult(true, 1, "out", "").getMessage());
        assertEquals("OK", new GitResult(true, 0, "", "").getMessage());
        assertEquals("git exited with code 3",
                new GitResult(true, 3, "", "").getMessage());
        assertEquals(List.of("a", "b"),
                new GitResult(true, 0, "a\n\nb\n", "").stdoutLines());
        assertTrue(new GitResult(true, 0, null, null).stdoutLines().isEmpty());
        assertEquals(GitResult.NOT_STARTED, GitResult.of(null));
    }
}
