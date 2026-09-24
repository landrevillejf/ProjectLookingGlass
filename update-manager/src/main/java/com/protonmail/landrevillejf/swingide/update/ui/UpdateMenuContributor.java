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
package com.protonmail.landrevillejf.swingide.update.ui;

import lombok.extern.slf4j.Slf4j;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import java.util.List;

/**
 * Builds the update entries of a Help menu.
 * <p>
 * The plugin host shell and the legacy main window both contribute the same
 * three items, and the plugin API is deliberately not involved: this is a plain
 * Swing helper, so neither host needs a {@code MenuProvider} and no plugin JAR
 * has to be rebuilt.
 * </p>
 *
 * @author landrevillejf
 * @version 1.0.0
 * @since 1.0.0
 */
@Slf4j
public final class UpdateMenuContributor {

    /** Label of the manual update check. */
    public static final String CHECK_LABEL = "Check for Updates...";

    /** Label of the update settings form. */
    public static final String SETTINGS_LABEL = "Update Settings...";

    /** Label of the changelog of the running version. */
    public static final String CHANGELOG_LABEL = "What's New";

    /** Tooltip shown when the update service could not be created. */
    private static final String UNAVAILABLE_TOOLTIP = "Update service unavailable";

    private UpdateMenuContributor() {
        // Static helper only.
    }

    /**
     * Creates the three update menu items, bound to the given presenter.
     * <p>
     * The items are returned disabled when no presenter is available, so a host
     * whose update service failed to start still shows a complete Help menu.
     * </p>
     *
     * @param presenter the presenter driving the update flow, may be {@code null}
     * @return the check, settings and changelog items, in that order
     */
    public static List<JMenuItem> createMenuItems(UpdatePresenter presenter) {
        boolean available = presenter != null && presenter.getService() != null;

        if (!available) {
            log.warn("No update presenter, the update menu items are disabled");
        }

        JMenuItem check = createItem(CHECK_LABEL, available,
            event -> presenter.checkForUpdates());
        JMenuItem settings = createItem(SETTINGS_LABEL, available,
            event -> presenter.showSettings());
        JMenuItem changelog = createItem(CHANGELOG_LABEL, available,
            event -> presenter.showChangelog());

        return List.of(check, settings, changelog);
    }

    /**
     * Appends the three update items to a menu, typically Help.
     *
     * @param menu      the menu to complete, {@code null} is ignored
     * @param presenter the presenter driving the update flow, may be {@code null}
     */
    public static void addTo(JMenu menu, UpdatePresenter presenter) {
        if (menu == null) {
            log.warn("No menu to add the update items to");
            return;
        }
        createMenuItems(presenter).forEach(menu::add);
    }

    private static JMenuItem createItem(String label, boolean available,
                                        java.awt.event.ActionListener action) {
        JMenuItem item = new JMenuItem(label);
        item.setEnabled(available);
        if (available) {
            item.addActionListener(action);
        } else {
            item.setToolTipText(UNAVAILABLE_TOOLTIP);
        }
        return item;
    }
}
