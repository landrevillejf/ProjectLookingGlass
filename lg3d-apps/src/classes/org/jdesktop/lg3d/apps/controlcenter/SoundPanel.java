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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import org.jdesktop.lg3d.displayserver.desktop2d.VolumeStatus;

/**
 * Sound panel: master output volume and mute, over the {@link VolumeStatus}
 * seam. The volume is a {@link JList} of decile presets (never a combo box or a
 * slider) so the panel keeps working when hosted offscreen in a {@code SwingNode}
 * on the 3D desktop; the same panel serves the 2D desktop. When no master audio
 * control exists (headless CI, no sound card) the panel degrades to a "no audio
 * device" note and Apply does nothing rather than failing.
 */
public class SoundPanel implements ControlPanel {

    /** The volume presets offered, in percent. */
    static final int[] VOLUME_PRESETS = {0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100};

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final DefaultListModel<String> volumes = new DefaultListModel<>();
    private final JList<String> volumeList = new JList<>(volumes);
    private final JCheckBox muteBox = new JCheckBox("Muted");
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton apply = new JButton("Apply");

    public SoundPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        for (int preset : VOLUME_PRESETS) {
            volumes.addElement(preset + "%");
        }
        volumeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        volumeList.setVisibleRowCount(6);
        JScrollPane scroll = new JScrollPane(volumeList);
        scroll.setPreferredSize(new Dimension(150, 160));
        scroll.setBorder(BorderFactory.createTitledBorder("Master volume"));

        JPanel muteWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        muteWrap.add(muteBox);

        JPanel center = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        center.add(scroll);
        center.add(muteWrap);

        apply.addActionListener(e -> applyVolume());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        south.add(apply);

        root.add(statusLabel, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        reload();
    }

    @Override
    public String displayName() {
        return "Sound";
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

    /** Re-reads the master volume, syncing the list, the mute box and status. */
    private void reload() {
        Optional<VolumeStatus.Level> level = VolumeStatus.read();
        if (level.isEmpty()) {
            volumeList.clearSelection();
            muteBox.setSelected(false);
            muteBox.setEnabled(false);
            apply.setEnabled(false);
            statusLabel.setText("No audio device (master volume unavailable).");
            return;
        }
        VolumeStatus.Level value = level.get();
        muteBox.setEnabled(true);
        apply.setEnabled(true);
        volumeList.setSelectedIndex(indexOfVolume(value.percent()));
        muteBox.setSelected(value.muted());
        statusLabel.setText(VolumeStatus.label(value));
    }

    /** Pushes the selected preset and the mute state to the master control. */
    private void applyVolume() {
        int index = volumeList.getSelectedIndex();
        if (index >= 0 && index < VOLUME_PRESETS.length) {
            VolumeStatus.setVolume(VOLUME_PRESETS[index]);
        }
        VolumeStatus.setMuted(muteBox.isSelected());
        reload();
    }

    /**
     * The preset index nearest {@code percent} (clamped), so a read volume of
     * e.g. 47% selects the 50% row. Pure so it can be unit-tested headless.
     */
    static int indexOfVolume(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        int index = Math.round(clamped / 10f);
        return Math.max(0, Math.min(VOLUME_PRESETS.length - 1, index));
    }
}
