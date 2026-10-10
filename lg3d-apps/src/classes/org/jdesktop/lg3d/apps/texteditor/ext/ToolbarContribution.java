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

import java.util.Objects;
import javax.swing.Icon;

/**
 * A toolbar button contributed by an extension. The button appears in the
 * editor's toolbar under the extension's submenu when the extension is enabled
 * and granted {@link TextEditorPermission#TOOLBAR}. When a contribution
 * declares an {@linkplain #getAccelerator() accelerator}, the editor also binds
 * it as a keyboard shortcut so the action can be run directly, without opening
 * the extensions card.
 *
 * <p>A contribution may also carry an optional {@link Icon} for the toolbar
 * button art. It is supplied through the wider {@linkplain #ToolbarContribution(
 * String, String, String, Runnable, String, Icon) constructor}; the pre-existing
 * constructors leave it {@code null}, so every shipped and third-party extension
 * keeps compiling unchanged. When no icon is present the editor falls back to a
 * generated glyph, so a user-configured button always has art even off the
 * test classpath where an icon store may be absent.</p>
 */
public final class ToolbarContribution {

    private final String id;
    private final String label;
    private final String tooltip;
    private final Runnable action;
    private final String accelerator;
    private final Icon icon;

    /**
     * Builds a contribution with no accelerator.
     *
     * @param id      stable unique identifier within the extension (e.g. "sort-lines")
     * @param label   button label shown in the UI
     * @param tooltip tooltip text (may be null)
     * @param action  callback invoked on the EDT when the button is clicked
     */
    public ToolbarContribution(String id, String label, String tooltip, Runnable action) {
        this(id, label, tooltip, action, null);
    }

    /**
     * Builds a contribution that also carries a keyboard accelerator.
     *
     * @param id          stable unique identifier within the extension
     * @param label       button label shown in the UI
     * @param tooltip     tooltip text (may be null)
     * @param action      callback invoked on the EDT when the button is clicked
     * @param accelerator a {@link javax.swing.KeyStroke#getKeyStroke(String)} spec
     *                    (e.g. {@code "control alt S"}); null/blank means none
     */
    public ToolbarContribution(String id, String label, String tooltip, Runnable action,
                               String accelerator) {
        this(id, label, tooltip, action, accelerator, null);
    }

    /**
     * Builds a contribution that also carries a keyboard accelerator and an
     * optional button icon.
     *
     * @param id          stable unique identifier within the extension
     * @param label       button label shown in the UI
     * @param tooltip     tooltip text (may be null)
     * @param action      callback invoked on the EDT when the button is clicked
     * @param accelerator a {@link javax.swing.KeyStroke#getKeyStroke(String)} spec
     *                    (e.g. {@code "control alt S"}); null/blank means none
     * @param icon        button art (may be null; the editor then uses a glyph)
     */
    public ToolbarContribution(String id, String label, String tooltip, Runnable action,
                               String accelerator, Icon icon) {
        this.id = (id == null || id.isBlank()) ? "unknown" : id.trim();
        this.label = (label == null || label.isBlank()) ? this.id : label.trim();
        this.tooltip = (tooltip == null) ? "" : tooltip.trim();
        this.action = Objects.requireNonNull(action, "action");
        this.accelerator = (accelerator == null) ? "" : accelerator.trim();
        this.icon = icon;
    }

    public String getId() { return id; }
    public String getLabel() { return label; }
    public String getTooltip() { return tooltip; }
    public Runnable getAction() { return action; }

    /** @return the accelerator KeyStroke spec, or {@code ""} when none is declared. */
    public String getAccelerator() { return accelerator; }

    /** @return the button icon, or {@code null} when the contribution declares none. */
    public Icon getIcon() { return icon; }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ToolbarContribution)) {
            return false;
        }
        ToolbarContribution that = (ToolbarContribution) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ToolbarContribution[" + label + "]";
    }
}
