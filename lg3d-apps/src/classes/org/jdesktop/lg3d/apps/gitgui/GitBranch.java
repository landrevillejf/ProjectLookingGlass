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
 * A local branch, as reported by {@code git for-each-ref refs/heads}.
 *
 * <p>This is the AWT-free model behind the "Branches" list of
 * {@link GitGuiPanel}; {@code current} marks the checked-out {@code HEAD} so the
 * list can highlight it and the panel can disable a no-op checkout.</p>
 *
 * @param name    the short branch name (e.g. {@code main}, {@code feature/x})
 * @param current true when this branch is the checked-out {@code HEAD}
 */
public record GitBranch(String name, boolean current) {

    /** The list-row text: a leading {@code *} marks the current branch. */
    @Override
    public String toString() {
        return (current ? "* " : "  ") + name;
    }
}
