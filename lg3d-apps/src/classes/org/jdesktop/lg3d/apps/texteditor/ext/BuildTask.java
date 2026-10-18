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
package org.jdesktop.lg3d.apps.texteditor.ext;

/**
 * One build task a project proposes to run, published by a build extension
 * through {@link EditorContext#showTasks} and rendered as a leaf of the editor's
 * Tasks tree. A task is discovered by scanning the project's build file
 * ({@code build.gradle} / {@code pom.xml}) rather than by asking the tool, so
 * the tree is available the moment a project is opened and refreshes as tasks
 * are added or removed &mdash; no Gradle/Maven invocation, no daemon, no
 * network.
 *
 * <p>The scan that discovers a task cannot run it, so it leaves
 * {@linkplain #action()} null; the owning extension wires each discovered task
 * to its own run path with {@link #withAction(Runnable)} before publishing. A
 * task with no action is a proposal the tree still lists (greyed, inert on
 * double-click) rather than drops, so a reader sees the whole vocabulary.</p>
 *
 * @param group       the tree branch this task is filed under (e.g.
 *                    {@code "Lifecycle"}, {@code "Declared tasks"},
 *                    {@code "module :app"}); blank files it at the root
 * @param name        the task or goal name, and the single token run for it
 *                    (e.g. {@code "build"}, {@code "test"}, {@code ":app:run"})
 * @param description a one-line human blurb shown as the node tooltip; may be blank
 * @param line        the 1-based line the task is declared on, or 0 for a
 *                    built-in lifecycle task the build file never names
 * @param action      the run callback, or null when the task is not yet wired
 */
public record BuildTask(String group, String name, String description, int line,
                        Runnable action) {

    public BuildTask {
        group = (group == null) ? "" : group;
        name = (name == null) ? "" : name;
        description = (description == null) ? "" : description;
        line = Math.max(0, line);
    }

    /**
     * A discovered-but-unwired task (no run callback). The scan returns these;
     * the extension attaches an action with {@link #withAction(Runnable)}.
     */
    public BuildTask(String group, String name, String description, int line) {
        this(group, name, description, line, null);
    }

    /** @return a copy of this task that runs {@code run} when activated. */
    public BuildTask withAction(Runnable run) {
        return new BuildTask(group, name, description, line, run);
    }

    /** @return true when this task carries a run callback. */
    public boolean isRunnable() {
        return action != null;
    }

    /** @return the label shown on the tree leaf. */
    public String display() {
        return name;
    }

    @Override
    public String toString() {
        return display();
    }
}
