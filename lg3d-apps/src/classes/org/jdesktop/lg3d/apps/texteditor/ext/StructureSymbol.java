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
 * One entry in a document's structural outline (a type, method, field or other
 * named member), reported by an analysis extension through
 * {@link EditorContext#showStructure} and rendered in the Structure panel.
 *
 * @param name the member's simple name (e.g. {@code "computeIfAbsent"})
 * @param kind a short kind label (e.g. {@code "class"}, {@code "method"},
 *             {@code "field"}); free-form, shown beside the name
 * @param line the 1-based line to jump to when the node is activated
 */
public record StructureSymbol(String name, String kind, int line) {

    public StructureSymbol {
        name = (name == null) ? "" : name;
        kind = (kind == null) ? "" : kind;
        line = Math.max(1, line);
    }

    /** @return the label shown on the outline node. */
    public String display() {
        if (kind.isEmpty()) {
            return name;
        }
        return name + " : " + kind;
    }

    @Override
    public String toString() {
        return display();
    }
}
