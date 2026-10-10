/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

/**
 * The "Customize Toolbar" card: an in-panel editor for the user's toolbar
 * button selection, modelled after {@link ExtensionsCard}. The left half lists
 * every currently-available extension command (grouped label {@code Provider:
 * label}), the right half lists the commands chosen for the toolbar in order.
 * Add / Remove move a command between the halves, Up / Down reorder the active
 * list, and a small {@link JList} sets the display mode (icon / text / both) of
 * the selected active button. Apply hands the ordered selection back to the
 * host (which persists it and rebuilds the toolbar); Back returns to the editor
 * without saving.
 *
 * <p>Like every secondary Espresso surface it is built only from {@code JList}s
 * and {@code JButton}s &mdash; never a combo box or a dialog &mdash; so it
 * composites correctly into the 3D desktop's offscreen {@code SwingNode}
 * capture. All state lives in a working draft {@link ToolbarButtonConfig}; the
 * host is touched only on Apply, so cancelling is free. EDT-only.</p>
 */
final class ToolbarCustomizeCard extends JPanel {

    /** The panel-side callbacks for the toolbar-customise card. */
    interface Host {

        /** @return every command the toolbar can currently be given, in order. */
        List<AvailableCommand> availableCommands();

        /** @return the user's current ordered toolbar selection. */
        List<ToolbarButtonConfig.Entry> currentToolbar();

        /** Persists {@code entries} as the toolbar selection and rebuilds it. */
        void applyToolbar(List<ToolbarButtonConfig.Entry> entries);

        /** Returns to the editor card. */
        void closeCard();
    }

    /**
     * One selectable command. {@code id} is the stable
     * {@code providerId/contributionId} key the config stores; {@code provider}
     * and {@code category} are display metadata; {@code label} is the command's
     * own button text.
     */
    record AvailableCommand(String id, String provider, String category, String label) {

        /** The list-cell text: {@code "Provider: label"}. */
        String display() {
            return provider + ": " + label;
        }
    }

    /** The display-mode choices, in presentation order, labelled for the UI. */
    private static final String[] MODE_LABELS = {"Icon", "Text", "Icon + Text"};

    private final Host host;
    private final List<AvailableCommand> allAvailable = new ArrayList<>();
    /** The working selection; committed to the host only on Apply. */
    private final ToolbarButtonConfig draft = ToolbarButtonConfig.defaults();

    private final DefaultListModel<AvailableCommand> availableModel = new DefaultListModel<>();
    private final JList<AvailableCommand> availableList = new JList<>(availableModel);
    private final JList<Integer> activeList = new JList<>();
    private final DefaultListModel<Integer> activeIndexModel = new DefaultListModel<>();

    private final JButton addButton = new JButton("Add \u2192");
    private final JButton removeButton = new JButton("\u2190 Remove");
    private final JButton upButton = new JButton("Up");
    private final JButton downButton = new JButton("Down");
    private final JButton applyButton = new JButton("Apply");
    private final JList<String> modeList = new JList<>(MODE_LABELS);
    /** Guards the active-selection &harr; mode-selection listener feedback loop. */
    private boolean syncingMode;

