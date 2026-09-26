package org.jdesktop.lg3d.apps.periodictable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jdesktop.lg3d.apps.periodictable.PeriodicTablePanel.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure data side of {@link PeriodicTablePanel}: the embedded element
 * table parses to all 118 elements with unique, contiguous atomic numbers and
 * valid categories; the pure {@code gridPosition} layout places the s/p/d blocks
 * and the detached f-block rows correctly; and selection fills the detail line.
 * A construction smoke test proves the panel builds headlessly (no peer, no
 * display) like the games' panels.
 */
class PeriodicTablePanelTest {

    private static final Set<String> CATEGORIES =
            new HashSet<>(Arrays.asList(PeriodicTablePanel.CATEGORY_ORDER));

    @Test
    @DisplayName("the embedded table parses to all 118 elements")
    void parsesAllElements() {
        List<Element> elements = PeriodicTablePanel.elements();
        assertEquals(118, elements.size());
    }

    @Test
    @DisplayName("atomic numbers are unique and contiguous 1..118")
    void contiguousAtomicNumbers() {
        List<Element> elements = PeriodicTablePanel.elements();
        for (int i = 0; i < elements.size(); i++) {
            assertEquals(i + 1, elements.get(i).z(),
                    "element at index " + i + " must be Z=" + (i + 1));
        }
    }

    @Test
    @DisplayName("every element has a non-blank symbol/name/mass and a valid category")
    void fieldsArePopulated() {
        for (Element e : PeriodicTablePanel.elements()) {
            assertTrue(CATEGORIES.contains(e.category()),
                    "Z=" + e.z() + " has unknown category " + e.category());
            assertTrue(!e.symbol().isBlank(), "Z=" + e.z() + " has a blank symbol");
            assertTrue(!e.name().isBlank(), "Z=" + e.z() + " has a blank name");
            assertTrue(!e.mass().isBlank(), "Z=" + e.z() + " has a blank mass");
        }
    }

    @Test
    @DisplayName("spot-checks: modern symbols/names for H, Cn and Og")
    void spotChecks() {
        Element h = PeriodicTablePanel.elements().get(0);
        assertEquals("H", h.symbol());
        assertEquals("Hydrogen", h.name());
        assertEquals("nonmetal", h.category());

        Element cn = PeriodicTablePanel.elements().get(111);   // Z=112
        assertEquals(112, cn.z());
        assertEquals("Cn", cn.symbol(), "the 2006 'Uub' drift is corrected to Cn");
        assertEquals("Copernicium", cn.name());

        Element og = PeriodicTablePanel.elements().get(117);   // Z=118
        assertEquals(118, og.z());
        assertEquals("Og", og.symbol());
        assertEquals("Oganesson", og.name());
        assertEquals("noble", og.category());
    }

