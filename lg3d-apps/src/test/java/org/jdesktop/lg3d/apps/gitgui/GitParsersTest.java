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
 * Covers the pure parsers against canned {@code git} output: porcelain status
 * (including the whitespace-significant staged column and renames), for-each-ref
 * branches, the separator-delimited log, and single-value first-line reads.
 */
class GitParsersTest {

    private static final String FS = GitCommands.FIELD_SEP;
    private static final String RS = GitCommands.RECORD_SEP;

    @Test
    @DisplayName("status parses the branch header and the change list")
    void parsesStatus() {
        String out = "## main...origin/main [ahead 1]\n"
                + " M src/A.java\n"
                + "A  src/B.java\n"
                + "?? build/out.txt\n"
                + " D gone.txt\n";
        GitParsers.Status status = GitParsers.parseStatus(out);
        assertEquals("main", status.branch());
        assertEquals(4, status.changes().size());

        GitChange modified = status.changes().get(0);
        assertEquals("src/A.java", modified.path());
        assertEquals(' ', modified.indexStatus());
        assertEquals('M', modified.worktreeStatus());
        assertFalse(modified.isStaged(), "a leading space means not staged");
        assertEquals("Modified", modified.statusLabel());

        GitChange added = status.changes().get(1);
        assertTrue(added.isStaged());
        assertEquals("New file", added.statusLabel());

        GitChange untracked = status.changes().get(2);
        assertTrue(untracked.isUntracked());
        assertEquals("Untracked", untracked.statusLabel());

        GitChange deleted = status.changes().get(3);
        assertEquals("Deleted", deleted.statusLabel());
    }

    @Test
    @DisplayName("status keeps the new path of a rename")
    void parsesRename() {
        GitParsers.Status status = GitParsers.parseStatus("## main\nR  old.txt -> new.txt\n");
        assertEquals(1, status.changes().size());
        assertEquals("new.txt", status.changes().get(0).path());
        assertEquals("Renamed", status.changes().get(0).statusLabel());
    }

    @Test
    @DisplayName("status recognises conflict codes")
    void parsesConflict() {
        GitParsers.Status status = GitParsers.parseStatus("## main\nUU clash.txt\n");
        GitChange conflict = status.changes().get(0);
        assertTrue(conflict.isConflict());
        assertEquals("Conflict", conflict.statusLabel());
    }

    @Test
    @DisplayName("status tolerates a fresh repo and empty input")
    void parsesDegenerateStatus() {
        assertEquals("", GitParsers.parseStatus(null).branch());
        assertTrue(GitParsers.parseStatus(null).changes().isEmpty());
        assertEquals("main",
                GitParsers.parseStatus("## No commits yet on main\n").branch());
        assertTrue(GitParsers.parseStatus("## main\n").changes().isEmpty());
    }

    @Test
    @DisplayName("the branch header strips upstream and ahead/behind")
    void parsesBranchHeader() {
        assertEquals("main", GitParsers.parseBranchHeader("main...origin/main [ahead 1]"));
        assertEquals("feat/x", GitParsers.parseBranchHeader("feat/x"));
        assertEquals("dev", GitParsers.parseBranchHeader("dev [behind 2]"));
        assertEquals("", GitParsers.parseBranchHeader(null));
    }

    @Test
    @DisplayName("branches marks the checked-out head")
    void parsesBranches() {
        String out = "*\tmain\n\tfeature/a\n\tfeature/b\n";
        List<GitBranch> branches = GitParsers.parseBranches(out);
        assertEquals(3, branches.size());
        assertTrue(branches.get(0).current());
        assertEquals("main", branches.get(0).name());
        assertFalse(branches.get(1).current());
        assertEquals("feature/a", branches.get(1).name());
        assertTrue(branches.get(2).name().equals("feature/b"));
    }

    @Test
    @DisplayName("branches tolerates blank and missing output")
    void parsesDegenerateBranches() {
        assertTrue(GitParsers.parseBranches(null).isEmpty());
        assertTrue(GitParsers.parseBranches("\n\n").isEmpty());
    }

    @Test
    @DisplayName("log splits records and fields on the separators")
    void parsesLog() {
        String out = "abc123" + FS + "Alice" + FS + "2026-01-02" + FS + "First commit" + RS
                + "def456" + FS + "Bob" + FS + "2026-01-01" + FS + "Second commit" + RS;
        List<GitCommit> commits = GitParsers.parseLog(out);
        assertEquals(2, commits.size());
        assertEquals("abc123", commits.get(0).hash());
        assertEquals("Alice", commits.get(0).author());
        assertEquals("2026-01-02", commits.get(0).date());
        assertEquals("First commit", commits.get(0).subject());
        assertEquals("def456", commits.get(1).hash());
    }

    @Test
    @DisplayName("log skips malformed records and empty input")
    void parsesDegenerateLog() {
        assertTrue(GitParsers.parseLog(null).isEmpty());
        assertTrue(GitParsers.parseLog("   ").isEmpty());
        // A record with too few fields is dropped, a good one survives.
        String out = "bad" + RS + "abc" + FS + "A" + FS + "d" + FS + "ok" + RS;
        List<GitCommit> commits = GitParsers.parseLog(out);
        assertEquals(1, commits.size());
        assertEquals("ok", commits.get(0).subject());
    }

    @Test
    @DisplayName("firstLine returns the first non-blank line or the fallback")
    void parsesFirstLine() {
        assertEquals("main", GitParsers.firstLine("\n main \n", "?"));
        assertEquals("?", GitParsers.firstLine("  \n", "?"));
        assertEquals("?", GitParsers.firstLine(null, "?"));
    }

    @Test
    @DisplayName("change models expose staged / untracked / conflict and labels")
    void changeModelSemantics() {
        assertTrue(new GitChange("a", 'M', ' ').isStaged());
        assertTrue(new GitChange("a", '?', '?').isUntracked());
        assertTrue(new GitChange("a", 'A', 'A').isConflict());
        assertTrue(new GitChange("a", 'D', 'D').isConflict());
        assertEquals("Copied", new GitChange("a", 'C', ' ').statusLabel());
        assertEquals("Type change", new GitChange("a", 'T', ' ').statusLabel());
        assertEquals("Changed", new GitChange("a", 'X', ' ').statusLabel());
        assertTrue(new GitChange("M  a", 'M', ' ').toString().startsWith("M "));
    }

    @Test
    @DisplayName("commit and branch models render list-row text")
    void modelToString() {
        assertTrue(new GitCommit("h", "a", "d", "subj").toString().contains("subj"));
        assertTrue(new GitBranch("main", true).toString().startsWith("*"));
        assertFalse(new GitBranch("main", false).toString().startsWith("*"));
    }
}
