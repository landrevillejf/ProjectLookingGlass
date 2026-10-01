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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link GitGuiPanel}'s construction and its whole open / stage / commit /
 * branch / diff flow without ever spawning git: the panel is built in its
 * synchronous test mode over a {@link FakeGitRunner}-backed
 * {@link GitRepository}, so every worker pass runs inline on the test thread and
 * the readouts are deterministic. Swing widgets construct headless, so this runs
 * in CI.
 */
class GitGuiPanelTest {

    private static final File DIR = new File("/tmp/fake-repo");

    private static FakeGitRunner happyRepo() {
        return new FakeGitRunner()
                .when("--is-inside-work-tree", "true\n")
                .when("--abbrev-ref", "main\n")
                .when("remote", "git@example.com:me/repo.git\n")
                .when("status", "## main\n M src/A.java\n?? new.txt\n")
                .when("for-each-ref", "*\tmain\n\tdev\n")
                .when("log", "abc" + GitCommands.FIELD_SEP + "A"
                        + GitCommands.FIELD_SEP + "d" + GitCommands.FIELD_SEP + "subj"
                        + GitCommands.RECORD_SEP)
                .when("diff", "- old\n+ new\n")
                .when("show", "commit abc\n");
    }

    private static GitGuiPanel panel(FakeGitRunner runner) {
        return new GitGuiPanel(new GitRepository(DIR, runner), true);
    }

    @Test
    @DisplayName("a fresh panel is empty and idle until a folder is opened")
    void freshPanelIsIdle() {
        GitGuiPanel panel = new GitGuiPanel();
        assertEquals("Open a repository to begin.", panel.statusText());
        assertEquals(0, panel.changeCount());
        assertEquals(0, panel.branchCount());
        assertEquals(0, panel.historyCount());
        assertFalse(panel.isRepositoryValid());
        assertEquals("", panel.branchText());
    }

    @Test
    @DisplayName("opening a repository fills the changes, branches and history")
    void loadsRepository() {
        GitGuiPanel panel = panel(happyRepo());
        assertTrue(panel.isRepositoryValid());
        assertEquals("main", panel.branchText());
        assertEquals(2, panel.changeCount(), "one modified + one untracked");
        assertEquals(2, panel.branchCount());
        assertEquals(1, panel.historyCount());
        assertTrue(panel.statusText().contains("main"), panel.statusText());
        assertTrue(panel.statusText().contains("2 change(s)"), panel.statusText());
        assertTrue(panel.statusText().contains("1 staged") == false, panel.statusText());
        assertTrue(panel.statusText().contains("origin: git@example.com:me/repo.git"),
                panel.statusText());
    }

    @Test
    @DisplayName("a folder that is not a repository says so honestly")
    void notARepository() {
        GitGuiPanel panel = panel(new FakeGitRunner()
                .when("--is-inside-work-tree", "false\n"));
        assertFalse(panel.isRepositoryValid());
        assertTrue(panel.statusText().contains("Not a git repository"), panel.statusText());
        assertEquals(0, panel.changeCount());
    }

    @Test
    @DisplayName("a missing git is surfaced as guidance, not a fake state")
    void missingGit() {
        GitGuiPanel panel = panel(new FakeGitRunner().available(false));
        assertFalse(panel.isRepositoryValid());
        assertTrue(panel.statusText().contains("git was not found"), panel.statusText());
    }

    @Test
    @DisplayName("staging the selected change runs git add and reloads")
    void stagesSelected() {
        FakeGitRunner runner = happyRepo();
        GitGuiPanel panel = panel(runner);
        panel.selectChange(0);
        panel.stageSelected();
        assertTrue(runner.ran("add"));
        assertTrue(panel.statusText().contains("Staged 1 file(s)"), panel.statusText());
    }

    @Test
    @DisplayName("unstaging the selected change runs git restore --staged")
    void unstagesSelected() {
        FakeGitRunner runner = happyRepo();
        GitGuiPanel panel = panel(runner);
        panel.selectChange(0);
        panel.unstageSelected();
        assertTrue(runner.ran("restore"));
        assertTrue(panel.statusText().contains("Unstaged 1 file(s)"), panel.statusText());
    }

