package org.jdesktop.lg3d.apps.periodictable;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * A plain-Swing periodic table of the elements for the 2D/Swing desktop.
 *
 * <p>This is the pure-2D counterpart of the native-3D {@link PeriodicTable3D}
 * app (which is untouched): the one start-menu descriptor keyed on the 3D main
 * class launches this panel as an MDI internal frame in the 2D desktop through
 * {@code Desktop2DAppRegistry.PANEL_APPS}, exactly like the four games and the
 * Office-group incubator apps. It renders the standard 18x7 grid plus the
 * detached lanthanide and actinide rows, colours each cell by category with a
 * legend, shows the mass and category as a hover tooltip, and fills a detail
 * line under the grid when an element is clicked.</p>
 *
 * <p>The element data (all 118, with modern symbols and names - Copernicium
 * rather than the 2006 "Uub", plus Flerovium, Livermorium, Nihonium, Moscovium,
 * Tennessine and Oganesson) is embedded as a compact string constant and parsed
 * by the pure, headless-testable {@link #parse(String)} helper into an immutable
 * list of {@link Element} records; the grid placement is likewise a pure
 * {@link #gridPosition(int)} function. Following the {@code CalculatorPanel} /
 * {@code ChessPanel} SwingNode-offscreen rule, the panel uses a null layout with
 * explicit {@code setBounds} and a fixed preferred size, so it lays out
 * identically whether hosted on a {@code SwingNode} or as an internal frame.</p>
 */
public class PeriodicTablePanel extends JPanel {

    /** One chemical element: atomic number, symbol, name, category and mass. */
    public record Element(int z, String symbol, String name, String category, String mass) {
    }

    // ------------------------------------------------------------------
    // Embedded element data (Z|symbol|name|category|mass), all 118 elements
    // ------------------------------------------------------------------

    static final String DATA = """
            1|H|Hydrogen|nonmetal|1.008
            2|He|Helium|noble|4.0026
            3|Li|Lithium|alkali|6.94
            4|Be|Beryllium|alkaline|9.0122
            5|B|Boron|metalloid|10.81
            6|C|Carbon|nonmetal|12.011
            7|N|Nitrogen|nonmetal|14.007
            8|O|Oxygen|nonmetal|15.999
            9|F|Fluorine|halogen|18.998
            10|Ne|Neon|noble|20.180
            11|Na|Sodium|alkali|22.990
            12|Mg|Magnesium|alkaline|24.305
            13|Al|Aluminium|post-transition|26.982
            14|Si|Silicon|metalloid|28.085
            15|P|Phosphorus|nonmetal|30.974
            16|S|Sulfur|nonmetal|32.06
            17|Cl|Chlorine|halogen|35.45
            18|Ar|Argon|noble|39.948
            19|K|Potassium|alkali|39.098
            20|Ca|Calcium|alkaline|40.078
            21|Sc|Scandium|transition|44.956
            22|Ti|Titanium|transition|47.867
            23|V|Vanadium|transition|50.942
            24|Cr|Chromium|transition|51.996
            25|Mn|Manganese|transition|54.938
            26|Fe|Iron|transition|55.845
            27|Co|Cobalt|transition|58.933
            28|Ni|Nickel|transition|58.693
            29|Cu|Copper|transition|63.546
            30|Zn|Zinc|transition|65.38
            31|Ga|Gallium|post-transition|69.723
            32|Ge|Germanium|metalloid|72.630
            33|As|Arsenic|metalloid|74.922
            34|Se|Selenium|nonmetal|78.971
            35|Br|Bromine|halogen|79.904
            36|Kr|Krypton|noble|83.798
            37|Rb|Rubidium|alkali|85.468
            38|Sr|Strontium|alkaline|87.62
            39|Y|Yttrium|transition|88.906
            40|Zr|Zirconium|transition|91.224
            41|Nb|Niobium|transition|92.906
            42|Mo|Molybdenum|transition|95.95
            43|Tc|Technetium|transition|98
            44|Ru|Ruthenium|transition|101.07
            45|Rh|Rhodium|transition|102.91
            46|Pd|Palladium|transition|106.42
            47|Ag|Silver|transition|107.87
            48|Cd|Cadmium|transition|112.41
            49|In|Indium|post-transition|114.82
            50|Sn|Tin|post-transition|118.71
            51|Sb|Antimony|metalloid|121.76
            52|Te|Tellurium|metalloid|127.60
            53|I|Iodine|halogen|126.90
            54|Xe|Xenon|noble|131.29
            55|Cs|Caesium|alkali|132.91
            56|Ba|Barium|alkaline|137.33
            57|La|Lanthanum|lanthanide|138.91
            58|Ce|Cerium|lanthanide|140.12
            59|Pr|Praseodymium|lanthanide|140.91
            60|Nd|Neodymium|lanthanide|144.24
            61|Pm|Promethium|lanthanide|145
            62|Sm|Samarium|lanthanide|150.36
            63|Eu|Europium|lanthanide|151.96
            64|Gd|Gadolinium|lanthanide|157.25
            65|Tb|Terbium|lanthanide|158.93
            66|Dy|Dysprosium|lanthanide|162.50
            67|Ho|Holmium|lanthanide|164.93
            68|Er|Erbium|lanthanide|167.26
            69|Tm|Thulium|lanthanide|168.93
            70|Yb|Ytterbium|lanthanide|173.05
            71|Lu|Lutetium|lanthanide|174.97
            72|Hf|Hafnium|transition|178.49
            73|Ta|Tantalum|transition|180.95
            74|W|Tungsten|transition|183.84
            75|Re|Rhenium|transition|186.21
            76|Os|Osmium|transition|190.23
            77|Ir|Iridium|transition|192.22
            78|Pt|Platinum|transition|195.08
            79|Au|Gold|transition|196.97
            80|Hg|Mercury|transition|200.59
            81|Tl|Thallium|post-transition|204.38
            82|Pb|Lead|post-transition|207.2
            83|Bi|Bismuth|post-transition|208.98
            84|Po|Polonium|post-transition|209
            85|At|Astatine|halogen|210
            86|Rn|Radon|noble|222
            87|Fr|Francium|alkali|223
            88|Ra|Radium|alkaline|226
            89|Ac|Actinium|actinide|227
            90|Th|Thorium|actinide|232.04
            91|Pa|Protactinium|actinide|231.04
            92|U|Uranium|actinide|238.03
            93|Np|Neptunium|actinide|237
            94|Pu|Plutonium|actinide|244
            95|Am|Americium|actinide|243
            96|Cm|Curium|actinide|247
            97|Bk|Berkelium|actinide|247
            98|Cf|Californium|actinide|251
            99|Es|Einsteinium|actinide|252
            100|Fm|Fermium|actinide|257
            101|Md|Mendelevium|actinide|258
            102|No|Nobelium|actinide|259
            103|Lr|Lawrencium|actinide|266
            104|Rf|Rutherfordium|transition|267
            105|Db|Dubnium|transition|268
            106|Sg|Seaborgium|transition|269
            107|Bh|Bohrium|transition|270
            108|Hs|Hassium|transition|277
            109|Mt|Meitnerium|transition|278
            110|Ds|Darmstadtium|transition|281
            111|Rg|Roentgenium|transition|282
            112|Cn|Copernicium|transition|285
            113|Nh|Nihonium|post-transition|286
            114|Fl|Flerovium|post-transition|289
            115|Mc|Moscovium|post-transition|290
            116|Lv|Livermorium|post-transition|293
            117|Ts|Tennessine|halogen|294
            118|Og|Oganesson|noble|294
            """;

    /** The parsed, immutable element table (all 118 elements). */
    private static final List<Element> ELEMENTS = parse(DATA);

    /** Legend / iteration order of the element categories. */
    static final String[] CATEGORY_ORDER = {
        "alkali", "alkaline", "transition", "post-transition", "metalloid",
        "nonmetal", "halogen", "noble", "lanthanide", "actinide"
    };

    // ------------------------------------------------------------------
    // Geometry (fixed size; null layout with explicit setBounds)
    // ------------------------------------------------------------------

    private static final int CELL_W = 56;
    private static final int CELL_H = 60;
    private static final int GAP = 3;
    private static final int MARGIN = 14;
    private static final int COLS = 18;
    private static final int MAIN_ROWS = 7;

    private static final int GRID_W = COLS * CELL_W + (COLS - 1) * GAP;
    private static final int WIDTH_PX = GRID_W + 2 * MARGIN;
    private static final int MAIN_H = MAIN_ROWS * CELL_H + (MAIN_ROWS - 1) * GAP;
    private static final int F_BLOCK_GAP = 18;
    private static final int LANTHANIDE_Y = MARGIN + MAIN_H + F_BLOCK_GAP;
    private static final int ACTINIDE_Y = LANTHANIDE_Y + CELL_H + GAP;
    private static final int LEGEND_Y = ACTINIDE_Y + CELL_H + 16;
    private static final int LEGEND_H = 44;
    private static final int DETAIL_Y = LEGEND_Y + LEGEND_H + 4;
    private static final int HEIGHT_PX = DETAIL_Y + 24 + MARGIN;

    private static final Color TEXT = new Color(0x1A, 0x1A, 0x1A);
    private static final Color BACKGROUND = new Color(0x20, 0x26, 0x31);

    /** The atomic number of the selected element, or 0 when none is selected. */
    private int selectedZ;

    /** The detail line filled when an element is clicked. */
    private final JLabel detail = new JLabel("Click an element for details");

    /** Every element cell, so a selection change can repaint them all. */
    private final List<ElementCell> cells = new ArrayList<>();

    public PeriodicTablePanel() {
        setLayout(null);
        setOpaque(true);
        setBackground(BACKGROUND);
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        for (Element element : ELEMENTS) {
            int[] pos = gridPosition(element.z());
            if (pos == null) {
                continue;
            }
            ElementCell cell = new ElementCell(element);
            cell.setBounds(colX(pos[1]), rowY(pos[0]), CELL_W, CELL_H);
            add(cell);
            cells.add(cell);
        }

        add(buildLegend());

        detail.setBounds(MARGIN, DETAIL_Y, GRID_W, 24);
        detail.setForeground(new Color(0xE6, 0xE6, 0xE6));
        detail.setHorizontalAlignment(SwingConstants.CENTER);
        add(detail);
    }

    // ------------------------------------------------------------------
    // Pure data helpers (headless-testable)
    // ------------------------------------------------------------------

    /**
     * Parses the {@code Z|symbol|name|category|mass} line format into an
     * immutable list of {@link Element} records. Blank and malformed lines
     * (too few fields, or a non-integer atomic number) are skipped, so a partial
     * table still yields the elements it does describe.
     */
    static List<Element> parse(String data) {
        List<Element> out = new ArrayList<>();
        if (data == null) {
            return Collections.unmodifiableList(out);
        }
        for (String raw : data.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\\|");
            if (f.length < 5) {
                continue;
            }
            try {
                int z = Integer.parseInt(f[0].trim());
                out.add(new Element(z, f[1].trim(), f[2].trim(), f[3].trim(), f[4].trim()));
            } catch (NumberFormatException e) {
                // skip a malformed atomic number
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** The parsed element table (all 118 elements), in atomic-number order. */
    public static List<Element> elements() {
        return ELEMENTS;
    }

    /**
     * The grid cell for atomic number {@code z} as {@code {row, column}}, both
     * 1-based: rows 1..7 are the main grid, row 8 is the detached lanthanide
     * row and row 9 the detached actinide row; columns run 1..18. Returns
     * {@code null} for a number outside 1..118.
     */
    static int[] gridPosition(int z) {
        if (z == 1) {
            return new int[] {1, 1};
        }
        if (z == 2) {
            return new int[] {1, 18};
        }
        if (z >= 3 && z <= 4) {
            return new int[] {2, z - 2};              // Li, Be -> groups 1-2
        }
        if (z >= 5 && z <= 10) {
            return new int[] {2, z + 8};              // B..Ne -> groups 13-18
        }
        if (z >= 11 && z <= 12) {
            return new int[] {3, z - 10};             // Na, Mg
        }
        if (z >= 13 && z <= 18) {
            return new int[] {3, z};                  // Al..Ar
        }
        if (z >= 19 && z <= 36) {
            return new int[] {4, z - 18};             // K..Kr
        }
        if (z >= 37 && z <= 54) {
            return new int[] {5, z - 36};             // Rb..Xe
        }
        if (z == 55 || z == 56) {
            return new int[] {6, z - 54};             // Cs, Ba
        }
        if (z >= 57 && z <= 71) {
            return new int[] {8, z - 57 + 3};         // lanthanides, cols 3-17
        }
        if (z >= 72 && z <= 86) {
            return new int[] {6, z - 68};             // Hf..Rn, groups 4-18
        }
        if (z == 87 || z == 88) {
            return new int[] {7, z - 86};             // Fr, Ra
        }
        if (z >= 89 && z <= 103) {
            return new int[] {9, z - 89 + 3};         // actinides, cols 3-17
        }
        if (z >= 104 && z <= 118) {
            return new int[] {7, z - 100};            // Rf..Og, groups 4-18
        }
        return null;
    }

    /** The fill colour for a category key; a neutral grey for an unknown key. */
    static Color categoryColor(String category) {
        if (category == null) {
            return new Color(0xBD, 0xBD, 0xBD);
        }
        switch (category) {
            case "alkali":          return new Color(0xFF, 0x6B, 0x6B);
            case "alkaline":        return new Color(0xFF, 0xA9, 0x4D);
            case "transition":      return new Color(0xFF, 0xD4, 0x3B);
            case "post-transition": return new Color(0xA9, 0xE3, 0x4B);
            case "metalloid":       return new Color(0x63, 0xE6, 0xBE);
            case "nonmetal":        return new Color(0x4D, 0xAB, 0xF7);
            case "halogen":         return new Color(0x74, 0x8F, 0xFC);
            case "noble":           return new Color(0xDA, 0x77, 0xF2);
            case "lanthanide":      return new Color(0xF7, 0x83, 0xAC);
            case "actinide":        return new Color(0xE5, 0x99, 0xF7);
            default:                return new Color(0xBD, 0xBD, 0xBD);
        }
    }

    /** The human-readable label for a category key. */
    static String categoryLabel(String category) {
        if (category == null) {
            return "Unknown";
        }
        switch (category) {
            case "alkali":          return "Alkali metal";
            case "alkaline":        return "Alkaline earth";
            case "transition":      return "Transition metal";
            case "post-transition": return "Post-transition";
            case "metalloid":       return "Metalloid";
            case "nonmetal":        return "Reactive nonmetal";
            case "halogen":         return "Halogen";
            case "noble":           return "Noble gas";
            case "lanthanide":      return "Lanthanide";
            case "actinide":        return "Actinide";
            default:                return "Unknown";
        }
    }

    /** The element with atomic number {@code z}, or null when out of range. */
    static Element element(int z) {
        for (Element e : ELEMENTS) {
            if (e.z() == z) {
                return e;
            }
        }
        return null;
    }

    private static int colX(int col) {
        return MARGIN + (col - 1) * (CELL_W + GAP);
    }

    private static int rowY(int row) {
        if (row == 8) {
            return LANTHANIDE_Y;
        }
        if (row == 9) {
            return ACTINIDE_Y;
        }
        return MARGIN + (row - 1) * (CELL_H + GAP);
    }

    // ------------------------------------------------------------------
    // Selection
    // ------------------------------------------------------------------

    /**
     * Selects the element with atomic number {@code z} (0 clears the selection),
     * updating the detail line and repainting the cells. Invoked by a cell click
     * and, headlessly, by the tests.
     */
    public void selectElement(int z) {
        selectedZ = z;
        detail.setText(detailText());
        for (ElementCell cell : cells) {
            cell.repaint();
        }
    }

    /** The one-line detail text for the current selection. */
    String detailText() {
        Element e = element(selectedZ);
        if (e == null) {
            return "Click an element for details";
        }
        return e.z() + "  " + e.symbol() + "  " + e.name()
                + "  ·  " + categoryLabel(e.category())
                + "  ·  " + e.mass() + " u";
    }

    // ------------------------------------------------------------------
    // Legend
    // ------------------------------------------------------------------

    private JPanel buildLegend() {
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 2));
        legend.setOpaque(false);
        legend.setBounds(MARGIN, LEGEND_Y, GRID_W, LEGEND_H);
        for (String category : CATEGORY_ORDER) {
            legend.add(legendChip(category));
        }
        return legend;
    }

    private JPanel legendChip(String category) {
        JPanel chip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        chip.setOpaque(false);
        JPanel swatch = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(categoryColor(category));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        swatch.setPreferredSize(new Dimension(12, 12));
        JLabel label = new JLabel(categoryLabel(category));
        label.setForeground(new Color(0xCF, 0xD4, 0xDC));
        chip.add(swatch);
        chip.add(label);
        return chip;
    }

    // ------------------------------------------------------------------
    // Test hooks (package-private)
    // ------------------------------------------------------------------

    int selectedZ() {
        return selectedZ;
    }

    int cellCount() {
        return cells.size();
    }

    // ------------------------------------------------------------------
    // The custom-painted element cell
    // ------------------------------------------------------------------

    private final class ElementCell extends JPanel {

        private final Element el;

        ElementCell(Element el) {
            this.el = el;
            setOpaque(false);
            setToolTipText(el.name() + " — " + el.mass() + " u ("
                    + categoryLabel(el.category()) + ")");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    selectElement(el.z());
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            Font base = getFont();

            g.setColor(categoryColor(el.category()));
            g.fillRoundRect(0, 0, w - 1, h - 1, 6, 6);

            if (selectedZ == el.z()) {
                g.setColor(TEXT);
                g.setStroke(new BasicStroke(2.5f));
                g.drawRoundRect(1, 1, w - 3, h - 3, 6, 6);
            } else {
                g.setColor(new Color(0, 0, 0, 70));
                g.drawRoundRect(0, 0, w - 1, h - 1, 6, 6);
            }

            g.setColor(TEXT);
            g.setFont(base.deriveFont(9f));
            g.drawString(String.valueOf(el.z()), 4, 12);

            g.setFont(base.deriveFont(Font.BOLD, 20f));
            FontMetrics fm = g.getFontMetrics();
            int sx = (w - fm.stringWidth(el.symbol())) / 2;
            g.drawString(el.symbol(), sx, h / 2 + 6);

            g.setFont(base.deriveFont(8f));
            drawFitted(g, el.name(), w, h - 5);
        }

        /** Draws {@code text} centred, truncated with an ellipsis if too wide. */
        private void drawFitted(Graphics2D g, String text, int w, int y) {
            FontMetrics fm = g.getFontMetrics();
            int max = w - 6;
            if (fm.stringWidth(text) <= max) {
                g.drawString(text, (w - fm.stringWidth(text)) / 2, y);
                return;
            }
            String t = text;
            while (t.length() > 0 && fm.stringWidth(t + "\u2026") > max) {
                t = t.substring(0, t.length() - 1);
            }
            g.drawString(t + "\u2026", 3, y);
        }
    }
}
