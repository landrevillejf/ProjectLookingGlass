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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import org.jdesktop.lg3d.apps.mail.MailAccount.CredentialMode;

/**
 * The tabbed settings dialog: <b>Accounts</b>, <b>Appearance</b>, <b>Rules</b> and
 * <b>Behaviour</b>. It is the single place the user customizes the client, and it
 * writes straight through to the persisted stores - {@link MailAccountStore} and
 * {@link MailRuleStore} for accounts/rules, {@link MailSettings} for the rest - so
 * the panel can simply re-read them after the dialog closes.
 *
 * <p>Following the project UI convention (SwingNode offscreen rendering on the 3D
 * desktop), enumerated choices are {@link JList} or {@link JRadioButton} selectors,
 * never combo boxes. Account and rule editing happen through the
 * {@link MailAccountDialog} and an inline {@link #editRule} form.</p>
 */
final class MailSettingsDialog extends JDialog {

    private final MailSessionManager manager;
    private final MailAccountStore accountStore;
    private final MailRuleStore ruleStore;
    private final MailSettings settings;

    // Accounts tab.
    private final DefaultListModel<MailAccount> accountModel =
            new DefaultListModel<MailAccount>();
    private final JList<MailAccount> accountList = new JList<MailAccount>(accountModel);

    // Appearance tab.
    private final JList<String> fontList;
    private final JSpinner listSize = new JSpinner(new SpinnerNumberModel(13, 8, 32, 1));
    private final JSpinner readerSize = new JSpinner(new SpinnerNumberModel(14, 8, 40, 1));
    private final JRadioButton themeLight = new JRadioButton("Light");
    private final JRadioButton themeDark = new JRadioButton("Dark");
    private final JRadioButton densityCompact = new JRadioButton("Compact");
    private final JRadioButton densityComfortable = new JRadioButton("Comfortable");
    private final JRadioButton paneRight = new JRadioButton("Right");
    private final JRadioButton paneBottom = new JRadioButton("Bottom");
    private final JRadioButton paneHidden = new JRadioButton("Hidden");
    private final JButton accentButton = new JButton();
    private int accentColor;

    // Rules tab.
    private final DefaultListModel<MailRule> ruleModel =
            new DefaultListModel<MailRule>();
    private final JList<MailRule> ruleList = new JList<MailRule>(ruleModel);
    private final List<MailRule> rules = new ArrayList<MailRule>();

    // Behaviour tab.
    private final JRadioButton sortDate = new JRadioButton("Date");
    private final JRadioButton sortFrom = new JRadioButton("From");
    private final JRadioButton sortSubject = new JRadioButton("Subject");
    private final JRadioButton sortDesc = new JRadioButton("Newest first");
    private final JRadioButton sortAsc = new JRadioButton("Oldest first");
    private final JSpinner checkInterval =
            new JSpinner(new SpinnerNumberModel(10, 0, 1440, 5));
    private final JCheckBox confirmDelete = new JCheckBox("Confirm before deleting");
    private final JCheckBox renderHtml =
            new JCheckBox("Render HTML mail (remote content is always blocked)");
    private final JCheckBox notifyNewMail =
            new JCheckBox("Notify on new mail (desktop toast)");

    private boolean committed;

