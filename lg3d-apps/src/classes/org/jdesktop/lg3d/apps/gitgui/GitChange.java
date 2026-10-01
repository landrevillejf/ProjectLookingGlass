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

/**
 * One working-tree change as reported by {@code git status --porcelain}: the
 * file path plus the two-column index / work-tree status codes git uses.
 *
 * <p>This is the AWT-free model behind the "Changes" list of
 * {@link GitGuiPanel}. It knows how to translate git's terse status letters
 * into the words a desktop user expects ("Modified", "New file", "Deleted",
 * "Renamed", "Untracked", "Conflict") and whether the change is already staged
 * (in the index) or still only in the work tree.</p>
 *
 * @param path           the file path relative to the repository root
 * @param indexStatus    the staged / index column ({@code X} in git's {@code XY})
 * @param worktreeStatus the work-tree column ({@code Y} in git's {@code XY})
 */
public record GitChange(String path, char indexStatus, char worktreeStatus) {

    /** True when the change is already in the index (staged for commit). */
    public boolean isStaged() {
        return indexStatus != ' ' && indexStatus != '?' && indexStatus != '!';
    }

    /** True when git does not track the file yet ({@code ??}). */
    public boolean isUntracked() {
        return indexStatus == '?' || worktreeStatus == '?';
    }

    /** True when the path is in a merge / rebase conflict ({@code U} codes). */
    public boolean isConflict() {
        return indexStatus == 'U' || worktreeStatus == 'U'
                || (indexStatus == 'A' && worktreeStatus == 'A')
                || (indexStatus == 'D' && worktreeStatus == 'D');
    }

    /** A short human label for the change, e.g. "Modified" or "New file". */
    public String statusLabel() {
        if (isConflict()) {
            return "Conflict";
        }
        char code = (indexStatus != ' ' && indexStatus != '?')
                ? indexStatus : worktreeStatus;
        return switch (code) {
            case 'M' -> "Modified";
            case 'A' -> "New file";
            case 'D' -> "Deleted";
            case 'R' -> "Renamed";
            case 'C' -> "Copied";
            case 'T' -> "Type change";
            case '?' -> "Untracked";
            case '!' -> "Ignored";
            default -> "Changed";
        };
    }

    /**
     * The list-row text: a two-letter status badge followed by the path, e.g.
     * {@code "M  src/Main.java"} or {@code "?  build/out.txt"}.
     */
    @Override
    public String toString() {
        return "" + indexStatus + worktreeStatus + "  " + path;
    }
}
