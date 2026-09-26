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
package org.jdesktop.lg3d.apps.controlcenter;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import org.jdesktop.lg3d.displayserver.desktop2d.LocaleStatus;

/**
 * Language &amp; Region panel: shows the current system {@code LANG} locale and
 * lets the user pick another from a {@link JList} (never a combo box, so the
 * panel keeps working hosted offscreen in a {@code SwingNode}). Everything goes
 * through the {@link LocaleStatus} seam, so on a host without systemd the panel
 * degrades to a clear read-only note rather than failing. Setting the locale
 * writes the system configuration (normally needing administrator privileges) and
 * takes effect for <em>new</em> login sessions, which the panel states.
 */
public class LocalePanel implements ControlPanel {

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final JLabel currentLabel = new JLabel(" ");
    private final JLabel warningLabel = new JLabel(" ");
    private final DefaultListModel<String> localeModel = new DefaultListModel<>();
    private final JList<String> localeList = new JList<>(localeModel);
    private final JButton setLocale = new JButton("Set locale");
    private final JButton refresh = new JButton("Refresh");
    private final JLabel statusLabel = new JLabel(" ");

    public LocalePanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        warningLabel.setForeground(new Color(160, 90, 20));

        localeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        localeList.setVisibleRowCount(14);
        JScrollPane scroll = new JScrollPane(localeList);
        scroll.setPreferredSize(new Dimension(340, 260));
        scroll.setBorder(BorderFactory.createTitledBorder("System locale (LANG)"));

        setLocale.addActionListener(e -> applyLocale());
        refresh.addActionListener(e -> reload());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(setLocale);
        buttons.add(refresh);

        JPanel north = new JPanel(new BorderLayout(2, 2));
        north.add(currentLabel, BorderLayout.NORTH);
        north.add(warningLabel, BorderLayout.SOUTH);

        JLabel note = new JLabel("Applies to new login sessions.");
        JPanel south = new JPanel(new BorderLayout(2, 2));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(buttons);
        row.add(note);
        south.add(row, BorderLayout.NORTH);
        south.add(statusLabel, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(scroll, BorderLayout.CENTER);

        root.add(north, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        reload();
    }

    @Override
    public String displayName() {
        return "Language & Region";
    }

    @Override
    public javax.swing.Icon icon() {
        return null;
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void onShow() {
        reload();
    }

    // ------------------------------------------------------------------

    private void reload() {
        if (!LocaleStatus.available()) {
            warningLabel.setText("localectl is not available; language and region settings are read-only.");
            currentLabel.setText(" ");
            localeModel.clear();
            localeList.setEnabled(false);
            setLocale.setEnabled(false);
            statusLabel.setText(" ");
            return;
        }
        warningLabel.setText(" ");
        localeList.setEnabled(true);
        setLocale.setEnabled(true);

        String lang = LocaleStatus.currentLang();
        currentLabel.setText("Current system locale (LANG): "
                + (lang.isEmpty() ? "(not set)" : lang));

        List<String> locales = LocaleStatus.listLocales();
        String keep = localeList.getSelectedValue();
        localeModel.clear();
        for (String l : locales) {
            localeModel.addElement(l);
        }
        int sel = localeIndex(locales, lang);
        if (sel < 0 && keep != null) {
            sel = locales.indexOf(keep);
        }
        if (sel >= 0 && sel < localeModel.size()) {
            localeList.setSelectedIndex(sel);
        }
        statusLabel.setText(locales.size() + " locale(s) available.");
    }

    private void applyLocale() {
        String locale = localeList.getSelectedValue();
        if (locale == null) {
            statusLabel.setText("Select a locale first.");
            return;
        }
        boolean ok = LocaleStatus.setLocale(locale);
        statusLabel.setText(ok
                ? "System locale set to " + locale + ". It applies to new login sessions."
                : "Could not set the locale (administrator privileges may be required).");
        reload();
    }

    /**
     * The list index to preselect for the current {@code lang}, or {@code -1} when
     * it is blank or not offered. Pure so it can be unit-tested headless.
     */
    static int localeIndex(List<String> locales, String lang) {
        if (locales == null || lang == null || lang.isEmpty()) {
            return -1;
        }
        return locales.indexOf(lang);
    }
}
