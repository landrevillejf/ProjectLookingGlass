/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.uikit;

import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.utils.action.ActionInt;
import org.jdesktop.lg3d.utils.eventadapter.MouseWheelEventAdapter;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Color4f;

/**
 * A wheel-scrollable viewport over a vertical list of fixed-height rows -
 * the lg3d-native stand-in for a {@code JScrollPane(JTable/JList)}.
 *
 * <p>Rows are {@link Component3D}s built by the caller at the list's width and
 * row height; the list positions the visible window of them top-down and hides
 * (and unpicks) the rest, so a thousand-row directory costs only the visible
 * rows in the scene graph. The mouse wheel scrolls; the viewport backdrop is
 * pickable so wheeling works over empty areas too.</p>
 *
 * <p>Place the list itself with {@link #setTranslation}; rows are positioned
 * relative to the list origin (centered viewport).</p>
 */
public class ScrollList3D extends Component3D {

    private final float width;
    private final float height;
    private final float rowH;
    private final int visible;

    private final List<Component3D> rows = new ArrayList<>();
    private int offset;

    public ScrollList3D(float width, float height, float rowH, Color4f backdrop) {
        this.width = width;
        this.height = height;
        this.rowH = rowH;
        this.visible = Math.max(1, (int) Math.floor(height / rowH));

        // Backdrop first (behind rows), also the wheel pick target.
        addChild(Ui3D.component(
                Ui3D.at(Ui3D.panel(width, height, 0.004f, backdrop), 0f, 0f, -0.004f)));

        addListener(new MouseWheelEventAdapter(new ActionInt() {
            public void performAction(LgEventSource source, int rotation) {
                scroll(rotation);
            }
        }));
    }

    /** Replaces the row set and resets the scroll position to the top. */
    public void setRows(List<Component3D> newRows) {
        for (Component3D r : rows) {
            removeChild(r);
        }
        rows.clear();
        rows.addAll(newRows);
        for (Component3D r : rows) {
            addChild(r);
        }
        offset = 0;
        layout();
    }

    /** Scrolls by {@code delta} rows (positive = down), clamped to the ends. */
    public void scroll(int delta) {
        int max = Math.max(0, rows.size() - visible);
        int n = Math.min(max, Math.max(0, offset + delta));
        if (n != offset) {
            offset = n;
            layout();
        }
    }

    public int getOffset() {
        return offset;
    }

    public int getRowCount() {
        return rows.size();
    }

    public int getVisibleRowCount() {
        return visible;
    }

    public float getWidth() {
        return width;
    }

    public float getRowHeight() {
        return rowH;
    }

    /** Places the visible window of rows; hides and unpicks the rest. */
    private void layout() {
        float top = height * 0.5f;
        for (int i = 0; i < rows.size(); i++) {
            Component3D r = rows.get(i);
            int v = i - offset;
            if (v >= 0 && v < visible) {
                r.setTranslation(0.0f, top - (v + 0.5f) * rowH, 0.002f);
                r.changeVisible(true);
                r.setMouseEventEnabled(true);
            } else {
                r.changeVisible(false);
                r.setMouseEventEnabled(false);
            }
        }
    }
}
