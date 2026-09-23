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
package org.jdesktop.lg3d.apps.paint;

import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

/**
 * The vertical tool palette: one toggle button per {@link Tool}, laid out in two
 * columns with runtime-drawn icons and hint tooltips. Selecting a button makes it
 * the state's active tool and notifies the {@link Listener} so the canvas can swap
 * its cursor and status hint. The first tool (Brush) is selected by default.
 */
public class Toolbox extends JPanel {

    /** Notified when the user picks a different tool. */
    public interface Listener {
        void toolSelected(Tool tool);
    }

    private final List<Tool> tools = new ArrayList<Tool>();
    private final ButtonGroup group = new ButtonGroup();
    private final PaintState state;
    private final Listener listener;

    public Toolbox(PaintState state, Listener listener) {
        this.state = state;
        this.listener = listener;
        setLayout(new GridLayout(0, 2, 2, 2));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        addTool(new BrushTool());
        addTool(new PencilTool());
        addTool(new EraserTool());
        addTool(new SprayTool());
        addTool(new FillTool());
        addTool(new EyedropperTool());
        addTool(new LineTool());
        addTool(new RectangleTool());
        addTool(new EllipseTool());
        addTool(new PolygonTool());
        addTool(new FreeformTool());
        addTool(new TextTool());
        addTool(new SelectRectTool());
        addTool(new SelectLassoTool());

        if (!tools.isEmpty()) {
            selectTool(tools.get(0));
        }
    }

    private void addTool(final Tool tool) {
        tools.add(tool);
        JToggleButton b = new JToggleButton(tool.getIcon());
        b.setToolTipText(tool.getHint());
        b.setMargin(new Insets(2, 2, 2, 2));
        b.setFocusPainted(false);
        b.setActionCommand(tool.getName());
        b.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                selectTool(tool);
            }
        });
        group.add(b);
        add(b);
    }

    /** Makes {@code tool} active (button, state and listener). */
    public void selectTool(Tool tool) {
        if (tool == null) {
            return;
        }
        state.setTool(tool);
        java.awt.Component[] comps = getComponents();
        for (int i = 0; i < comps.length && i < tools.size(); i++) {
            if (tools.get(i) == tool && comps[i] instanceof JToggleButton) {
                ((JToggleButton) comps[i]).setSelected(true);
            }
        }
        if (listener != null) {
            listener.toolSelected(tool);
        }
    }

    public List<Tool> getTools() {
        return tools;
    }
}
