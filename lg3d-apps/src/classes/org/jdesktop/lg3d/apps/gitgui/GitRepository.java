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

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jdesktop.lg3d.utils.system.ProcessRunner;

/**
 * The Git GUI backend: an AWT-free executor that drives the system {@code git}
 * against one working directory and returns the parsed models
 * ({@link GitChange} / {@link GitCommit} / {@link GitBranch}) plus raw
 * {@link GitResult}s for mutating commands.
 *
 * <p>The desktop bundles no JGit, so - exactly like the Security Center
 * delegating to ClamAV and the VPN client delegating to {@code nmcli} - this
 * class honestly shells out to the {@code git} on {@code PATH} through lg3d's
 * {@link ProcessRunner}. When git is missing every query degrades to an empty
 * result and every mutation to {@link GitResult#NOT_STARTED}, so the panel shows
 * guidance instead of throwing or faking success.</p>
 *
 * <p>The actual process launch is isolated behind the {@link Runner} seam so the
 * whole class is unit-testable headless against canned git output with a fake
 * runner - no repository and no git binary required.</p>
 */
public final class GitRepository {

    /** Timeout for local, fast commands (status, log, diff, commit). */
    private static final long LOCAL_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);

    /** Timeout for network commands (fetch, pull, push). */
    private static final long NETWORK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(120);

    /** How many commits {@link #log()} fetches by default. */
    public static final int DEFAULT_LOG_LIMIT = 200;

    /**
     * Runs one command list against a working directory. The default
     * implementation delegates to {@link ProcessRunner}; tests inject a fake.
     */
    public interface Runner {
        GitResult run(List<String> command, File workingDir, long timeoutMillis);

        /**
         * Whether the backend executable is present. Defaults to true so a fake
         * runner (which never launches a process) is always "available"; the
         * production runner overrides this with a real {@code PATH} probe.
         */
        default boolean available() {
            return true;
        }
    }

    /** The production runner: lg3d's {@link ProcessRunner} over the real git. */
    public static final Runner PROCESS_RUNNER = new Runner() {
        @Override
        public GitResult run(List<String> command, File dir, long timeoutMillis) {
            return GitResult.of(ProcessRunner.run(command, timeoutMillis,
                    TimeUnit.MILLISECONDS, dir));
        }

        @Override
        public boolean available() {
            return ProcessRunner.isAvailable(GitCommands.GIT);
        }
    };

    private final File directory;
    private final Runner runner;

    /** Creates a repository handle over {@code directory} using the real git. */
    public GitRepository(File directory) {
        this(directory, PROCESS_RUNNER);
    }

    /**
     * Creates a repository handle over {@code directory} using {@code runner}.
     * Package-visible seam for headless tests; the panel uses the real runner.
     */
    GitRepository(File directory, Runner runner) {
        this.directory = directory;
        this.runner = (runner == null) ? PROCESS_RUNNER : runner;
    }

    /** True when a {@code git} executable is on the {@code PATH}. */
    public static boolean isGitAvailable() {
        return ProcessRunner.isAvailable(GitCommands.GIT);
    }

    /**
     * True when this handle's backend is usable: a real {@code PATH} probe for
     * the production runner, always true for an injected fake.
     */
    public boolean isAvailable() {
        return runner.available();
    }

    /** The working directory this handle runs git in. */
    public File getDirectory() {
        return directory;
    }

    // ------------------------------------------------------------------
    // Core execution
    // ------------------------------------------------------------------

    /** Runs a local command, or returns {@link GitResult#NOT_STARTED} if git is absent. */
    GitResult run(List<String> command) {
        return run(command, LOCAL_TIMEOUT_MS);
    }

    private GitResult run(List<String> command, long timeoutMillis) {
        if (!isAvailable()) {
            return GitResult.NOT_STARTED;
        }
        return runner.run(command, directory, timeoutMillis);
    }

    // ------------------------------------------------------------------
    // Inspection
    // ------------------------------------------------------------------

    /** True when {@code directory} sits inside a git working tree. */
    public boolean isRepository() {
        GitResult r = run(GitCommands.isRepository());
        return r.isSuccess() && r.getStdout().trim().startsWith("true");
    }

    /** The repository top-level path, or the directory path when unavailable. */
    public String topLevel() {
        GitResult r = run(GitCommands.topLevel());
        return r.isSuccess()
                ? GitParsers.firstLine(r.getStdout(), directory.getPath())
                : directory.getPath();
    }

    /** The current branch name (empty when detached / unborn / no repo). */
    public String currentBranch() {
        GitResult r = run(GitCommands.currentBranch());
        if (!r.isSuccess()) {
            return "";
        }
        String name = GitParsers.firstLine(r.getStdout(), "");
        return "HEAD".equals(name) ? "" : name;   // detached HEAD has no name
    }

    /** The {@code origin} remote URL, or empty when there is none. */
    public String remoteUrl() {
        GitResult r = run(GitCommands.remoteUrl());
        return r.isSuccess() ? GitParsers.firstLine(r.getStdout(), "") : "";
    }

    /** The local branches, current first-marked; empty when unavailable. */
    public List<GitBranch> branches() {
        GitResult r = run(GitCommands.branches());
        return r.isSuccess() ? GitParsers.parseBranches(r.getStdout()) : List.of();
    }

    /** The working-tree status (branch + changes); empty when unavailable. */
    public GitParsers.Status status() {
        GitResult r = run(GitCommands.status());
        return r.isSuccess()
                ? GitParsers.parseStatus(r.getStdout())
                : new GitParsers.Status("", List.of());
    }

    /** The most recent {@code limit} commits, newest first. */
    public List<GitCommit> log(int limit) {
        GitResult r = run(GitCommands.log(limit));
        return r.isSuccess() ? GitParsers.parseLog(r.getStdout()) : List.of();
    }

    /** The most recent {@link #DEFAULT_LOG_LIMIT} commits. */
    public List<GitCommit> log() {
        return log(DEFAULT_LOG_LIMIT);
    }

    /** The unified diff of one path (staged or work-tree); raw patch text. */
    public String diff(String path, boolean staged) {
        GitResult r = run(GitCommands.diff(path, staged));
        return r.isSuccess() ? r.getStdout() : r.getMessage();
    }

    /** The full patch of one commit ({@code git show}); raw text. */
    public String show(String hash) {
        if (hash == null || hash.isBlank()) {
            return "";
        }
        GitResult r = run(GitCommands.show(hash));
        return r.isSuccess() ? r.getStdout() : r.getMessage();
    }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------

    /** Stages the given paths ({@code git add}). */
    public GitResult stage(List<String> paths) {
        return run(GitCommands.add(paths));
    }

    /** Unstages the given paths, keeping work-tree edits ({@code git restore --staged}). */
    public GitResult unstage(List<String> paths) {
        return run(GitCommands.unstage(paths));
    }

    /** Commits the staged index with {@code message}. */
    public GitResult commit(String message) {
        if (message == null || message.isBlank()) {
            return new GitResult(false, -1, "", "Enter a commit message first.");
        }
        return run(GitCommands.commit(message));
    }

    /** Switches the working tree to {@code branch}. */
    public GitResult checkout(String branch) {
        if (branch == null || branch.isBlank()) {
            return new GitResult(false, -1, "", "No branch selected.");
        }
        return run(GitCommands.checkout(branch));
    }

    /** Creates and checks out a new branch {@code name}. */
    public GitResult createBranch(String name) {
        if (name == null || name.isBlank()) {
            return new GitResult(false, -1, "", "Enter a branch name first.");
        }
        return run(GitCommands.createBranch(name));
    }

    /** Fast-forwards from the tracking remote ({@code git pull --ff-only}). */
    public GitResult pull() {
        return run(GitCommands.pull(), NETWORK_TIMEOUT_MS);
    }

    /** Publishes the current branch ({@code git push}). */
    public GitResult push() {
        return run(GitCommands.push(), NETWORK_TIMEOUT_MS);
    }

    /** Refreshes remote-tracking refs ({@code git fetch --all --prune}). */
    public GitResult fetch() {
        return run(GitCommands.fetch(), NETWORK_TIMEOUT_MS);
    }

    /** Creates a new repository in the working directory ({@code git init}). */
    public GitResult init() {
        return run(GitCommands.init());
    }
}
