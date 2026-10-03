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
package org.jdesktop.lg3d.apps.tuner;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

/**
 * The "add a custom tuning" editor: a name field, a string-count spinner and one
 * note picker per string (low to high). Any tuning can be built this way - for
 * example Drop C is six strings set to C2 G2 C3 F3 A3 D4.
 *
 * <p>Like the sibling {@code AppearancePanel}, which is hosted in a
 * {@code SwingNode} the same way this panel is, the editor is shown with
 * {@link JOptionPane} rooted at the hosting panel; Swing resolves the owner
 * window from the component hierarchy, so it works both as a real top-level in
 * the 2D desktop and inside the 3D desktop's offscreen {@code SwingNode}.</p>
 *
 * <p>All the logic that turns the editor's inputs into a {@link Tuning} lives in
 * the pure statics ({@link #noteLabels()}, {@link #midiOf(String)},
 * {@link #labelOf(int)}, {@link #build(String, int[])}), which the headless tests
 * assert on without ever showing a dialog. Only {@link #show(Component)} touches
 * a window.</p>
 */
final class AddTuningDialog {

    /** Inclusive MIDI range offered by each note picker (C0..C8). */
    static final int MIN_MIDI = 12;
    static final int MAX_MIDI = 108;

    /** Sensible bounds on the number of strings. */
    static final int MIN_STRINGS = 1;
    static final int MAX_STRINGS = 12;
    static final int DEFAULT_STRINGS = 6;

    /** The six string MIDI numbers the editor pre-fills (E-standard guitar). */
    private static final int[] PREFILL = {40, 45, 50, 55, 59, 64};

    private static final String[] LABELS = buildLabels();

    private final JTextField nameField = new JTextField(18);
    private final JSpinner countSpinner = new JSpinner(
            new SpinnerNumberModel(DEFAULT_STRINGS, MIN_STRINGS, MAX_STRINGS, 1));
    private final JPanel stringsPanel = new JPanel(new GridLayout(0, 3, 6, 4));
    private final List<JComboBox<String>> noteBoxes = new ArrayList<>();
    private final JPanel editor = new JPanel(new BorderLayout(8, 8));

    /** Builds the editor with the default string count pre-filled. */
    AddTuningDialog() {
        countSpinner.addChangeListener(e -> rebuildStringRows());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        top.add(new JLabel("Name:"));
        top.add(nameField);
        top.add(new JLabel("Strings:"));
        top.add(countSpinner);

        stringsPanel.setBorder(BorderFactory.createTitledBorder("Strings (low to high)"));

        editor.add(top, BorderLayout.NORTH);
        editor.add(stringsPanel, BorderLayout.CENTER);
        rebuildStringRows();
    }

    /**
     * Shows the editor as a modal OK/Cancel dialog rooted at {@code parent}.
     *
     * @param parent the hosting component (the tuner panel)
     * @return the tuning the user defined, or null if they cancelled
     */
    Tuning show(Component parent) {
        int result = JOptionPane.showConfirmDialog(parent, editor, "Add tuning",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        return build(nameField.getText(), currentMidi());
    }

    /** The MIDI numbers currently chosen in the note pickers, low to high. */
    private int[] currentMidi() {
        int[] midi = new int[noteBoxes.size()];
        for (int i = 0; i < noteBoxes.size(); i++) {
            midi[i] = midiOf((String) noteBoxes.get(i).getSelectedItem());
        }
        return midi;
    }

    /** Rebuilds one note picker per string, keeping prior choices where possible. */
    private void rebuildStringRows() {
        int count = (Integer) countSpinner.getValue();
        List<String> previous = new ArrayList<>();
        for (JComboBox<String> box : noteBoxes) {
            previous.add((String) box.getSelectedItem());
        }
        stringsPanel.removeAll();
        noteBoxes.clear();
        for (int i = 0; i < count; i++) {
            JComboBox<String> box = new JComboBox<>(LABELS);
            String chosen = (i < previous.size()) ? previous.get(i) : null;
            if (chosen == null && i < PREFILL.length) {
                chosen = labelOf(PREFILL[i]);
            }
            if (chosen != null) {
                box.setSelectedItem(chosen);
            }
            JPanel cell = new JPanel(new BorderLayout(4, 0));
            cell.add(new JLabel((i + 1) + ":"), BorderLayout.WEST);
            cell.add(box, BorderLayout.CENTER);
            stringsPanel.add(cell);
            noteBoxes.add(box);
        }
        stringsPanel.revalidate();
        stringsPanel.repaint();
    }

    // ------------------------------------------------------------------
    // Pure helpers (unit-tested without a display)
    // ------------------------------------------------------------------

    /** Every selectable note label, low to high, for {@link #MIN_MIDI}..{@link #MAX_MIDI}. */
    static String[] noteLabels() {
        return LABELS.clone();
    }

    /** The note label of a MIDI number (e.g. 40 -&gt; "E2"). */
    static String labelOf(int midi) {
        return Note.fromMidi(midi).getLabel();
    }

    /** The MIDI number of a note label, or {@link #MIN_MIDI} when unrecognised. */
    static int midiOf(String label) {
        if (label != null) {
            for (int i = 0; i < LABELS.length; i++) {
                if (LABELS[i].equals(label)) {
                    return MIN_MIDI + i;
                }
            }
        }
        return MIN_MIDI;
    }

    /**
     * Builds a tuning from a name and the string MIDI numbers, low to high. A
     * blank name is replaced with a derived one (e.g. "Custom (C2 G2 ...)").
     *
     * @param name the user-entered name (may be blank/null)
     * @param midi the string MIDI numbers, low to high
     * @return the tuning
     */
    static Tuning build(String name, int[] midi) {
        int[] strings = (midi == null) ? new int[0] : midi.clone();
        String trimmed = (name == null) ? "" : name.trim();
        String resolved = trimmed.isEmpty() ? defaultName(strings) : trimmed;
        return Tuning.of(resolved, strings);
    }

    /** A readable fallback name for an unnamed tuning. */
    static String defaultName(int[] midi) {
        if (midi == null || midi.length == 0) {
            return "Custom (chromatic)";
        }
        StringBuilder sb = new StringBuilder("Custom (");
        for (int i = 0; i < midi.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(labelOf(midi[i]));
        }
        return sb.append(')').toString();
    }

    private static String[] buildLabels() {
        String[] labels = new String[MAX_MIDI - MIN_MIDI + 1];
        for (int m = MIN_MIDI; m <= MAX_MIDI; m++) {
            labels[m - MIN_MIDI] = Note.fromMidi(m).getLabel();
        }
        return labels;
    }
}