    MailSettingsDialog(Frame owner, MailSessionManager manager,
            MailAccountStore accountStore, MailRuleStore ruleStore,
            MailSettings settings) {
        super(owner, "Mail settings", true);
        this.manager = manager;
        this.accountStore = accountStore;
        this.ruleStore = ruleStore;
        this.settings = settings;

        String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        fontList = new JList<String>(families);
        fontList.setVisibleRowCount(8);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Accounts", buildAccountsTab());
        tabs.addTab("Appearance", buildAppearanceTab());
        tabs.addTab("Rules", buildRulesTab());
        tabs.addTab("Behaviour", buildBehaviourTab());

        loadSettings();
        reloadAccounts();
        reloadRules();

        getContentPane().add(tabs, BorderLayout.CENTER);
        getContentPane().add(buildButtons(), BorderLayout.SOUTH);
        setSize(560, 520);
        setLocationRelativeTo(owner);
    }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    private JPanel buildAccountsTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        accountList.setVisibleRowCount(8);
        p.add(new JScrollPane(accountList), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(0, 1, 4, 4));
        buttons.add(button("Add...", e -> addAccount()));
        buttons.add(button("Edit...", e -> editAccount()));
        buttons.add(button("Remove", e -> removeAccount()));
        buttons.add(button("Set default", e -> setDefault()));
        JPanel east = new JPanel(new BorderLayout());
        east.add(buttons, BorderLayout.NORTH);
        p.add(east, BorderLayout.EAST);
        return p;
    }

    private JPanel buildAppearanceTab() {
        JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel fonts = new JPanel(new BorderLayout(6, 0));
        fonts.setBorder(BorderFactory.createTitledBorder("List font"));
        fonts.add(new JScrollPane(fontList), BorderLayout.CENTER);
        JPanel sizePanel = new JPanel(new BorderLayout(4, 0));
        sizePanel.add(new JLabel("Size:"), BorderLayout.WEST);
        sizePanel.add(listSize, BorderLayout.CENTER);
        fonts.add(sizePanel, BorderLayout.SOUTH);
        p.add(fonts);

        JPanel sizes = new JPanel(new FlowLayout(FlowLayout.LEFT));
        sizes.add(new JLabel("Reader font size:"));
        sizes.add(readerSize);
        p.add(sizes);

        p.add(radioRow("Theme:", themeLight, themeDark));
        p.add(radioRow("List density:", densityCompact, densityComfortable));
        p.add(radioRow("Reading pane:", paneRight, paneBottom, paneHidden));

        JPanel accent = new JPanel(new FlowLayout(FlowLayout.LEFT));
        accentButton.setPreferredSize(new java.awt.Dimension(90, 26));
        accentButton.addActionListener(e -> chooseAccent());
        accent.add(new JLabel("Accent colour:"));
        accent.add(accentButton);
        p.add(accent);
        return p;
    }

    private JPanel buildRulesTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        ruleList.setVisibleRowCount(8);
        p.add(new JScrollPane(ruleList), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(0, 1, 4, 4));
        buttons.add(button("Add...", e -> addRule()));
        buttons.add(button("Edit...", e -> editSelectedRule()));
        buttons.add(button("Remove", e -> removeRule()));
        buttons.add(button("Move up", e -> moveRule(-1)));
        buttons.add(button("Move down", e -> moveRule(1)));
        JPanel east = new JPanel(new BorderLayout());
        east.add(buttons, BorderLayout.NORTH);
        p.add(east, BorderLayout.EAST);
        return p;
    }

    private JPanel buildBehaviourTab() {
        JPanel p = new JPanel(new GridLayout(0, 1, 6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        p.add(radioRow("Sort by:", sortDate, sortFrom, sortSubject));
        p.add(radioRow("Order:", sortDesc, sortAsc));

        JPanel check = new JPanel(new FlowLayout(FlowLayout.LEFT));
        check.add(new JLabel("Auto-check every (minutes, 0 = off):"));
        check.add(checkInterval);
        p.add(check);

        p.add(confirmDelete);
        p.add(renderHtml);
        p.add(notifyNewMail);
        return p;
    }

    private JPanel buildButtons() {
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> {
            commit();
            committed = true;
            dispose();
        });
        south.add(cancel);
        south.add(ok);
        return south;
    }

    // ------------------------------------------------------------------
    // Small widget helpers
    // ------------------------------------------------------------------

    private static JButton button(String label,
            java.awt.event.ActionListener a) {
        JButton b = new JButton(label);
        b.addActionListener(a);
        return b;
    }

    private static JPanel radioRow(String label, JRadioButton... buttons) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        row.add(new JLabel(label));
        for (JRadioButton b : buttons) {
            group.add(b);
            row.add(b);
        }
        return row;
    }

    private void chooseAccent() {
        Color c = JColorChooser.showDialog(this, "Accent colour",
                new Color(accentColor));
        if (c != null) {
            accentColor = c.getRGB() & 0xFFFFFF;
            paintAccent();
        }
    }

    private void paintAccent() {
        accentButton.setBackground(new Color(accentColor));
        accentButton.setText("#" + Integer.toHexString(accentColor).toUpperCase());
    }

    // ------------------------------------------------------------------
    // Load / commit
    // ------------------------------------------------------------------

    private void loadSettings() {
        listSize.setValue(settings.getListFontSize());
        readerSize.setValue(settings.getReaderFontSize());
        fontList.setSelectedValue(settings.getListFontFamily(), true);
        (settings.getTheme() == MailSettings.Theme.DARK ? themeDark : themeLight)
                .setSelected(true);
        (settings.getDensity() == MailSettings.Density.COMPACT
                ? densityCompact : densityComfortable).setSelected(true);
        switch (settings.getReadingPanePosition()) {
            case BOTTOM: paneBottom.setSelected(true); break;
            case HIDDEN: paneHidden.setSelected(true); break;
            default: paneRight.setSelected(true); break;
        }
        switch (settings.getSortColumn()) {
            case FROM: sortFrom.setSelected(true); break;
            case SUBJECT: sortSubject.setSelected(true); break;
            default: sortDate.setSelected(true); break;
        }
        (settings.isSortDescending() ? sortDesc : sortAsc).setSelected(true);
        checkInterval.setValue(settings.getCheckIntervalMinutes());
        confirmDelete.setSelected(settings.isConfirmOnDelete());
        renderHtml.setSelected(settings.isRenderHtml());
        notifyNewMail.setSelected(settings.isNotifyOnNewMail());
        accentColor = settings.getAccentColor();
        paintAccent();
    }

    private void commit() {
        String family = fontList.getSelectedValue();
        if (family != null) {
            settings.setListFontFamily(family);
            settings.setReaderFontFamily(family);
        }
        settings.setListFontSize((Integer) listSize.getValue());
        settings.setReaderFontSize((Integer) readerSize.getValue());
        settings.setTheme(themeDark.isSelected()
                ? MailSettings.Theme.DARK : MailSettings.Theme.LIGHT);
        settings.setDensity(densityCompact.isSelected()
                ? MailSettings.Density.COMPACT : MailSettings.Density.COMFORTABLE);
        settings.setReadingPanePosition(paneBottom.isSelected()
                ? MailSettings.ReadingPanePosition.BOTTOM
                : paneHidden.isSelected()
                        ? MailSettings.ReadingPanePosition.HIDDEN
                        : MailSettings.ReadingPanePosition.RIGHT);
        settings.setSortColumn(sortFrom.isSelected()
                ? MailSettings.SortColumn.FROM
                : sortSubject.isSelected()
                        ? MailSettings.SortColumn.SUBJECT
                        : MailSettings.SortColumn.DATE);
        settings.setSortDescending(sortDesc.isSelected());
        settings.setCheckIntervalMinutes((Integer) checkInterval.getValue());
        settings.setConfirmOnDelete(confirmDelete.isSelected());
        settings.setRenderHtml(renderHtml.isSelected());
        settings.setNotifyOnNewMail(notifyNewMail.isSelected());
        settings.setAccentColor(accentColor);
        settings.save();
        ruleStore.saveAll(rules);
    }

    boolean isCommitted() {
        return committed;
    }

    // ------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------

    private void reloadAccounts() {
        accountModel.clear();
        for (MailAccount a : accountStore.load()) {
            accountModel.addElement(a);
        }
    }

    private void addAccount() {
        MailAccountDialog d = new MailAccountDialog(
                (Frame) getOwner(), null, manager);
        d.setVisible(true);
        if (d.isSaved()) {
            persistAccount(d.account(), d.typedPassword());
        }
    }

    private void editAccount() {
        MailAccount sel = accountList.getSelectedValue();
        if (sel == null) {
            return;
        }
        MailAccountDialog d = new MailAccountDialog(
                (Frame) getOwner(), sel, manager);
        d.setVisible(true);
        if (d.isSaved()) {
            persistAccount(d.account(), d.typedPassword());
        }
    }

    private void persistAccount(MailAccount account, String password) {
        accountStore.save(account);
        if (account.getCredentialMode() == CredentialMode.SAVED
                && password != null && !password.isEmpty()) {
            accountStore.setPassword(account.getId(), password);
        }
        manager.invalidate(account.getId());
        reloadAccounts();
    }

    private void removeAccount() {
        MailAccount sel = accountList.getSelectedValue();
        if (sel == null) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "Remove account " + sel.getDisplayLabel() + "?",
                "Remove account", JOptionPane.OK_CANCEL_OPTION);
        if (choice == JOptionPane.OK_OPTION) {
            accountStore.delete(sel.getId());
            manager.invalidate(sel.getId());
            reloadAccounts();
        }
    }

    private void setDefault() {
        MailAccount sel = accountList.getSelectedValue();
        if (sel == null) {
            return;
        }
        sel.setDefaultAccount(true);
        accountStore.save(sel);
        reloadAccounts();
    }

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    private void reloadRules() {
        rules.clear();
        rules.addAll(ruleStore.load());
        ruleModel.clear();
        for (MailRule r : rules) {
            ruleModel.addElement(r);
        }
    }

    private void addRule() {
        MailRule r = editRule(null);
        if (r != null) {
            rules.add(r);
            ruleModel.addElement(r);
        }
    }

    private void editSelectedRule() {
        int i = ruleList.getSelectedIndex();
        if (i < 0) {
            return;
        }
        MailRule updated = editRule(rules.get(i));
        if (updated != null) {
            rules.set(i, updated);
            ruleModel.set(i, updated);
        }
    }

    private void removeRule() {
        int i = ruleList.getSelectedIndex();
        if (i >= 0) {
            rules.remove(i);
            ruleModel.remove(i);
        }
    }

    private void moveRule(int delta) {
        int i = ruleList.getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= rules.size()) {
            return;
        }
        MailRule r = rules.remove(i);
        rules.add(j, r);
        ruleModel.remove(i);
        ruleModel.add(j, r);
        ruleList.setSelectedIndex(j);
    }

    /**
     * Inline rule editor: field / match / action radio groups, a value and (for
     * MOVE) a target folder. Returns a new {@link MailRule} on OK, or null.
     */
    private MailRule editRule(MailRule existing) {
        JTextField value = new JTextField(16);
        JTextField target = new JTextField(12);
        JCheckBox enabled = new JCheckBox("Enabled", true);

        JRadioButton[] fields = radios("From", "Subject", "To");
        JRadioButton[] matches = radios("Contains", "Equals", "Regex");
        JRadioButton[] actions = radios("Move to folder", "Mark read", "Flag", "Delete");

        if (existing != null) {
            value.setText(existing.getValue());
            target.setText(existing.getTargetFolder());
            enabled.setSelected(existing.isEnabled());
            fields[existing.getField().ordinal()].setSelected(true);
            matches[existing.getMatch().ordinal()].setSelected(true);
            actions[existing.getAction().ordinal()].setSelected(true);
        } else {
            fields[0].setSelected(true);
            matches[0].setSelected(true);
            actions[1].setSelected(true);
        }

        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
        panel.add(enabled);
        panel.add(labeledGroup("Field:", fields));
        panel.add(labeledGroup("Match:", matches));
        panel.add(field("Value:", value));
        panel.add(labeledGroup("Action:", actions));
        panel.add(field("Target folder:", target));

        int choice = JOptionPane.showConfirmDialog(this, panel,
                existing == null ? "New rule" : "Edit rule",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return null;
        }
        MailRule r = (existing == null) ? new MailRule(null) : existing;
        r.setEnabled(enabled.isSelected());
        r.setField(MailRule.Field.values()[selectedIndex(fields)]);
        r.setMatch(MailRule.Match.values()[selectedIndex(matches)]);
        r.setValue(value.getText());
        r.setAction(MailRule.Action.values()[selectedIndex(actions)]);
        r.setTargetFolder(target.getText());
        return r;
    }

    private static JRadioButton[] radios(String... labels) {
        JRadioButton[] out = new JRadioButton[labels.length];
        javax.swing.ButtonGroup g = new javax.swing.ButtonGroup();
        for (int i = 0; i < labels.length; i++) {
            out[i] = new JRadioButton(labels[i]);
            g.add(out[i]);
        }
        return out;
    }

    private static int selectedIndex(JRadioButton[] buttons) {
        for (int i = 0; i < buttons.length; i++) {
            if (buttons[i].isSelected()) {
                return i;
            }
        }
        return 0;
    }

    private static JPanel labeledGroup(String label, JRadioButton[] buttons) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(new JLabel(label));
        for (JRadioButton b : buttons) {
            row.add(b);
        }
        return row;
    }

    private static JPanel field(String label, Component c) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(new JLabel(label));
        row.add(c);
        return row;
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    /** Opens the dialog; returns true when the user pressed OK (settings applied). */
    static boolean show(Frame owner, MailSessionManager manager,
            MailAccountStore accountStore, MailRuleStore ruleStore,
            MailSettings settings) {
        MailSettingsDialog d = new MailSettingsDialog(owner, manager, accountStore,
                ruleStore, settings);
        d.setVisible(true);
        return d.isCommitted();
    }
}
