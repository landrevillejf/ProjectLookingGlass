/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.apps.paint;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

/**
 * A reusable live-preview adjustments dialog. It shows a downscaled copy of the
 * source and a row of labelled sliders; every slider change rebuilds an
 * {@link ImageOps.Filter} through the supplied {@link FilterFactory} and repaints
 * the preview. On OK, {@link #getFilter()} returns the filter for the caller to
 * apply at full resolution to the real layer(s).
 */
public class FilterDialog extends JDialog {

    /** Builds a filter from the current slider values (keyed by param key). */
    public interface FilterFactory {
        ImageOps.Filter build(Map<String, Integer> values);
    }

    /** One labelled integer slider. */
    public static final class Param {
        final String key;
        final String label;
        final int min;
        final int max;
        final int initial;

        public Param(String key, String label, int min, int max, int initial) {
            this.key = key;
            this.label = label;
            this.min = min;
            this.max = max;
            this.initial = initial;
        }
    }

    private static final int PREVIEW_MAX = 220;

    private final Map<String, JSlider> sliders =
            new LinkedHashMap<String, JSlider>();
    private final BufferedImage previewBase;
    private final JLabel previewLabel = new JLabel();
    private final FilterFactory factory;
    private boolean approved;

    public FilterDialog(Window owner, String title, BufferedImage source,
            Param[] params, FilterFactory factory) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.factory = factory;
        this.previewBase = makePreview(source);
        buildUI(params);
        refreshPreview();
        pack();
        setLocationRelativeTo(owner);
    }

    private static BufferedImage makePreview(BufferedImage source) {
        if (source == null) {
            return ImageOps.newImage(1, 1);
        }
        int w = source.getWidth();
        int h = source.getHeight();
        double scale = Math.min(1.0,
                (double) PREVIEW_MAX / Math.max(w, h));
        int pw = Math.max(1, (int) Math.round(w * scale));
        int ph = Math.max(1, (int) Math.round(h * scale));
        return ImageOps.scale(source, pw, ph);
    }

    private void buildUI(Param[] params) {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        previewLabel.setHorizontalAlignment(JLabel.CENTER);
        previewLabel.setBorder(BorderFactory.createLineBorder(java.awt.Color.GRAY));
        JPanel previewWrap = new JPanel(new FlowLayout(FlowLayout.CENTER));
        previewWrap.add(previewLabel);
        root.add(previewWrap, BorderLayout.CENTER);

        JPanel sliderPanel = new JPanel(new GridLayout(0, 1, 4, 4));
        for (Param p : params) {
            JPanel row = new JPanel(new BorderLayout());
            JLabel lab = new JLabel(p.label);
            lab.setPreferredSize(new java.awt.Dimension(110, 20));
            final JSlider slider = new JSlider(p.min, p.max, p.initial);
            slider.setPaintTicks(false);
            slider.addChangeListener(new ChangeListener() {
                public void stateChanged(ChangeEvent e) {
                    refreshPreview();
                }
            });
            sliders.put(p.key, slider);
            row.add(lab, BorderLayout.WEST);
            row.add(slider, BorderLayout.CENTER);
            final JLabel val = new JLabel(String.valueOf(p.initial));
            val.setPreferredSize(new java.awt.Dimension(45, 20));
            slider.addChangeListener(new ChangeListener() {
                public void stateChanged(ChangeEvent e) {
                    val.setText(String.valueOf(slider.getValue()));
                }
            });
            row.add(val, BorderLayout.EAST);
            sliderPanel.add(row);
        }
        root.add(sliderPanel, BorderLayout.SOUTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                approved = true;
                dispose();
            }
        });
        cancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        buttons.add(ok);
        buttons.add(cancel);
        getRootPane().setDefaultButton(ok);

        setLayout(new BorderLayout());
        add(root, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
    }

    private Map<String, Integer> values() {
        Map<String, Integer> v = new HashMap<String, Integer>();
        for (Map.Entry<String, JSlider> e : sliders.entrySet()) {
            v.put(e.getKey(), e.getValue().getValue());
        }
        return v;
    }

    private void refreshPreview() {
        if (previewBase == null || factory == null) {
            return;
        }
        try {
            ImageOps.Filter f = factory.build(values());
            BufferedImage out = f.apply(previewBase);
            previewLabel.setIcon(new ImageIcon(out));
        } catch (RuntimeException ex) {
            // A preview failure must not break the dialog; leave the last image.
        }
    }

    public boolean showDialog() {
        approved = false;
        setVisible(true);
        return approved;
    }

    /** The filter described by the current slider values, for full-res apply. */
    public ImageOps.Filter getFilter() {
        return factory.build(values());
    }
}