    @Test
    @DisplayName("staging with no selection is a safe no-op")
    void stageNoSelection() {
        FakeGitRunner runner = happyRepo();
        GitGuiPanel panel = panel(runner);
        panel.stageSelected();
        panel.unstageSelected();
        assertFalse(runner.ran("add"));
        assertFalse(runner.ran("restore"));
    }

    @Test
    @DisplayName("committing with a message runs git commit and clears the box")
    void commits() {
        FakeGitRunner runner = happyRepo();
        GitGuiPanel panel = panel(runner);
        panel.setCommitMessage("feat: a thing");
        panel.commit();
        assertTrue(runner.ran("commit"));
        assertEquals("", panel.commitMessageText(), "the box clears on success");
        assertTrue(panel.statusText().contains("Commit"), panel.statusText());
    }

    @Test
    @DisplayName("committing with a blank message is refused")
    void commitBlank() {
        FakeGitRunner runner = happyRepo();
        GitGuiPanel panel = panel(runner);
        panel.setCommitMessage("   ");
        panel.commit();
        assertFalse(runner.ran("commit"));
        assertEquals("Enter a commit message first.", panel.statusText());
        assertEquals("   ", panel.commitMessageText(), "the text is kept for editing");
    }

    @Test
    @DisplayName("checking out the selected branch runs git checkout")
    void checksOut() {
        FakeGitRunner runner = happyRepo();
        GitGuiPanel panel = panel(runner);
        panel.selectBranch(1);   // dev
        panel.checkoutSelected();
        assertTrue(runner.ran("checkout"));
        assertTrue(panel.statusText().contains("Checked out dev"), panel.statusText());
    }

    @Test
    @DisplayName("creating a branch runs git switch -c")
    void createsBranch() {
        FakeGitRunner runner = happyRepo();
        GitGuiPanel panel = panel(runner);
        panel.doCreateBranch("feat/z");
        assertTrue(runner.ran("switch"));
        assertTrue(panel.statusText().contains("Created branch feat/z"), panel.statusText());
    }

    @Test
    @DisplayName("fetch, pull and push delegate and report")
    void syncActions() {
        FakeGitRunner runner = happyRepo();
        GitGuiPanel panel = panel(runner);
        panel.doFetch();
        assertTrue(runner.ran("fetch"));
        panel.doPull();
        assertTrue(runner.ran("pull"));
        panel.doPush();
        assertTrue(runner.ran("push"));
        assertTrue(panel.statusText().contains("Push"), panel.statusText());
    }

    @Test
    @DisplayName("a failing sync surfaces git's own error")
    void failingSync() {
        FakeGitRunner runner = happyRepo().whenFails("push", "rejected: non-fast-forward");
        GitGuiPanel panel = panel(runner);
        panel.doPush();
        assertTrue(panel.statusText().contains("failed"), panel.statusText());
        assertTrue(panel.statusText().contains("non-fast-forward"), panel.statusText());
    }

    @Test
    @DisplayName("selecting a change shows its work-tree diff")
    void showsChangeDiff() {
        GitGuiPanel panel = panel(happyRepo());
        panel.selectChange(0);   // " M src/A.java" (unstaged)
        assertTrue(panel.diffText().contains("+ new"), panel.diffText());
    }

    @Test
    @DisplayName("selecting an untracked change explains there is no diff yet")
    void showsUntrackedDiff() {
        GitGuiPanel panel = panel(happyRepo());
        panel.selectChange(1);   // "?? new.txt"
        assertTrue(panel.diffText().contains("untracked file"), panel.diffText());
    }

    @Test
    @DisplayName("selecting a commit shows its patch")
    void showsCommitDiff() {
        GitGuiPanel panel = panel(happyRepo());
        panel.selectCommit(0);
        assertTrue(panel.diffText().contains("commit abc"), panel.diffText());
    }

    @Test
    @DisplayName("actions are inert before any repository is opened")
    void inertWithoutRepository() {
        GitGuiPanel panel = new GitGuiPanel();
        panel.stageSelected();
        panel.commit();
        panel.doCheckout("dev");
        panel.doCreateBranch("x");
        panel.doFetch();
        panel.doPull();
        panel.doPush();
        panel.doInit();
        assertEquals("Open a folder first, then Init to create a repository.",
                panel.statusText());
    }
}
