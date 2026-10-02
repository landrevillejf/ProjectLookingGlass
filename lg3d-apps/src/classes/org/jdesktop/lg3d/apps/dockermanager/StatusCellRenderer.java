package org.jdesktop.lg3d.apps.dockermanager;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;

// Cell Renderer for Status
public class StatusCellRenderer extends DefaultTableCellRenderer {
    // Colors
    private final Color SUCCESS_COLOR = new Color(34, 139, 34);
    private final Color ERROR_COLOR = new Color(224, 53, 69);
    private final Color INFO_COLOR = new Color(13, 110, 253);

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
                                                   boolean isSelected, boolean hasFocus, int row, int column) {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

        setHorizontalAlignment(CENTER);

        if (value != null) {
            String status = value.toString().toLowerCase();
            if (status.contains("up") || status.contains("running")) {
                setBackground(SUCCESS_COLOR);
                setForeground(Color.WHITE);
            } else if (status.contains("exited") || status.contains("stopped")) {
                setBackground(ERROR_COLOR);
                setForeground(Color.WHITE);
            } else {
                setBackground(INFO_COLOR);
                setForeground(Color.WHITE);
            }
        }

        return this;
    }
}
