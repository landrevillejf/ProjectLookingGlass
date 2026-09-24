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
package org.jdesktop.lg3d.apps.calculator;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Swing content of the Calculator desktop window: an editable expression
 * field with a live result preview, a six-column scientific key pad, a memory
 * register row and a clickable calculation history.
 *
 * <p>The panel uses a {@code null} layout with explicit bounds for every
 * child: {@code SwingNode} paints hosted panels offscreen without a layout
 * pass, so bounds assigned by a Swing layout manager would never be computed
 * and the children would render zero-sized (see {@code docs/swingnode.md}).</p>
 */
public class CalculatorPanel extends JPanel {

    /** Panel size in native pixels; the wrapper hands these to TitledSwingWindow. */
    public static final int WIDTH_PX = 566;
    public static final int HEIGHT_PX = 362;

    private static final int MARGIN = 8;
    private static final int BTN_W = 62;
    private static final int BTN_H = 30;
    private static final int GAP = 4;
    private static final int GRID_COLS = 6;
    private static final int GRID_Y = 86;
    private static final int GRID_W = GRID_COLS * BTN_W + (GRID_COLS - 1) * GAP;
    private static final int HIST_X = MARGIN + GRID_W + 8;
    private static final int HIST_W = 150;

    /** Multi-character tokens that backspace removes in a single step. */
    private static final String[] TOKEN_SUFFIXES = {
        "asin(", "acos(", "atan(", "sin(", "cos(", "tan(", "sqrt(", "abs(",
        " mod ", "Ans", "pi", "^2", "^-1",
    };

    /** A plain number at the very end of the expression, for the +/- key. */
    private static final Pattern TRAILING_NUMBER =
            Pattern.compile("(\\d+(?:\\.\\d*)?|\\.\\d+)$");

    private final CalculatorEngine engine = new CalculatorEngine();
    private final JTextField display = new JTextField();
    private final JLabel preview = new JLabel();
    private final JLabel memoryFlag = new JLabel();
    private final DefaultListModel<String> historyModel = new DefaultListModel<>();
    private final JList<String> historyList = new JList<>(historyModel);
    private JButton angleButton;

    public CalculatorPanel() {
        setLayout(null);
        setOpaque(true);
        setBackground(new Color(0x20, 0x26, 0x31));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        buildDisplay();
        buildKeys();
        buildHistory();

        display.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                refreshPreview();
            }

            public void removeUpdate(DocumentEvent e) {
                refreshPreview();
            }

