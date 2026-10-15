/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules (world library): the spellings one asset reference may live under.
 *
 * This is the rule that decides whether a stock world arrives as a building or as a
 * coloured box, so the list is asserted rather than trusted. Measured over the seven
 * shipped world jars, every one of the 23 distinct deployed models exists ONLY as
 * {@code art/<pkg>/models/untitled.dae.gz} while its {@code .dep} names it plain at
 * the module root -- so a store that takes the reference literally finds nothing and
 * loads nothing, and no error says why. The candidates are the crossing of the two
 * conventions the packager applies and the author cannot see, and their ORDER is part
 * of the contract: the literal spelling first, so a module that ships exactly what it
 * names is answered without the conventions ever being consulted.
 */
package org.jdesktop.wonderland.modules.asset;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AssetSpellingTest {

    @Test
    void theStockModelSpellingExpandsToAllFourCandidatesLiteralFirst() {
        // The exact shape every shipped world uses: root reference, plain suffix.
        assertThat(AssetSpelling.candidates("Arch-rustic.kmz/models/untitled.dae"))
                .containsExactly(
                        "Arch-rustic.kmz/models/untitled.dae",
                        "Arch-rustic.kmz/models/untitled.dae.gz",
                        "art/Arch-rustic.kmz/models/untitled.dae",
                        "art/Arch-rustic.kmz/models/untitled.dae.gz");
    }

    @Test
    void theTwoConventionsCrossRatherThanChoose() {
        // A compressed texture beside an uncompressed model is the normal case, so
        // neither convention may imply the other: the gzip sibling must appear both
        // at the root and under art/, and a path already under art/ must still get
        // its gzip sibling.
        assertThat(AssetSpelling.candidates("textures/ground.jpg"))
                .containsExactly("textures/ground.jpg", "textures/ground.jpg.gz",
                        "art/textures/ground.jpg", "art/textures/ground.jpg.gz");
        assertThat(AssetSpelling.candidates("art/room.kmz/models/room.dae"))
                .containsExactly("art/room.kmz/models/room.dae",
                        "art/room.kmz/models/room.dae.gz");
    }

    @Test
    void aPathAlreadyCarryingAConventionContributesOnlyItselfForThatDimension() {
        // The dedup guards: a name that already spells itself compressed, or already
        // sits under art/, must not be offered twice under one spelling, because a
        // store that sees a duplicate cannot tell a miss from a hit it already tried.
        assertThat(AssetSpelling.candidates("art/room.kmz/models/room.dae.gz"))
                .containsExactly("art/room.kmz/models/room.dae.gz");
        assertThat(AssetSpelling.candidates("room.kmz/models/room.dae.gz"))
                .containsExactly("room.kmz/models/room.dae.gz",
                        "art/room.kmz/models/room.dae.gz");
    }

    @Test
    void nothingToNameYieldsNothingToTry() {
        assertThat(AssetSpelling.candidates(null)).isEmpty();
        assertThat(AssetSpelling.candidates("")).isEmpty();
    }

    @Test
    void theListIsNeverEmptyAndNeverRepeatsForAnythingWorthNaming() {
        for (String path : List.of("a.dep", "art/a.dep", "a.dep.gz", "art/a.dep.gz",
                "wfs/x-wlc.xml", "models/untitled.dae")) {
            List<String> candidates = AssetSpelling.candidates(path);
            assertThat(candidates).as("candidates for " + path).isNotEmpty();
            assertThat(candidates).as("no duplicate spelling for " + path)
                    .doesNotHaveDuplicates();
            // The literal reference is always tried first, whatever else is offered.
            assertThat(candidates.get(0)).isEqualTo(path);
        }
    }
}
