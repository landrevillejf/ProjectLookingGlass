package org.jdesktop.lg3d.apps.dockermanager;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

public class ImageTableModel extends AbstractTableModel {
    private final String[] columnNames = {"Repository", "Tag", "Image ID", "Created", "Size"};
    private List<ImageInfo> images = new ArrayList<>();

    @Override
    public int getRowCount() {
        return images.size();
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
        ImageInfo image = images.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> image.repository;
            case 1 -> image.tag;
            case 2 -> image.id.substring(0, 12);
            case 3 -> image.created;
            case 4 -> image.size;
            default -> null;
        };
    }

    public ImageInfo getImage(int row) {
        return images.get(row);
    }

    public void setImages(List<ImageInfo> images) {
        this.images = images;
        fireTableDataChanged();
    }
}
