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
package org.jdesktop.lg3d.apps.mail;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.swing.table.AbstractTableModel;

/**
 * The message-list table model: one row per {@link MailMessage} envelope with the
 * columns the list shows (read / flagged / attachment indicators, From, Subject,
 * Date). Sorting is delegated to a {@code TableRowSorter} over these columns, so
 * the model only has to expose comparable values (the date column returns a
 * {@code Long} epoch so it sorts chronologically, not lexically).
 */
final class MailTableModel extends AbstractTableModel {

    static final int COL_READ = 0;
    static final int COL_FLAG = 1;
    static final int COL_ATTACH = 2;
    static final int COL_FROM = 3;
    static final int COL_SUBJECT = 4;
    static final int COL_DATE = 5;

    private static final String[] COLUMNS = {
        "\u25cf", "\u2691", "\uD83D\uDCCE", "From", "Subject", "Date"
    };

    private final List<MailMessage> rows = new ArrayList<MailMessage>();
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm");

    void setMessages(List<MailMessage> messages) {
        rows.clear();
        if (messages != null) {
            rows.addAll(messages);
        }
        fireTableDataChanged();
    }

    MailMessage getMessageAt(int viewRow) {
        if (viewRow < 0 || viewRow >= rows.size()) {
            return null;
        }
        return rows.get(viewRow);
    }

    int indexOf(MailMessage m) {
        return rows.indexOf(m);
    }

    int rowCount() {
        return rows.size();
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        switch (column) {
            case COL_READ:
            case COL_FLAG:
            case COL_ATTACH:
                return Boolean.class;
            case COL_DATE:
                return Long.class;
            default:
                return String.class;
        }
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        MailMessage m = rows.get(rowIndex);
        switch (columnIndex) {
            case COL_READ:
                return !m.isRead();          // dot shows on UNread messages
            case COL_FLAG:
                return m.isFlagged();
            case COL_ATTACH:
                return m.hasAttachments();
            case COL_FROM:
                return m.getFrom().display();
            case COL_SUBJECT:
                return m.getSubject().isEmpty() ? "(no subject)" : m.getSubject();
            case COL_DATE:
                return m.getWhen();
            default:
                return "";
        }
    }

    /** Formats an epoch-millis value the way the Date column tooltip shows it. */
    String formatDate(long millis) {
        return dateFormat.format(new Date(millis));
    }
}
