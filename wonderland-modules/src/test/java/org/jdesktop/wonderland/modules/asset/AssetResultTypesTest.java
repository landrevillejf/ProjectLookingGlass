/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5c (assetmgr): the load-state vocabulary and the two
 * result aggregates. Pins AssetLoadStatus.isTerminal, the ModelLoadResult
 * loaded/error split, and WorldContents' counting over a mix of loaded cells,
 * error cells and non-model (null-model) cells.
 */
package org.jdesktop.wonderland.modules.asset;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AssetResultTypesTest {

    @Test
    void onlyPendingIsNonTerminal() {
        assertThat(AssetLoadStatus.PENDING.isTerminal()).isFalse();
        assertThat(AssetLoadStatus.LOADED.isTerminal()).isTrue();
        assertThat(AssetLoadStatus.ERROR.isTerminal()).isTrue();
    }

    @Test
    void anErrorResultCarriesItsReasonAndNoPayload() {
        ModelLoadResult result = ModelLoadResult.error("collada not found");

        assertThat(result.status()).isEqualTo(AssetLoadStatus.ERROR);
        assertThat(result.isLoaded()).isFalse();
        assertThat(result.error()).isEqualTo("collada not found");
        assertThat(result.model()).isNull();
        assertThat(result.loaderData()).isNull();
        assertThat(result.document()).isNull();
        assertThat(result.presentTextures()).isEmpty();
        assertThat(result.missingTextures()).isEmpty();
    }

    @Test
    void aLoadedResultCarriesItsPayloadAndTextures() {
        ModelLoadResult result = ModelLoadResult.loaded(null, null, null,
                List.of("a.png"), List.of("b.png"));

        assertThat(result.isLoaded()).isTrue();
        assertThat(result.error()).isNull();
        assertThat(result.presentTextures()).containsExactly("a.png");
        assertThat(result.missingTextures()).containsExactly("b.png");
    }

    @Test
    void worldContentsCountsAcrossLoadedErrorAndNonModelCells() {
        WorldContents.LoadedCell loaded = new WorldContents.LoadedCell(null,
                ModelLoadResult.loaded(null, null, null, List.of("x"), List.of("m1", "m2")));
        WorldContents.LoadedCell errored = new WorldContents.LoadedCell(null,
                ModelLoadResult.error("boom"));
        WorldContents.LoadedCell nonModel = new WorldContents.LoadedCell(null, null);

        WorldContents contents = new WorldContents("mod",
                List.of(loaded, errored, nonModel));

        assertThat(contents.module()).isEqualTo("mod");
        assertThat(contents.cellCount()).isEqualTo(3);
        assertThat(contents.loadedModelCount()).isEqualTo(1);
        assertThat(contents.missingTextureCount()).isEqualTo(2); // only the loaded cell's
        assertThat(contents.cells()).containsExactly(loaded, errored, nonModel);
        assertThat(loaded.name()).isNull(); // a null state reads as a null name
        assertThat(loaded.state()).isNull(); // the state accessor carries the decoded cell
    }
}
