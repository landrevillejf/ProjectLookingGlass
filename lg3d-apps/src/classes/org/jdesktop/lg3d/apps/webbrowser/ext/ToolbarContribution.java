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
package org.jdesktop.lg3d.apps.webbrowser.ext;

/**
 * A toolbar button an extension contributes through
 * {@link BrowserExtension#toolbarContributions()}. The panel renders one Swing
 * button per contribution and invokes {@link #getOnClick()} on press.
 */
public final class ToolbarContribution {

    private final String id;
    private final String label;
    private final String tooltip;
    private final Runnable onClick;

    /**
     * @param id      stable id (used to de-duplicate contributions)
     * @param label   the button text
     * @param tooltip hover text (may be null)
     * @param onClick action run on the EDT when pressed (may be null)
     */
    public ToolbarContribution(String id, String label, String tooltip, Runnable onClick) {
        this.id = (id == null || id.isBlank()) ? "contrib" : id.trim();
        this.label = (label == null || label.isBlank()) ? this.id : label.trim();
        this.tooltip = (tooltip == null) ? "" : tooltip;
        this.onClick = onClick;
    }

    public String getId() { return id; }
    public String getLabel() { return label; }
    public String getTooltip() { return tooltip; }
    public Runnable getOnClick() { return onClick; }

    @Override
    public String toString() {
        return "ToolbarContribution[" + id + " " + label + "]";
    }
}
