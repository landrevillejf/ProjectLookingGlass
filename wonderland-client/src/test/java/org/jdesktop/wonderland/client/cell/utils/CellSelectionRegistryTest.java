/*
 * M2b pin: the selection registry that the M2a CellSelectionSPI feeds.
 *
 * CellSelectionRegistry is process-wide singleton state, so every case starts
 * from a known-empty registry (JUnit runs the cases in one JVM) and restores
 * the slot in @AfterEach -- otherwise the shared mutable singleton would leak
 * across tests. The behaviours pinned here are the ones the cell-creation
 * dialog depends on: register installs, get reads back the exact instance,
 * unregister blanks only when the SAME instance is still installed (a stale
 * unregister from a replaced provider must not drop the newer one), and get
 * returns null when nothing is registered. Pure JDK, runs headless.
 */
package org.jdesktop.wonderland.client.cell.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Image;
import java.util.Properties;
import org.jdesktop.wonderland.client.cell.registry.spi.CellFactorySPI;
import org.jdesktop.wonderland.client.cell.utils.spi.CellSelectionSPI;
import org.jdesktop.wonderland.common.cell.state.CellServerState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CellSelectionRegistryTest {

    /** A minimal, distinguishable CellSelectionSPI: the factory it returns is
     * its own identity, so the test can tell two providers apart. */
    private static CellSelectionSPI provider(String name) {
        CellFactorySPI factory = new CellFactorySPI() {
            @Override public String[] getExtensions() {
                return new String[] {name};
            }
            @Override public <T extends CellServerState> T getDefaultCellServerState(Properties props) {
                return null;
            }
            @Override public String getDisplayName() {
                return name;
            }
            @Override public Image getPreviewImage() {
                return null;
            }
        };
        return extension -> factory;
    }

    @AfterEach
    void clearRegistry() {
        CellSelectionSPI current = CellSelectionRegistry.getCellSelectionSPI();
        if (current != null) {
            CellSelectionRegistry.unregister(current);
        }
        assertThat(CellSelectionRegistry.getCellSelectionSPI()).isNull();
    }

    @Test
    void emptyRegistryReadsNull() {
        assertThat(CellSelectionRegistry.getCellSelectionSPI()).isNull();
    }

    @Test
    void registerInstallsAndGetReadsBackTheSameInstance() {
        CellSelectionSPI spi = provider("model");
        CellSelectionRegistry.register(spi);
        assertThat(CellSelectionRegistry.getCellSelectionSPI()).isSameAs(spi);
    }

    @Test
    void registerReplacesTheInstalledProvider() {
        CellSelectionRegistry.register(provider("first"));
        CellSelectionSPI second = provider("second");
        CellSelectionRegistry.register(second);
        assertThat(CellSelectionRegistry.getCellSelectionSPI()).isSameAs(second);
    }

    @Test
    void unregisterBlanksOnlyTheMatchingInstance() {
        CellSelectionSPI first = provider("first");
        CellSelectionSPI second = provider("second");
        CellSelectionRegistry.register(first);
        CellSelectionRegistry.register(second);

        // A stale unregister of the replaced provider must leave the current one.
        CellSelectionRegistry.unregister(first);
        assertThat(CellSelectionRegistry.getCellSelectionSPI()).isSameAs(second);

        // Only unregistering the instance still installed blanks the slot.
        CellSelectionRegistry.unregister(second);
        assertThat(CellSelectionRegistry.getCellSelectionSPI()).isNull();
    }
}