    ToolbarCustomizeCard(Host host) {
        this.host = host;
        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        setName("toolbarCustomizeCard");

        availableList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        availableList.setVisibleRowCount(14);
        availableList.setCellRenderer(new AvailableRenderer());
        // The Add button's enablement is driven off this selection: without it the
        // button stays greyed out after a click on an available command, and the
        // whole card looks dead even though a row is selected.
        availableList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                refreshButtons();
            }
        });

        activeList.setModel(activeIndexModel);
        activeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        activeList.setVisibleRowCount(14);
        activeList.setCellRenderer(new ActiveRenderer());
        activeList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                refreshButtons();
                syncModeSelection();
            }
        });

        modeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        modeList.setVisibleRowCount(3);
        modeList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                applyModeToSelected();
            }
        });

        addButton.addActionListener(e -> addSelected());
        removeButton.addActionListener(e -> removeSelected());
        upButton.addActionListener(e -> moveSelected(-1));
        downButton.addActionListener(e -> moveSelected(+1));
        applyButton.addActionListener(e -> apply());
        JButton back = new JButton("Back to Editor");
        back.addActionListener(e -> host.closeCard());

        JPanel available = new JPanel(new BorderLayout(4, 4));
        available.add(new JLabel("Available Commands"), BorderLayout.NORTH);
        available.add(new JScrollPane(availableList), BorderLayout.CENTER);

        JPanel middle = new JPanel(new GridLayout(0, 1, 4, 4));
        middle.setBorder(BorderFactory.createEmptyBorder(24, 4, 24, 4));
        middle.add(addButton);
        middle.add(removeButton);

        JPanel activeColumn = new JPanel(new BorderLayout(4, 4));
        activeColumn.add(new JLabel("Toolbar (top to bottom)"), BorderLayout.NORTH);
        activeColumn.add(new JScrollPane(activeList), BorderLayout.CENTER);
        JPanel reorder = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        reorder.add(upButton);
        reorder.add(downButton);
        JPanel modeBox = new JPanel(new BorderLayout(0, 2));
        modeBox.add(new JLabel("Display mode of selected"), BorderLayout.NORTH);
        modeBox.add(new JScrollPane(modeList), BorderLayout.CENTER);
        JPanel activeSouth = new JPanel(new BorderLayout(0, 4));
        activeSouth.add(reorder, BorderLayout.NORTH);
        activeSouth.add(modeBox, BorderLayout.CENTER);
        activeColumn.add(activeSouth, BorderLayout.SOUTH);

        JPanel columns = new JPanel(new BorderLayout(6, 0));
        columns.add(available, BorderLayout.WEST);
        columns.add(middle, BorderLayout.CENTER);
        columns.add(activeColumn, BorderLayout.EAST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        buttons.add(applyButton);
        buttons.add(back);

        add(new JLabel("Choose which extension commands appear on the toolbar, "
                + "in order, and how each is drawn."), BorderLayout.NORTH);
        add(columns, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        refreshButtons();
    }

    /**
     * Rebuilds both halves from the host: the available list (minus anything
     * already chosen) and the active list from {@code current}. Preserves the
     * active selection by position where possible.
     */
    void show(List<AvailableCommand> available, ToolbarButtonConfig config) {
        allAvailable.clear();
        if (available != null) {
            allAvailable.addAll(available);
        }
        List<ToolbarButtonConfig.Entry> entries =
                (config != null) ? config.entries() : List.of();
        draft.replaceFrom(entries);
        reloadLists();
        cardsSelectionRestore(entries.size());
    }

    /** A convenience overload taking the entries list directly (test-friendly). */
    void show(List<AvailableCommand> available, List<ToolbarButtonConfig.Entry> entries) {
        ToolbarButtonConfig cfg = ToolbarButtonConfig.defaults();
        cfg.replaceFrom(entries);
        show(available, cfg);
    }

    private void cardsSelectionRestore(int size) {
        if (size > 0) {
            activeList.setSelectedIndex(0);
        } else if (!availableModel.isEmpty()) {
            // Nothing chosen yet: pre-select the first command so Add is live the
            // moment the card opens rather than after a hunting click.
            availableList.setSelectedIndex(0);
        }
        refreshButtons();
        syncModeSelection();
    }

    /** Recomputes both list models from {@link #allAvailable} and {@link #draft}. */
    private void reloadLists() {
        availableModel.clear();
        for (AvailableCommand cmd : allAvailable) {
            if (!draft.contains(cmd.id())) {
                availableModel.addElement(cmd);
            }
        }
        activeIndexModel.clear();
        for (int i = 0; i < draft.size(); i++) {
            activeIndexModel.addElement(i);
        }
    }

    /** @return the active-row cell label for draft index {@code i}. */
    private String activeLabel(int i) {
        ToolbarButtonConfig.Entry e = draft.get(i);
        if (e == null) {
            return "";
        }
        String provider = providerOf(e.id());
        String label = labelOf(e.id());
        return provider + ": " + label + "  [" + modeLabel(e.mode()) + "]";
    }

    private String providerOf(String id) {
        for (AvailableCommand c : allAvailable) {
            if (c.id().equals(id)) {
                return c.provider();
            }
        }
        return id;
    }

    private String labelOf(String id) {
        for (AvailableCommand c : allAvailable) {
            if (c.id().equals(id)) {
                return c.label();
            }
        }
        return id;
    }

    /** Adds the selected available command to the end of the draft. */
    private void addSelected() {
        AvailableCommand cmd = availableList.getSelectedValue();
        if (cmd != null && !draft.contains(cmd.id())) {
            draft.add(cmd.id(), ToolbarButtonConfig.DEFAULT_MODE);
            reloadLists();
            activeList.setSelectedIndex(draft.size() - 1);
            refreshButtons();
            syncModeSelection();
        }
    }

    /** Removes the selected active entry from the draft. */
    private void removeSelected() {
        int i = activeList.getSelectedIndex();
        if (i >= 0 && i < draft.size()) {
            draft.remove(draft.get(i).id());
            reloadLists();
            if (i < draft.size()) {
                activeList.setSelectedIndex(i);
            } else if (draft.size() > 0) {
                activeList.setSelectedIndex(draft.size() - 1);
            }
            refreshButtons();
            syncModeSelection();
        }
    }

    private void moveSelected(int delta) {
        int i = activeList.getSelectedIndex();
        if (i >= 0 && draft.move(i, delta)) {
            reloadLists();
            activeList.setSelectedIndex(Math.max(0, Math.min(i + delta, draft.size() - 1)));
            refreshButtons();
        }
    }

    private void applyModeToSelected() {
        if (syncingMode) {
            return;
        }
        int i = activeList.getSelectedIndex();
        int m = modeList.getSelectedIndex();
        if (i >= 0 && i < draft.size() && m >= 0 && m < ToolbarButtonConfig.DisplayMode.values().length) {
            draft.setMode(i, ToolbarButtonConfig.DisplayMode.values()[m]);
            // Repaint the row in place: reloading the model would reset the active
            // selection and re-enter this listener through syncModeSelection().
            activeList.repaint();
        }
    }

    private void syncModeSelection() {
        syncingMode = true;
        try {
            int i = activeList.getSelectedIndex();
            if (i >= 0 && i < draft.size()) {
                ToolbarButtonConfig.Entry e = draft.get(i);
                modeList.setSelectedIndex(e.mode().ordinal());
            } else {
                modeList.clearSelection();
            }
        } finally {
            syncingMode = false;
        }
    }

    private void apply() {
        host.applyToolbar(draft.entries());
        host.closeCard();
    }

    private void refreshButtons() {
        boolean hasAvailable = availableList.getSelectedIndex() >= 0;
        boolean hasActive = activeList.getSelectedIndex() >= 0;
        addButton.setEnabled(hasAvailable);
        removeButton.setEnabled(hasActive);
        upButton.setEnabled(hasActive);
        downButton.setEnabled(hasActive);
        modeList.setEnabled(hasActive);
    }

    // -- test seams ------------------------------------------------------------

    /** @return the working draft entries (test seam). */
    List<ToolbarButtonConfig.Entry> draftEntries() {
        return draft.entries();
    }

    /** @return how many unchosen commands are shown (test seam). */
    int availableCount() {
        return availableModel.size();
    }

    /** @return how many chosen buttons are shown (test seam). */
    int activeCount() {
        return draft.size();
    }

    /**
     * Selects an available command by list position (test seam). Deliberately only
     * moves the selection: the live {@code availableList} listener is what
     * re-enables Add, so the seam cannot shortcut the path the user takes.
     */
    void selectAvailable(int index) {
        if (index >= 0 && index < availableModel.size()) {
            availableList.setSelectedIndex(index);
        } else {
            availableList.clearSelection();
        }
    }

    /** Selects an active button by draft position (test seam; see {@link #selectAvailable}). */
    void selectActive(int index) {
        if (index >= 0 && index < draft.size()) {
            activeList.setSelectedIndex(index);
        } else {
            activeList.clearSelection();
            refreshButtons();
        }
    }

    /** @return true when Add is clickable (test seam). */
    boolean isAddEnabled() {
        return addButton.isEnabled();
    }

    /** @return true when Remove is clickable (test seam). */
    boolean isRemoveEnabled() {
        return removeButton.isEnabled();
    }

    /** Clicks Add for the current available selection (test seam). */
    void clickAdd() {
        addSelected();
    }

    /** Clicks Remove for the current active selection (test seam). */
    void clickRemove() {
        removeSelected();
    }

    /** Clicks Up / Down for the current active selection (test seam). */
    void clickMove(int delta) {
        moveSelected(delta);
    }

    /** Selects a display mode for the current active entry (test seam). */
    void chooseMode(ToolbarButtonConfig.DisplayMode mode) {
        if (mode != null) {
            modeList.setSelectedIndex(mode.ordinal());
        }
    }

    /** Clicks Apply, committing the draft to the host (test seam). */
    void clickApply() {
        apply();
    }

    private static String modeLabel(ToolbarButtonConfig.DisplayMode mode) {
        return switch (mode) {
            case ICON -> "Icon";
            case TEXT -> "Text";
            case ICON_TEXT -> "Icon + Text";
        };
    }

    /** Renders available commands, disabled when their provider is off. */
    private static final class AvailableRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value,
                int index, boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    l, value, index, selected, focus);
            AvailableCommand cmd = (AvailableCommand) value;
            label.setText(cmd.display());
            label.setToolTipText(cmd.category());
            return label;
        }
    }

    /** Renders active rows by their labelled draft position. */
    private final class ActiveRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value,
                int index, boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    l, value, index, selected, focus);
            Integer i = (Integer) value;
            label.setText(activeLabel(i));
            label.setFont(label.getFont().deriveFont(Font.PLAIN));
            return label;
        }
    }
}
