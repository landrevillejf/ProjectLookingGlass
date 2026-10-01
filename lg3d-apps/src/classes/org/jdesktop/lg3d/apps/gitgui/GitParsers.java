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
 * Pure parsers turning {@code git}'s machine-readable output into the
 * {@link GitChange} / {@link GitCommit} / {@link GitBranch} models.
 *
 * <p>Every method is a pure string function - no process, no filesystem - so the
 * whole parse table is unit-testable headless against canned git output.</p>
 *
 * <p>The parsers take the <b>raw</b> stdout (not pre-trimmed lines) because git's
 * formats are whitespace-significant: {@code git status --porcelain} encodes the
 * staged column in character 0 (a leading space means "not staged"), and the log
 * format separates fields with the {@code %x1f} unit separator and records with
 * the {@code %x1e} record separator rather than newlines.</p>
 */
public final class GitParsers {

    private GitParsers() {
        // no instances
    }

    /**
     * The parsed result of {@code git status --porcelain=v1 --branch}.
     *
     * @param branch  the current branch name (may be empty on a fresh repo)
     * @param changes the working-tree changes, in git's own order
     */
    public record Status(String branch, List<GitChange> changes) {
    }

    /**
     * Parses {@code git status --porcelain=v1 --branch} output. The first
     * {@code ## } line yields the branch; every other non-blank line is an
     * {@code XY path} change, where a rename ({@code R}) carries
     * {@code old -> new} and the <em>new</em> path is kept.
     *
     * @param stdout the raw status output (may be null)
     * @return the parsed status, never null
     */
    public static Status parseStatus(String stdout) {
        List<GitChange> changes = new ArrayList<>();
        String branch = "";
        if (stdout == null) {
            return new Status(branch, changes);
        }
        for (String raw : stdout.split("\n")) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("## ")) {
                branch = parseBranchHeader(line.substring(3));
                continue;
            }
            if (line.length() < 4) {
                // A porcelain entry is at least "XY p"; anything shorter is noise.
                continue;
            }
            char index = line.charAt(0);
            char worktree = line.charAt(1);
            String path = line.substring(3);
            int arrow = path.indexOf(" -> ");
            if (arrow >= 0 && (index == 'R' || index == 'C')) {
                path = path.substring(arrow + 4);   // keep the rename/copy target
            }
            changes.add(new GitChange(path, index, worktree));
        }
        return new Status(branch, changes);
    }

    /**
     * Extracts the local branch name from a {@code --branch} header body such as
     * {@code main...origin/main [ahead 1]}, {@code main}, or
     * {@code No commits yet on main}.
     */
    static String parseBranchHeader(String body) {
        if (body == null) {
            return "";
        }
        String b = body.trim();
        String noCommits = "No commits yet on ";
        if (b.startsWith(noCommits)) {
            return b.substring(noCommits.length()).trim();
        }
        int dots = b.indexOf("...");
        if (dots >= 0) {
            b = b.substring(0, dots);
        }
        int bracket = b.indexOf('[');
        if (bracket >= 0) {
            b = b.substring(0, bracket);
        }
        return b.trim();
    }

    /**
     * Parses {@code git for-each-ref --format=%(HEAD)%09%(refname:short)} output
     * into local branches. A line whose {@code %(HEAD)} field is {@code *} is the
     * checked-out branch.
     *
     * @param stdout the raw for-each-ref output (may be null)
     * @return the branches, never null
     */
    public static List<GitBranch> parseBranches(String stdout) {
        List<GitBranch> branches = new ArrayList<>();
        if (stdout == null) {
            return branches;
        }
        for (String raw : stdout.split("\n")) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isBlank()) {
                continue;
            }
            boolean current = line.startsWith("*");
            String name = line;
            int tab = name.indexOf('\t');
            if (tab >= 0) {
                name = name.substring(tab + 1);
            } else if (current) {
                name = name.substring(1);
            }
            name = name.trim();
            if (!name.isEmpty()) {
                branches.add(new GitBranch(name, current));
            }
        }
        return branches;
    }

    /**
     * Parses {@code git log --pretty=format:%h%x1f%an%x1f%ad%x1f%s%x1e} output.
     * Records are split on the {@code %x1e} record separator, fields on the
     * {@code %x1f} unit separator; a record with fewer than four fields is
     * skipped rather than mis-shown.
     *
     * @param stdout the raw log output (may be null)
     * @return the commits, newest first, never null
     */
    public static List<GitCommit> parseLog(String stdout) {
        List<GitCommit> commits = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) {
            return commits;
        }
        for (String record : stdout.split(GitCommands.RECORD_SEP)) {
            String trimmed = record.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] fields = trimmed.split(java.util.regex.Pattern.quote(
                    GitCommands.FIELD_SEP), -1);
            if (fields.length < 4) {
                continue;
            }
            commits.add(new GitCommit(fields[0].trim(), fields[1].trim(),
                    fields[2].trim(), fields[3].trim()));
        }
        return commits;
    }

    /**
     * The first non-blank line of a single-value command's output
     * ({@code rev-parse --abbrev-ref HEAD}, {@code remote get-url origin},
     * {@code rev-parse --show-toplevel}), or {@code fallback} when there is none.
     *
     * @param stdout   the raw output (may be null)
     * @param fallback the value to return when the output is blank
     * @return the first line, or {@code fallback}
     */
    public static String firstLine(String stdout, String fallback) {
        if (stdout == null) {
            return fallback;
        }
        for (String raw : stdout.split("\n")) {
            String line = raw.trim();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return fallback;
    }
}