    @Test
    @DisplayName("the parser skips blank and malformed lines")
    void parserIsRobust() {
        List<Element> parsed = PeriodicTablePanel.parse(
                "1|H|Hydrogen|nonmetal|1.008\n"
                + "\n"
                + "   \n"
                + "not-a-number|X|Bad|nonmetal|0\n"
                + "2|He|Noble\n"                        // too few fields
                + "3|Li|Lithium|alkali|6.94\n");
        assertEquals(2, parsed.size());
        assertEquals(1, parsed.get(0).z());
        assertEquals(3, parsed.get(1).z());
        assertTrue(PeriodicTablePanel.parse(null).isEmpty());
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    @Test
    @DisplayName("gridPosition places the s/p/d blocks and detached f-block rows")
    void layoutPositions() {
        assertArrayEquals(new int[] {1, 1}, PeriodicTablePanel.gridPosition(1));    // H
        assertArrayEquals(new int[] {1, 18}, PeriodicTablePanel.gridPosition(2));   // He
        assertArrayEquals(new int[] {2, 1}, PeriodicTablePanel.gridPosition(3));    // Li
        assertArrayEquals(new int[] {2, 18}, PeriodicTablePanel.gridPosition(10));  // Ne
        assertArrayEquals(new int[] {4, 1}, PeriodicTablePanel.gridPosition(19));   // K
        assertArrayEquals(new int[] {4, 18}, PeriodicTablePanel.gridPosition(36));  // Kr
        assertArrayEquals(new int[] {6, 2}, PeriodicTablePanel.gridPosition(56));   // Ba
        assertArrayEquals(new int[] {6, 4}, PeriodicTablePanel.gridPosition(72));   // Hf
        assertArrayEquals(new int[] {6, 18}, PeriodicTablePanel.gridPosition(86));  // Rn
        assertArrayEquals(new int[] {7, 4}, PeriodicTablePanel.gridPosition(104));  // Rf
        assertArrayEquals(new int[] {7, 12}, PeriodicTablePanel.gridPosition(112)); // Cn
        assertArrayEquals(new int[] {7, 18}, PeriodicTablePanel.gridPosition(118)); // Og
        // Detached f-block rows: lanthanides (row 8) and actinides (row 9).
        assertArrayEquals(new int[] {8, 3}, PeriodicTablePanel.gridPosition(57));   // La
        assertArrayEquals(new int[] {8, 17}, PeriodicTablePanel.gridPosition(71));  // Lu
        assertArrayEquals(new int[] {9, 3}, PeriodicTablePanel.gridPosition(89));   // Ac
        assertArrayEquals(new int[] {9, 17}, PeriodicTablePanel.gridPosition(103)); // Lr
    }

    @Test
    @DisplayName("gridPosition is null outside 1..118 and every Z lands in a cell")
    void layoutCoverage() {
        assertNull(PeriodicTablePanel.gridPosition(0));
        assertNull(PeriodicTablePanel.gridPosition(119));
        assertNull(PeriodicTablePanel.gridPosition(-1));
        for (int z = 1; z <= 118; z++) {
            int[] pos = PeriodicTablePanel.gridPosition(z);
            assertNotNull(pos, "Z=" + z + " must have a grid cell");
            assertTrue(pos[0] >= 1 && pos[0] <= 9, "row out of range for Z=" + z);
            assertTrue(pos[1] >= 1 && pos[1] <= 18, "column out of range for Z=" + z);
        }
    }

    @Test
    @DisplayName("no two elements share a grid cell")
    void layoutHasNoCollisions() {
        Set<String> seen = new HashSet<>();
        for (int z = 1; z <= 118; z++) {
            int[] pos = PeriodicTablePanel.gridPosition(z);
            assertTrue(seen.add(pos[0] + ":" + pos[1]),
                    "Z=" + z + " collides at row " + pos[0] + " col " + pos[1]);
        }
        assertEquals(118, seen.size());
    }

    // ------------------------------------------------------------------
    // Categories / colours
    // ------------------------------------------------------------------

    @Test
    @DisplayName("each category maps to a distinct colour and a non-blank label")
    void categoryColorsAndLabels() {
        Set<Integer> argbs = new HashSet<>();
        for (String category : PeriodicTablePanel.CATEGORY_ORDER) {
            argbs.add(PeriodicTablePanel.categoryColor(category).getRGB());
            assertTrue(!PeriodicTablePanel.categoryLabel(category).isBlank());
        }
        assertEquals(PeriodicTablePanel.CATEGORY_ORDER.length, argbs.size(),
                "every category colour must be distinct");
        // An unknown / null key degrades rather than throwing.
        assertNotNull(PeriodicTablePanel.categoryColor("bogus"));
        assertNotNull(PeriodicTablePanel.categoryColor(null));
        assertEquals("Unknown", PeriodicTablePanel.categoryLabel(null));
    }

    // ------------------------------------------------------------------
    // Panel behaviour (headless-safe construction)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the panel builds headlessly with one cell per element")
    void constructionSmokeTest() {
        PeriodicTablePanel panel = new PeriodicTablePanel();
        assertEquals(118, panel.cellCount());
        assertEquals(0, panel.selectedZ());
        assertNotNull(panel.getPreferredSize());
        assertTrue(panel.getPreferredSize().width > 0);
        assertTrue(panel.getPreferredSize().height > 0);
    }

    @Test
    @DisplayName("selecting an element fills the detail line; 0 clears it")
    void selectionFillsDetail() {
        PeriodicTablePanel panel = new PeriodicTablePanel();
        assertEquals("Click an element for details", panel.detailText());

        panel.selectElement(6);
        assertEquals(6, panel.selectedZ());
        String detail = panel.detailText();
        assertTrue(detail.contains("Carbon"), detail);
        assertTrue(detail.contains("12.011"), detail);

        panel.selectElement(118);
        assertTrue(panel.detailText().contains("Oganesson"));

        panel.selectElement(0);
        assertEquals(0, panel.selectedZ());
        assertEquals("Click an element for details", panel.detailText());
    }
}
