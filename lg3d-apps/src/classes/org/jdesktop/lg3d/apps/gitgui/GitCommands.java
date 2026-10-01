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

import java.util.ArrayList;
import java.util.List;

/**
 * Pure {@code git} command-line builders for the Git GUI.
 *
 * <p>Every method here returns an argument list and starts no process, so the
 * exact command lines the app drives are unit-testable headless - the same
 * separation the Security Center's {@code AntivirusBackend} uses. The thin,
 * guarded launch lives in {@link GitRepository}.</p>
 *
 * <p>The desktop ships no JGit and no libgit2 binding: like the recorder handing
 * screen capture to {@code ffmpeg} and the VPN client handing the tunnel to
 * {@code nmcli}, this app honestly delegates every operation to the system
 * {@code git} on {@code PATH}. Two global switches are prepended to read
 * commands so their output is machine-parseable regardless of the user's
 * config:</p>
 * <ul>
 *  <li>{@code -c core.quotePath=false} - keep non-ASCII paths literal instead
 *      of octal-escaping them, so {@link GitParsers} sees the real filename;</li>
 *  <li>{@code -c color.ui=false} / {@code --no-optional-locks} - never emit ANSI
 *      colour and never take a lock that a concurrent git could contend.</li>
 * </ul>
 */
public final class GitCommands {

    /** The git executable name, resolved against {@code PATH}. */
    public static final String GIT = "git";

    /**
     * Field separator (ASCII unit separator, {@code %x1f}) used between the
     * fields of one log record; chosen because it never occurs in a message.
     */
    public static final String FIELD_SEP = "\u001f";

    /**
     * Record separator (ASCII record separator, {@code %x1e}) used between log
     * records, so a multi-line commit body can never split a record.
     */
    public static final String RECORD_SEP = "\u001e";

    /** The {@code git log} pretty format the parser expects. */
    public static final String LOG_FORMAT =
            "%h" + FIELD_SEP + "%an" + FIELD_SEP + "%ad" + FIELD_SEP + "%s" + RECORD_SEP;

    private GitCommands() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Global switches
    // ------------------------------------------------------------------

    /** Starts a read-only command with the parse-friendly global switches. */
    private static List<String> read(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(GIT);
        cmd.add("-c");
        cmd.add("core.quotePath=false");
        cmd.add("-c");
        cmd.add("color.ui=false");
        cmd.add("--no-optional-locks");
        for (String a : args) {
            cmd.add(a);
        }
        return cmd;
    }

    /** Starts a mutating command (no {@code --no-optional-locks}, it must lock). */
    private static List<String> write(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(GIT);
        cmd.add("-c");
        cmd.add("core.quotePath=false");
        for (String a : args) {
            cmd.add(a);
        }
        return cmd;
    }

    // ------------------------------------------------------------------
    // Inspection
    // ------------------------------------------------------------------

    /** {@code git rev-parse --is-inside-work-tree}: is {@code dir} in a repo? */
    public static List<String> isRepository() {
        return read("rev-parse", "--is-inside-work-tree");
    }

    /** {@code git rev-parse --show-toplevel}: the repository root path. */
    public static List<String> topLevel() {
        return read("rev-parse", "--show-toplevel");
    }

    /** {@code git rev-parse --abbrev-ref HEAD}: the current branch name. */
    public static List<String> currentBranch() {
        return read("rev-parse", "--abbrev-ref", "HEAD");
    }

    /**
     * {@code git status --porcelain=v1 --branch}: the machine-readable change
     * list plus a leading {@code ## branch} line.
     */
    public static List<String> status() {
        return read("status", "--porcelain=v1", "--branch");
    }

    /**
     * {@code git for-each-ref} over local heads, tab-separated into
     * "{@code HEAD-mark}\t{@code short-name}".
     */
    public static List<String> branches() {
        return read("for-each-ref", "--format=%(HEAD)%09%(refname:short)",
                "refs/heads");
    }

    /** {@code git log} of at most {@code limit} commits in {@link #LOG_FORMAT}. */
    public static List<String> log(int limit) {
        int n = Math.max(1, limit);
        return read("log", "-n", Integer.toString(n),
                "--date=short", "--pretty=format:" + LOG_FORMAT);
    }

    /** {@code git show <hash>} - the full patch of one commit. */
    public static List<String> show(String hash) {
        return read("show", "--patch", "--format=fuller", hash);
    }

    /**
     * {@code git diff} of one path: against the index when {@code staged}, else
     * against the work tree. A blank path diffs the whole tree.
     */
    public static List<String> diff(String path, boolean staged) {
        // --no-color keeps the patch free of ANSI escapes for the Swing viewer.
        List<String> cmd = staged
                ? read("diff", "--cached", "--no-color")
                : read("diff", "--no-color");
        if (path != null && !path.isBlank()) {
            cmd.add("--");
            cmd.add(path);
        }
        return cmd;
    }

    /** {@code git remote get-url origin}: the push/pull remote URL, if any. */
    public static List<String> remoteUrl() {
        return read("remote", "get-url", "origin");
    }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------

    /** {@code git add -- <paths>}: stage the given paths (never a bare "."). */
    public static List<String> add(List<String> paths) {
        List<String> cmd = write("add", "--");
        if (paths != null) {
            cmd.addAll(paths);
        }
        return cmd;
    }

    /** {@code git restore --staged -- <paths>}: unstage, keeping work-tree edits. */
    public static List<String> unstage(List<String> paths) {
        List<String> cmd = write("restore", "--staged", "--");
        if (paths != null) {
            cmd.addAll(paths);
        }
        return cmd;
    }

    /**
     * {@code git commit -m <message>}: commit the staged index. The message is
     * passed as one argument (never through a shell), so any text is safe.
     */
    public static List<String> commit(String message) {
        return write("commit", "-m", (message == null) ? "" : message);
    }

    /** {@code git checkout <branch>}: switch the working tree to a branch. */
    public static List<String> checkout(String branch) {
        return write("checkout", branch);
    }

    /** {@code git switch -c <name>}: create and check out a new branch. */
    public static List<String> createBranch(String name) {
        return write("switch", "-c", name);
    }

    /** {@code git pull --ff-only}: fast-forward from the tracking remote. */
    public static List<String> pull() {
        return write("pull", "--ff-only");
    }

    /** {@code git push}: publish the current branch to its remote. */
    public static List<String> push() {
        return write("push");
    }

    /** {@code git fetch --all --prune}: refresh remote-tracking refs. */
    public static List<String> fetch() {
        return write("fetch", "--all", "--prune");
    }

    /** {@code git init}: create a new repository in the working directory. */
    public static List<String> init() {
        return write("init");
    }
}
