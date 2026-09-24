/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.awt.BorderLayout;
import java.awt.event.KeyEvent;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.MenuModel;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DStartMenu.Launcher;

/**
 * The 2D start menu with a type-to-search field across the top.
 *
 * <p>Structurally it is one {@link JPopupMenu}: a header panel holding the
 * search {@link JTextField} at index 0, a separator, then a content region that
 * shows either the normal category tree (blank query) or a flat, ranked list of
 * matches (non-blank query) produced by {@link AppSearch}. Typing filters live;
 * clearing the field restores the tree. {@code Enter} launches the top match and
 * {@code Esc} closes the popup.</p>
 *
 * <p>The popup is made focusable so the field can receive keystrokes, and focus
 * is moved to it (and the query reset) each time the menu is shown, so every
 * open starts from the full tree.</p>
 *
 * <p>The filtering/rebuild path ({@link #applyQuery}) and the match count
 * ({@link #resultCount}) are package-private so they can be exercised headless
 * without showing the popup; the matching itself lives in {@link AppSearch}.</p>
 */
final class StartMenuSearch {

    /** Shown when a non-blank query matched nothing. */
    static final String NO_MATCH_LABEL = "(no matching applications)";

    /** Columns of the search field (its preferred width). */
    private static final int FIELD_COLUMNS = 20;

    private static final String CLOSE_ACTION = "closeStartMenu";

    private final MenuModel model;
    private final Launcher launcher;
    private final JPopupMenu menu;
    private final JTextField field;

    /** Index of the first content component (just past header + separator). */
    private final int contentStart;

    /** The matches for the current query; empty while showing the tree. */
    private List<ItemSpec> matches = List.of();

    StartMenuSearch(MenuModel model, Launcher launcher) {
        this.model = model;
        this.launcher = launcher;
        this.menu = new JPopupMenu();
        this.menu.setFocusable(true);

        JPanel header = new JPanel(new BorderLayout());
        this.field = new JTextField(FIELD_COLUMNS);
        this.field.setFocusable(true);
        header.add(field, BorderLayout.CENTER);
        menu.add(header);
        menu.addSeparator();
        this.contentStart = menu.getComponentCount();

        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyQuery(field.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyQuery(field.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyQuery(field.getText());
            }
        });
        // Enter launches the best match; Esc closes the popup.
        field.addActionListener(e -> launchTopMatch());
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                CLOSE_ACTION);
        field.getActionMap().put(CLOSE_ACTION, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                menu.setVisible(false);
            }
        });

        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                // Start each open from the full tree and put the caret in the
                // field so the user can just type.
                field.setText("");
                SwingUtilities.invokeLater(() -> field.requestFocusInWindow());
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                // nothing to do
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                // nothing to do
            }
        });

        // Initial state: blank query -> the normal category tree.
        applyQuery("");
    }

    /** The popup to show from the taskbar start button. */
    JPopupMenu menu() {
        return menu;
    }

    /** Number of matches for the current (non-blank) query; 0 for the tree. */
    int resultCount() {
        return matches.size();
    }

    /** Components currently in the content region (tree entries or matches). */
    int contentCount() {
        return menu.getComponentCount() - contentStart;
    }

    /**
     * Rebuilds the content region for {@code rawQuery}: blank shows the category
     * tree, otherwise the ranked {@link AppSearch} matches (or a disabled
     * "no match" row). Package-private for headless tests.
     */
    void applyQuery(String rawQuery) {
        String query = (rawQuery == null) ? "" : rawQuery.trim();
        clearContent();
        if (query.isEmpty()) {
            matches = List.of();
            Desktop2DStartMenu.appendTree(menu, model, launcher);
        } else {
            matches = AppSearch.match(model.getItems(), query);
            if (matches.isEmpty()) {
                JMenuItem none = new JMenuItem(NO_MATCH_LABEL);
                none.setEnabled(false);
                menu.add(none);
            } else {
                for (ItemSpec item : matches) {
                    JMenuItem entry = Desktop2DStartMenu.createItem(item, launcher);
                    if (entry != null) {
                        menu.add(entry);
                    }
                }
            }
        }
        menu.revalidate();
        menu.repaint();
    }

    /** Launches the best current match; package-private for headless tests. */
    void launchTopMatch() {
        if (!matches.isEmpty()) {
            launcher.launch(matches.get(0));
            menu.setVisible(false);
        }
    }

    private void clearContent() {
        for (int i = menu.getComponentCount() - 1; i >= contentStart; i--) {
            menu.remove(i);
        }
    }
}
