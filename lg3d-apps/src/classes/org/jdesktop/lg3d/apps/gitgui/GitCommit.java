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
 * One commit in the branch history, as reported by {@code git log}.
 *
 * <p>This is the AWT-free model behind the "History" list of
 * {@link GitGuiPanel}. The {@code hash} is the abbreviated object name shown in
 * the list; the full object name is not needed to select a commit for
 * {@code git show}.</p>
 *
 * @param hash    the abbreviated commit hash ({@code %h})
 * @param author  the author name ({@code %an})
 * @param date    the author date, short form ({@code %ad} with {@code --date=short})
 * @param subject the commit subject line ({@code %s})
 */
public record GitCommit(String hash, String author, String date, String subject) {

    /** The list-row text: subject first, then hash, author and date. */
    @Override
    public String toString() {
        return subject + "  (" + hash + " - " + author + ", " + date + ")";
    }
}
