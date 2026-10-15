/*
 * M2d: the CellCacheBasicImpl registry, driven directly (the real hierarchy
 * messages arrive with M3). Pins load/get/unload/reparent, the tree linkage, the
 * DISK->ACTIVE/VISIBLE ordinal walk, the missing-parent reparent over the
 * MessageSink seam (with and without a sink), the load-failure callback, the
 * environment cell, the thread-local active cell and the statistics provider.
 */
package org.jdesktop.wonderland.client.cell;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.CacheableCell;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.CacheableEnvCell;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.PreParentedCell;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.RecordingSink;
import org.jdesktop.wonderland.client.cell.CellTestFixtures.ThrowingClientState;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.messages.CellReparentMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CellCacheBasicImplTest {

    private CellCacheBasicImpl cache;

    @BeforeEach
    void setUp() {
        cache = new CellCacheBasicImpl();
    }

    @AfterEach
    void tearDown() {
        CellTestFixtures.PRE_PARENT = null;
    }

    private Cell load(CellID id, String className, CellID parentId) {
        return cache.loadCell(id, className, null, parentId,
                CellTestFixtures.translation(0f, 0f, 0f), null, "cell-" + id);
    }

    @Test
    void loadRegistersRootCell() {
        CellID id = CellTestFixtures.id(1);
        Cell cell = load(id, CacheableCell.class.getName(), null);
        assertThat(cell).isNotNull();
        assertThat(cache.getCell(id)).isSameAs(cell);
        assertThat(cache.getCells()).containsExactly(cell);
        assertThat(cell.getCellCache()).isSameAs(cache);   // the M2d back-reference
        assertThat(cache.getRootCells()).containsExactly(cell);
        // no view, not the env cell -> stays DISK
        assertThat(cell.getStatus()).isSameAs(CellStatus.DISK);
    }

    @Test
    void loadLinksChildToParent() {
        CellID parentId = CellTestFixtures.id(1);
        Cell parent = load(parentId, CacheableCell.class.getName(), null);
        CellID childId = CellTestFixtures.id(2);
        Cell child = load(childId, CacheableCell.class.getName(), parentId);
        assertThat(child.getParent()).isSameAs(parent);
        assertThat(parent.getChildren()).containsExactly(child);
        // only the parent is a root
        assertThat(cache.getRootCells()).containsExactly(parent);
    }

    @Test
    void duplicateLoadIsRejected() {
        CellID id = CellTestFixtures.id(1);
        assertThat(load(id, CacheableCell.class.getName(), null)).isNotNull();
        assertThat(load(id, CacheableCell.class.getName(), null)).isNull();
    }

    @Test
    void unknownClassLoadReturnsNullAndIsNotRegistered() {
        CellID id = CellTestFixtures.id(1);
        assertThat(load(id, "com.example.NoSuchCell", null)).isNull();
        assertThat(cache.getCell(id)).isNull();
        assertThat(cache.getCells()).isEmpty();
    }

    @Test
    void missingParentSendsReparentOverTheSink() {
        RecordingSink sink = new RecordingSink();
        cache.setMessageSink(sink);
        CellID childId = CellTestFixtures.id(2);
        CellID missingParent = CellTestFixtures.id(99);
        Cell cell = load(childId, CacheableCell.class.getName(), missingParent);
        assertThat(cell).isNull();               // the child is dropped
        assertThat(sink.sent).hasSize(1);
        assertThat(sink.sent.get(0)).isInstanceOf(CellReparentMessage.class);
    }

    @Test
    void missingParentWithoutSinkJustDropsTheCell() {
        CellID childId = CellTestFixtures.id(2);
        CellID missingParent = CellTestFixtures.id(99);
        assertThat(load(childId, CacheableCell.class.getName(), missingParent)).isNull();
    }

    @Test
    void alreadyParentedChildHitsTheMultipleParentCatch() {
        CellID parentId = CellTestFixtures.id(1);
        Cell parent = load(parentId, CacheableCell.class.getName(), null);
        CellTestFixtures.PRE_PARENT = parent;
        CellID childId = CellTestFixtures.id(2);
        // PreParentedCell attaches itself to PRE_PARENT in its ctor, so the
        // cache's own parent.addChild throws MultipleParentException -> caught.
        Cell cell = load(childId, PreParentedCell.class.getName(), parentId);
        assertThat(cell).isNotNull();            // the catch does not abort the load
        assertThat(cache.getCell(childId)).isSameAs(cell);
    }

    @Test
    void loadFailureFiresTheListener() {
        final List<CellID> failed = new ArrayList<>();
        cache.addCellCacheListener(new CellCache.CellCacheListener() {
            @Override
            public void cellLoaded(CellID cellId, Cell cell) {
            }

            @Override
            public void cellLoadFailed(CellID cellId, String className, CellID parentCellID,
                    Throwable cause) {
                failed.add(cellId);
            }

            @Override
            public void cellUnloaded(CellID cellId, Cell cell) {
            }
        });
        CellID id = CellTestFixtures.id(1);
        Cell cell = cache.loadCell(id, CacheableCell.class.getName(), null, null,
                CellTestFixtures.translation(0f, 0f, 0f), new ThrowingClientState(), "x");
        assertThat(cell).isNull();
        assertThat(failed).containsExactly(id);
    }

    @Test
    void configureCellRenamesAndAppliesState() {
        CellID id = CellTestFixtures.id(1);
        Cell cell = load(id, CacheableCell.class.getName(), null);
        cache.configureCell(id, CellTestFixtures.namedState("renamed"), "renamed");
        assertThat(cell.getName()).isEqualTo("renamed");
        // same name -> no rename, and a null state is simply skipped
        cache.configureCell(id, null, "renamed");
        assertThat(cell.getName()).isEqualTo("renamed");
        // unknown cell -> warning, no throw
        cache.configureCell(CellTestFixtures.id(99), null, null);
        // null name with a real state -> the state applies, and its own
        // configData name is what reaches the cell (setClientState contract)
        cache.configureCell(id, CellTestFixtures.namedState("x"), null);
        assertThat(cell.getName()).isEqualTo("x");
    }

    @Test
    void unloadDetachesAndWalksDownToDisk() {
        CellID parentId = CellTestFixtures.id(1);
        Cell parent = load(parentId, CacheableCell.class.getName(), null);
        CellID childId = CellTestFixtures.id(2);
        Cell child = load(childId, CacheableCell.class.getName(), parentId);

        final List<CellID> unloaded = new ArrayList<>();
        cache.addCellCacheListener(new CellCache.CellCacheListener() {
            @Override
            public void cellLoaded(CellID cellId, Cell c) {
            }

            @Override
            public void cellLoadFailed(CellID cellId, String cn, CellID pid, Throwable cause) {
            }

            @Override
            public void cellUnloaded(CellID cellId, Cell c) {
                unloaded.add(cellId);
            }
        });

        cache.unloadCell(childId);
        assertThat(unloaded).containsExactly(childId);
        assertThat(cache.getCell(childId)).isNull();
        assertThat(child.getParent()).isNull();
        assertThat(parent.getChildren()).isEmpty();

        // unloading the root drops it from rootCells
        cache.deleteCell(parentId);
        assertThat(cache.getRootCells()).isEmpty();
        // unloading an unknown cell is a warning-only no-op
        cache.unloadCell(CellTestFixtures.id(99));
    }

    @Test
    void changeParentReLinksTheTree() {
        CellID aId = CellTestFixtures.id(1);
        Cell a = load(aId, CacheableCell.class.getName(), null);
        CellID bId = CellTestFixtures.id(2);
        Cell b = load(bId, CacheableCell.class.getName(), null);
        CellID childId = CellTestFixtures.id(3);
        Cell child = load(childId, CacheableCell.class.getName(), aId);

        cache.changeParent(childId, bId, CellTestFixtures.translation(5f, 0f, 0f));
        assertThat(child.getParent()).isSameAs(b);
        assertThat(a.getChildren()).isEmpty();
        assertThat(b.getChildren()).containsExactly(child);

        // reparent to an unknown parent id -> back to the root set (not env)
        cache.changeParent(childId, CellTestFixtures.id(99),
                CellTestFixtures.translation(0f, 0f, 0f));
        assertThat(child.getParent()).isNull();
        assertThat(cache.getRootCells()).contains(child);

        // unknown subject -> warning, no throw
        cache.changeParent(CellTestFixtures.id(98), bId,
                CellTestFixtures.translation(0f, 0f, 0f));
    }

    @Test
    void changeParentIntoAnAlreadyParentedChildHitsTheMultipleParentCatch() {
        CellID aId = CellTestFixtures.id(1);
        Cell a = load(aId, CacheableCell.class.getName(), null);
        CellID bId = CellTestFixtures.id(2);
        Cell b = load(bId, CacheableCell.class.getName(), null);
        CellID childId = CellTestFixtures.id(3);
        Cell child = load(childId, CacheableCell.class.getName(), aId);
        // child still hangs off a, but a is itself parented to b, so a's
        // addChild(c) throws MultipleParentException -> caught, load continues
        a.setParent(b);
        cache.changeParent(childId, aId, CellTestFixtures.translation(0f, 0f, 0f));
        assertThat(cache.getCell(childId)).isSameAs(child);
    }

    @Test
    void changeParentOfTheEnvironmentCellLeavesTheRootSetEmpty() {
        CellID envId = CellID.getEnvironmentCellID();
        Cell env = cache.loadCell(envId, CacheableEnvCell.class.getName(), null, null,
                CellTestFixtures.translation(0f, 0f, 0f), null, "env");
        cache.changeParent(envId, CellTestFixtures.id(99),
                CellTestFixtures.translation(0f, 0f, 0f));
        assertThat(env.getParent()).isNull();
        assertThat(cache.getRootCells()).isEmpty();
    }

    @Test
    void theViewWalkIsUnconditionalPerTheOriginal() {
        CellID id = CellTestFixtures.id(1);
        Cell cell = load(id, CacheableCell.class.getName(), null);
        assertThat(cell.getStatus()).isSameAs(CellStatus.DISK);
        // M2f restores the original setViewCell: it has NO null guard, so even
        // handing it a null view walks every parentless cell up to VISIBLE --
        // the 2006 quirk the boolean seam used to hide.
        cache.setViewCell(null);
        assertThat(cache.getViewCell()).isNull();
        assertThat(cell.getStatus()).isSameAs(CellStatus.VISIBLE);
    }

    @Test
    void childrenOfAVisibleParentSkipTheTreeWalkRung() {
        CellID rootId = CellTestFixtures.id(1);
        cache.setViewCell(CellTestFixtures.viewCell(cache));
        // loaded while the view is up -> loadCell walks it straight to VISIBLE
        Cell root = load(rootId, CacheableCell.class.getName(), null);
        assertThat(root.getStatus()).isSameAs(CellStatus.VISIBLE);
        CellID childId = CellTestFixtures.id(2);
        Cell child = load(childId, CacheableCell.class.getName(), rootId);
        assertThat(child.getStatus()).isSameAs(CellStatus.VISIBLE);
        // a second walk meets the root already at the required status: the
        // rung is skipped, but the recursion still visits the child
        int rootRungs = ((CacheableCell) root).statusCalls.size();
        cache.setViewCell(CellTestFixtures.viewCell(cache));
        assertThat(((CacheableCell) root).statusCalls).hasSize(rootRungs);
        assertThat(child.getStatus()).isSameAs(CellStatus.VISIBLE);
    }

    @Test
    void environmentCellLoadsActive() {
        CellID envId = CellID.getEnvironmentCellID();
        Cell env = cache.loadCell(envId, CacheableEnvCell.class.getName(), null, null,
                CellTestFixtures.translation(0f, 0f, 0f), null, "env");
        assertThat(env).isNotNull();
        assertThat(cache.getEnvironmentCell()).isSameAs(env);
        // env cells are walked up to ACTIVE even with no view
        assertThat(env.getStatus()).isSameAs(CellStatus.ACTIVE);
        // the env cell is never a "root" in the registry
        assertThat(cache.getRootCells()).isEmpty();
    }

    @Test
    void theDefaultEnvironmentClassIsInstantiableByTheCache() {
        // The exact string a live walk-in receives: WorldModel.ENVIRONMENT_CLASS
        // (the Spring bean's no-arg default) names EnvironmentCell directly, and
        // instantiateCell reflects it open. When that class was abstract this
        // threw InstantiationException on the transport thread -> a SEVERE log the
        // desktop surfaced as its crash dialog. Every other env test loaded a
        // concrete fixture subclass and so never exercised the production default;
        // this pins the name the server actually broadcasts.
        CellID envId = CellID.getEnvironmentCellID();
        Cell env = cache.loadCell(envId, EnvironmentCell.class.getName(), null, null,
                CellTestFixtures.translation(0f, 0f, 0f), null, "env");
        assertThat(env).isNotNull();
        assertThat(env).isInstanceOf(EnvironmentCell.class);
        assertThat(cache.getEnvironmentCell()).isSameAs(env);
        assertThat(env.getStatus()).isSameAs(CellStatus.ACTIVE);
    }

    @Test
    void hasViewWalksTheWholeTreeToVisible() {
        CellID rootId = CellTestFixtures.id(1);
        Cell root = load(rootId, CacheableCell.class.getName(), null);
        CellID childId = CellTestFixtures.id(2);
        Cell child = load(childId, CacheableCell.class.getName(), rootId);

        cache.setViewCell(CellTestFixtures.viewCell(cache));
        assertThat(cache.getViewCell()).isNotNull();
        assertThat(root.getStatus()).isSameAs(CellStatus.VISIBLE);
        assertThat(child.getStatus()).isSameAs(CellStatus.VISIBLE);
        // walking through every rung in order, increasing
        assertThat(((CacheableCell) child).statusCalls)
                .containsExactly("INACTIVE/true", "ACTIVE/true",
                        "RENDERING/true", "VISIBLE/true");
    }

    @Test
    void activeCellThreadLocalIsVisibleDuringTheWalk() {
        CellID id = CellTestFixtures.id(1);
        cache.setViewCell(CellTestFixtures.viewCell(cache));
        Cell cell = load(id, CacheableCell.class.getName(), null);
        // during loadCell's status walk the cache marks the cell current...
        assertThat(((CacheableCell) cell).activeDuringStatusWalk).isSameAs(cell);
        // ...and clears it in the finally block
        assertThat(CellCacheBasicImpl.getCurrentActiveCell()).isNull();
    }

    @Test
    void loadRecordsATimingStatThroughTheProvider() {
        CellTestFixtures.RecordingStatsProvider provider =
                new CellTestFixtures.RecordingStatsProvider();
        cache.getStatistics().setProvider(provider);
        CellID id = CellTestFixtures.id(1);
        Cell cell = load(id, CacheableCell.class.getName(), null);
        // loadCell always records a LoadTime stat
        assertThat(provider.store).containsKey("LoadTime");
        // and the status walk records a per-status stat re-used across rungs
        cache.setViewCell(CellTestFixtures.viewCell(cache));
        load(CellTestFixtures.id(2), CacheableCell.class.getName(), null);
        assertThat(provider.store).containsKeys("ACTIVE-time", "RENDERING-time",
                "VISIBLE-time");
        // setHasView(true) walked the already-loaded root up to VISIBLE
        assertThat(cell.getStatus()).isSameAs(CellStatus.VISIBLE);
    }

    @Test
    void defaultProviderIsNoopAndSwappable() {
        assertThat(cache.getStatistics()).isNotNull();
        assertThat(cache.getStatistics().getProvider())
                .isInstanceOf(CellStatistics.NoopCellStatisticsProvider.class);
        CellStatistics.CellStatisticsSPI spi = new CellTestFixtures.RecordingStatsProvider();
        cache.getStatistics().setProvider(spi);
        assertThat(cache.getStatistics().getProvider()).isSameAs(spi);
        load(CellTestFixtures.id(1), CacheableCell.class.getName(), null);
    }

    @Test
    void nullClassLoaderFallsBackToTheDefaultLoader() {
        CellCacheBasicImpl withNull = new CellCacheBasicImpl(null);
        assertThat(withNull.loadCell(CellTestFixtures.id(7),
                CacheableCell.class.getName(), null, null,
                CellTestFixtures.translation(0f, 0f, 0f), null, "n"))
                .isNotNull();
    }

    @Test
    void removedListenerStopsHearingTheLoadEvents() {
        java.util.List<org.jdesktop.wonderland.common.cell.CellID> loaded =
                new java.util.ArrayList<>();
        CellCache.CellCacheListener listener = new CellCache.CellCacheListener() {
            @Override
            public void cellLoaded(org.jdesktop.wonderland.common.cell.CellID cellId,
                    Cell cell) {
                loaded.add(cellId);
            }

            @Override
            public void cellLoadFailed(org.jdesktop.wonderland.common.cell.CellID cellId,
                    String className, org.jdesktop.wonderland.common.cell.CellID parentCellID,
                    Throwable cause) {
            }

            @Override
            public void cellUnloaded(org.jdesktop.wonderland.common.cell.CellID cellId,
                    Cell cell) {
            }
        };
        cache.addCellCacheListener(listener);
        load(CellTestFixtures.id(1), CacheableCell.class.getName(), null);
        assertThat(loaded).hasSize(1);
        cache.removeCellCacheListener(listener);
        load(CellTestFixtures.id(2), CacheableCell.class.getName(), null);
        assertThat(loaded).hasSize(1);
    }
}
