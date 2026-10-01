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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A headless {@link GitRepository.Runner} that never launches a process: it
 * answers each command from a scripted table keyed by a distinctive argument
 * token (e.g. {@code "status"}, {@code "--abbrev-ref"}) and records every command
 * it was asked to run. Lets the whole Git GUI backend and panel be exercised in
 * CI with no repository and no git binary on the PATH.
 */
final class FakeGitRunner implements GitRepository.Runner {

    private final Map<String, GitResult> responses = new LinkedHashMap<>();
    private final List<List<String>> commands = new ArrayList<>();
    private boolean available = true;

    /** Scripts the result for any command containing {@code token}. */
    FakeGitRunner when(String token, GitResult result) {
        responses.put(token, result);
        return this;
    }

    /** Scripts a successful result with the given stdout for {@code token}. */
    FakeGitRunner when(String token, String stdout) {
        return when(token, new GitResult(true, 0, stdout, ""));
    }

    /** Scripts a failing result with the given stderr for {@code token}. */
    FakeGitRunner whenFails(String token, String stderr) {
        return when(token, new GitResult(true, 1, "", stderr));
    }

    /** Makes {@link #available()} report {@code value} (git missing on PATH). */
    FakeGitRunner available(boolean value) {
        this.available = value;
        return this;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public GitResult run(List<String> command, File workingDir, long timeoutMillis) {
        commands.add(new ArrayList<>(command));
        for (Map.Entry<String, GitResult> entry : responses.entrySet()) {
            if (command.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return new GitResult(true, 0, "", "");
    }

    /** Every command this runner was asked to execute, in order. */
    List<List<String>> commands() {
        return commands;
    }

    /** True when some recorded command contains {@code token}. */
    boolean ran(String token) {
        for (List<String> command : commands) {
            if (command.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
