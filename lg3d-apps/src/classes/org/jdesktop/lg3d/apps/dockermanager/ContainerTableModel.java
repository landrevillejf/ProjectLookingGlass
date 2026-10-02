package org.jdesktop.lg3d.apps.dockermanager;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

// Table Models
public class ContainerTableModel extends AbstractTableModel {
    private final String[] columnNames = {"ID", "Name", "Image", "Status", "Ports", "Created"};
    private List<ContainerInfo> containers = new ArrayList<>();

    @Override
    public int getRowCount() {
        return containers.size();
    }

    @Override
    public int getColumnCount() {
        return columnNames.length;
    }

    @Override
    public String getColumnName(int column) {
        return columnNames[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        ContainerInfo container = containers.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> container.id.substring(0, 12);
            case 1 -> container.name;
            case 2 -> container.image;
            case 3 -> container.status;
            case 4 -> container.ports;
            case 5 -> container.created;
            default -> null;
        };
    }

    public ContainerInfo getContainer(int row) {
        return containers.get(row);
    }

    public void setContainers(List<ContainerInfo> containers) {
        this.containers = containers;
        fireTableDataChanged();
    }
}