            public void changedUpdate(DocumentEvent e) {
                refreshPreview();
            }
        });
        refreshPreview();
    }

    private void buildDisplay() {
        memoryFlag.setBounds(MARGIN, 8, 16, 20);
        memoryFlag.setForeground(new Color(0xFF, 0xB0, 0x40));
        add(memoryFlag);

        preview.setBounds(MARGIN + 18, 8, GRID_W - 18, 20);
        preview.setHorizontalAlignment(SwingConstants.RIGHT);
        preview.setForeground(new Color(0x9A, 0xA4, 0xB2));
        add(preview);

        display.setBounds(MARGIN, 32, GRID_W, 46);
        display.setHorizontalAlignment(SwingConstants.RIGHT);
        display.setFont(display.getFont().deriveFont(Font.BOLD, 24f));
        display.addActionListener(e -> evaluate());
        add(display);
    }

    private void buildKeys() {
        // Row 0: memory register and angle mode.
        addButton("MC", 0, 0, 1, e -> memoryClear());
        addButton("MR", 1, 0, 1, e -> insert(CalculatorEngine.format(engine.getMemory())));
        addButton("M+", 2, 0, 1, e -> memoryAccumulate(+1));
        addButton("M-", 3, 0, 1, e -> memoryAccumulate(-1));
        addButton("MS", 4, 0, 1, e -> memoryStore());
        angleButton = addButton("DEG", 5, 0, 1, e -> toggleAngle());

        // Row 1: direct trigonometry and backspace.
        addButton("sin", 0, 1, 1, e -> insert("sin("));
        addButton("cos", 1, 1, 1, e -> insert("cos("));
        addButton("tan", 2, 1, 1, e -> insert("tan("));
        addButton("ln", 3, 1, 1, e -> insert("ln("));
        addButton("log", 4, 1, 1, e -> insert("log("));
        addButton("⌫", 5, 1, 1, e -> backspace());

        // Row 2: powers, roots, reciprocal, factorial, clear.
        addButton("√", 0, 2, 1, e -> insert("sqrt("));
        addButton("x²", 1, 2, 1, e -> insert("^2"));
        addButton("x^y", 2, 2, 1, e -> insert("^("));
        addButton("1/x", 3, 2, 1, e -> insert("^-1"));
        addButton("n!", 4, 2, 1, e -> insert("!"));
        addButton("C", 5, 2, 1, e -> clearAll());

        // Row 3: inverse trig, constants, percent.
        addButton("asin", 0, 3, 1, e -> insert("asin("));
        addButton("acos", 1, 3, 1, e -> insert("acos("));
        addButton("atan", 2, 3, 1, e -> insert("atan("));
        addButton("π", 3, 3, 1, e -> insert("pi"));
        addButton("e", 4, 3, 1, e -> insert("e"));
        addButton("%", 5, 3, 1, e -> insert("%"));

        // Rows 4-6: digits, operators, parentheses, sign, abs, mod.
        addButton("7", 0, 4, 1, e -> insert("7"));
        addButton("8", 1, 4, 1, e -> insert("8"));
        addButton("9", 2, 4, 1, e -> insert("9"));
        addButton("÷", 3, 4, 1, e -> insert("/"));
        addButton("(", 4, 4, 1, e -> insert("("));
        addButton("±", 5, 4, 1, e -> negateLast());

        addButton("4", 0, 5, 1, e -> insert("4"));
        addButton("5", 1, 5, 1, e -> insert("5"));
        addButton("6", 2, 5, 1, e -> insert("6"));
        addButton("×", 3, 5, 1, e -> insert("*"));
        addButton(")", 4, 5, 1, e -> insert(")"));
        addButton("Ans", 5, 5, 1, e -> insert("Ans"));

        addButton("1", 0, 6, 1, e -> insert("1"));
        addButton("2", 1, 6, 1, e -> insert("2"));
        addButton("3", 2, 6, 1, e -> insert("3"));
        addButton("−", 3, 6, 1, e -> insert("-"));
        addButton("abs", 4, 6, 1, e -> insert("abs("));
        addButton("mod", 5, 6, 1, e -> insert(" mod "));

        // Row 7: wide zero, decimal point, plus and the wide equals key.
        addButton("0", 0, 7, 2, e -> insert("0"));
        addButton(".", 2, 7, 1, e -> insert("."));
        addButton("+", 3, 7, 1, e -> insert("+"));
        JButton equals = addButton("=", 4, 7, 2, e -> evaluate());
        equals.setBackground(new Color(0x3D, 0x6B, 0xC4));
        equals.setForeground(Color.WHITE);
    }

    private void buildHistory() {
        JScrollPane scroll = new JScrollPane(historyList);
        scroll.setBounds(HIST_X, MARGIN, HIST_W, 318);
        add(scroll);

        JButton clear = new JButton("Clear history");
        clear.setBounds(HIST_X, 334, HIST_W, 20);
        clear.setMargin(new Insets(0, 0, 0, 0));
        clear.addActionListener(e -> historyModel.clear());
        add(clear);

        // Clicking an entry recalls its result into the expression field.
        historyList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                int index = historyList.locationToIndex(e.getPoint());
                if (index < 0 || index >= historyModel.size()) {
                    return;
                }
                String entry = historyModel.get(index);
                int split = entry.lastIndexOf(" = ");
                if (split < 0) {
                    return;
                }
                display.setText(entry.substring(split + 3));
                display.setCaretPosition(display.getDocument().getLength());
                refreshPreview();
            }
        });
    }

    private JButton addButton(
            String label, int col, int row, int colSpan, ActionListener listener) {
        JButton button = new JButton(label);
        button.setBounds(
                MARGIN + col * (BTN_W + GAP),
                GRID_Y + row * (BTN_H + GAP),
                colSpan * BTN_W + (colSpan - 1) * GAP,
                BTN_H);
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setFocusPainted(false);
        button.addActionListener(listener);
        add(button);
        return button;
    }

    // ------------------------------------------------------------- key actions

    private void insert(String token) {
        String text = display.getText();
        display.setText(text + token);
        display.setCaretPosition(display.getDocument().getLength());
    }

    private void backspace() {
        String text = display.getText();
        for (String suffix : TOKEN_SUFFIXES) {
            if (text.endsWith(suffix)) {
                display.setText(text.substring(0, text.length() - suffix.length()));
                return;
            }
        }
        if (!text.isEmpty()) {
            display.setText(text.substring(0, text.length() - 1));
        }
    }

    /** Toggles the sign of the trailing number of the expression, if any. */
    private void negateLast() {
        String text = display.getText();
        Matcher matcher = TRAILING_NUMBER.matcher(text);
        if (!matcher.find()) {
            return;
        }
        int start = matcher.start();
        if (start > 0 && text.charAt(start - 1) == '-') {
            int before = start - 2;
            boolean signContext = before < 0 || "+-*/^(%".indexOf(text.charAt(before)) >= 0;
            if (!signContext) {
                return; // that minus is a subtraction, not a sign
            }
            display.setText(text.substring(0, start - 1) + text.substring(start));
        } else {
            display.setText(text.substring(0, start) + "-" + text.substring(start));
        }
    }

    private void clearAll() {
        display.setText("");
        preview.setText("");
    }

    private void evaluate() {
        String expression = display.getText().trim();
        if (expression.isEmpty()) {
            return;
        }
        try {
            double value = engine.evaluate(expression);
            String result = CalculatorEngine.format(value);
            engine.setLastAnswer(value);
            historyModel.insertElementAt(expression + " = " + result, 0);
            display.setText(result);
            display.setCaretPosition(display.getDocument().getLength());
            preview.setText("");
        } catch (CalculatorEngine.CalcException e) {
            preview.setText(e.getMessage());
        }
    }

    /** Live "= value" hint while the expression parses; silent otherwise. */
    private void refreshPreview() {
        String expression = display.getText().trim();
        if (expression.isEmpty()) {
            preview.setText("");
            return;
        }
        try {
            preview.setText("= " + CalculatorEngine.format(engine.evaluate(expression)));
        } catch (CalculatorEngine.CalcException e) {
            preview.setText("");
        }
    }

    // --------------------------------------------------------- memory register

    private Double evalCurrent() {
        try {
            return engine.evaluate(display.getText().trim());
        } catch (CalculatorEngine.CalcException e) {
            return null;
        }
    }

    private void memoryAccumulate(int sign) {
        Double value = evalCurrent();
        if (value == null) {
            return;
        }
        engine.addToMemory(sign * value);
        updateMemoryFlag();
    }

    private void memoryStore() {
        Double value = evalCurrent();
        if (value == null) {
            return;
        }
        engine.storeMemory(value);
        updateMemoryFlag();
    }

    private void memoryClear() {
        engine.clearMemory();
        updateMemoryFlag();
    }

    private void updateMemoryFlag() {
        memoryFlag.setText(engine.isMemorySet() ? "M" : "");
    }

    private void toggleAngle() {
        boolean degrees = engine.getAngleMode() == CalculatorEngine.AngleMode.DEGREES;
        engine.setAngleMode(degrees
                ? CalculatorEngine.AngleMode.RADIANS
                : CalculatorEngine.AngleMode.DEGREES);
        angleButton.setText(degrees ? "RAD" : "DEG");
    }
}
