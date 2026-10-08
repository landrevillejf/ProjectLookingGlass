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
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import org.jdesktop.lg3d.apps.controlcenter.ControlPanelRegistry.PanelDescriptor;

/**
 * The control center shell: a category navigation list on the left and a
 * {@link CardLayout} on the right showing the selected {@link ControlPanel}.
 * Categories are discovered from {@link ControlPanelRegistry}.
 *
 * <p>The navigation list is populated from the registry's cheap
 * {@link PanelDescriptor}s, so the window appears immediately with every
 * category listed. Each panel is then constructed <em>lazily</em>, the first
 * time its category is selected, behind a progress overlay (an indeterminate
 * bar plus a "Loading &lt;category&gt;" status line). Because several panels
 * block on platform I/O while they build (shelling out to {@code nmcli},
 * {@code bluetoothctl}, {@code timedatectl}, scanning the wallpaper directory,
 * reading {@code /proc}, and so on), the overlay is shown and painted
 * <em>before</em> the panel is constructed - the build is deferred to the next
 * event-dispatcher tick with a one-shot {@link Timer} - so the user always sees
 * which category is loading instead of a frozen, blank window. A category whose
 * panel cannot be built in this JVM shows a graceful "unavailable" card rather
 * than taking the whole control center down.</p>
 */
public class ControlCenterPanel extends JPanel {

    /** Card constraint for the "loading" progress overlay. */
    private static final String LOADING_KEY = "__loading__";
    /** Card constraint for a category that could not be built. */
    private static final String UNAVAILABLE_KEY = "__unavailable__";
    /** Delay (ms) before building a selected panel, so the overlay paints first. */
    private static final int BUILD_DELAY_MS = 30;

    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final DefaultListModel<PanelDescriptor> model = new DefaultListModel<>();
    private final JList<PanelDescriptor> nav = new JList<>(model);

    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel statusLabel = new JLabel(" ");

    /** Panels constructed so far, by category name (each added to the cards). */
    private final Map<String, ControlPanel> built = new HashMap<>();
    /** Categories whose panel could not be constructed in this JVM. */
    private final Set<String> unavailable = new HashSet<>();

    private ControlPanel current;
    private String currentName;
    private boolean loading;
    private Timer pendingBuild;

    public ControlCenterPanel() {
        super(new BorderLayout());
        // Must match ControlCenter.PANEL_H: TitledSwingWindow.show builds the
        // window chrome for that pixel height while setJPanel packs this panel
        // to its preferred size, so the two have to agree or the content and
        // the title bar / spines / thumbnail desync. 620 keeps the Appearance
        // panel's stacked sections fully visible without maximizing.
        setPreferredSize(new Dimension(720, 620));
        setBackground(new Color(238, 240, 244));

        cardPanel.add(buildLoadingCard(), LOADING_KEY);
        cardPanel.add(buildUnavailableCard(), UNAVAILABLE_KEY);

        // Listing the descriptors constructs no panel, so the navigation is
        // ready instantly and the window can be shown without waiting on I/O.
        for (PanelDescriptor d : ControlPanelRegistry.descriptors()) {
            model.addElement(d);
        }

        nav.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nav.setCellRenderer(new NavRenderer());
        nav.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                select(nav.getSelectedValue());
            }
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(nav), cardPanel);
        split.setDividerLocation(160);
        split.setResizeWeight(0.2);
        split.setBorder(BorderFactory.createEmptyBorder());

        add(split, BorderLayout.CENTER);

        cards.show(cardPanel, LOADING_KEY);
        if (model.isEmpty()) {
            statusLabel.setText("No categories are available.");
            progressBar.setIndeterminate(false);
        } else {
            nav.setSelectedIndex(0);
        }
    }

    /**
     * Shows {@code d}'s panel, constructing it on demand. The progress overlay
     * is painted before the (possibly blocking) construction so the user sees
     * "Loading &lt;category&gt;" rather than a frozen window.
     */
    private void select(PanelDescriptor d) {
        if (d == null) {
            return;
        }
        final String name = d.displayName();
        if (name.equals(currentName) && !loading) {
            return;
        }
        if (pendingBuild != null) {
            pendingBuild.stop();
            pendingBuild = null;
        }

        ControlPanel already = built.get(name);
        if (already != null) {
            showPanel(name, already);
            return;
        }
        if (unavailable.contains(name)) {
            showUnavailable(name);
            return;
        }

        currentName = name;
        loading = true;
        statusLabel.setText("Loading " + name + " \u2026");
        progressBar.setIndeterminate(true);
        cards.show(cardPanel, LOADING_KEY);
        cardPanel.revalidate();
        cardPanel.repaint();

        // Defer the build to the next EDT tick so the overlay above is painted
        // first; a panel whose constructor blocks on platform I/O then freezes
        // only itself, with its "Loading ..." status already on screen.
        pendingBuild = new Timer(BUILD_DELAY_MS, e -> {
            pendingBuild = null;
            ControlPanel p = d.get();
            if (p == null) {
                unavailable.add(name);
                showUnavailable(name);
            } else {
                built.put(name, p);
                cardPanel.add(p.component(), name);
                showPanel(name, p);
            }
            loading = false;
        });
        pendingBuild.setRepeats(false);
        pendingBuild.start();
    }

    private void showPanel(String name, ControlPanel p) {
        if (current != null && current != p) {
            current.onHide();
        }
        current = p;
        currentName = name;
        cards.show(cardPanel, name);
        p.onShow();
    }

    private void showUnavailable(String name) {
        if (current != null) {
            current.onHide();
            current = null;
        }
        currentName = name;
        cards.show(cardPanel, UNAVAILABLE_KEY);
    }

    /** The progress overlay shown while a category's panel is constructed. */
    private JPanel buildLoadingCard() {
        progressBar.setIndeterminate(true);
        progressBar.setPreferredSize(new Dimension(240, 18));
        progressBar.setMaximumSize(new Dimension(240, 18));
        progressBar.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel title = new JLabel("Control Center");
        title.setFont(title.getFont().deriveFont(Font.BOLD,
                title.getFont().getSize2D() + 3f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setOpaque(false);
        inner.add(title);
        inner.add(Box.createVerticalStrut(12));
        inner.add(statusLabel);
        inner.add(Box.createVerticalStrut(12));
        inner.add(progressBar);

        JPanel card = new JPanel(new GridBagLayout());
        card.setOpaque(false);
        card.add(inner);
        return card;
    }

    /** The card shown for a category that cannot be built in this JVM. */
    private JPanel buildUnavailableCard() {
        JLabel label = new JLabel(
                "<html><div style='text-align:center'>This category could not be"
                + "<br>opened in this desktop.</div></html>", SwingConstants.CENTER);
        label.setBorder(BorderFactory.createEmptyBorder(40, 20, 40, 20));
        JPanel card = new JPanel(new GridBagLayout());
        card.setOpaque(false);
        card.add(label);
        return card;
    }

    /** Navigation list renderer: the category display name. */
    private static final class NavRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            PanelDescriptor d = (PanelDescriptor) value;
            super.getListCellRendererComponent(list, d.displayName(), index,
                    isSelected, cellHasFocus);
            setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            return this;
        }
    }
}
