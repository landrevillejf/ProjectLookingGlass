/*
 * M2a pin: the client cell factory SPI shapes.
 *
 * These interfaces are what the M2b registry stores, and their generic bounds
 * (<T extends CellServerState> / <T extends CellComponentServerState>) are the
 * contract the renderer depends on to get a *concrete* default state back. A
 * bare anonymous implementation both proves the bound compiles in a consumer
 * and proves the erased method still returns the concrete class at runtime --
 * which is precisely the covariance the decompiled CellServerState subclasses
 * rely on. No registry / comms / scene-graph dependency: this is a pure-shape
 * test that can run headless, today, before Cell itself lands in M2b.
 */
package org.jdesktop.wonderland.client.cell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Image;
import java.util.Properties;
import org.jdesktop.wonderland.client.cell.registry.spi.CellComponentFactorySPI;
import org.jdesktop.wonderland.client.cell.registry.spi.CellFactorySPI;
import org.jdesktop.wonderland.client.cell.utils.CellCreationException;
import org.jdesktop.wonderland.client.cell.utils.spi.CellSelectionSPI;
import org.jdesktop.wonderland.common.cell.state.CellComponentServerState;
import org.jdesktop.wonderland.common.cell.state.CellServerState;
import org.jdesktop.wonderland.common.cell.state.ModelCellComponentServerState;
import org.jdesktop.wonderland.common.cell.state.ModelCellServerState;
import org.junit.jupiter.api.Test;

class CellFactorySpiTest {

    @Test
    void cellFactorySpisGenericDefaultStateReturnsConcreteSubclass() {
        CellFactorySPI factory = new CellFactorySPI() {
            @Override public String[] getExtensions() {
                return new String[] {"dae", "obj"};
            }
            @Override public <T extends CellServerState> T getDefaultCellServerState(Properties props) {
                // Returns the concrete subclass; the generic bound lets the
                // caller assign it to ModelCellServerState with no cast.
                ModelCellServerState s = new ModelCellServerState();
                s.setName(props.getProperty("name", "default"));
                @SuppressWarnings("unchecked")
                T ret = (T) s;
                return ret;
            }
            @Override public String getDisplayName() {
                return "Model";
            }
            @Override public Image getPreviewImage() {
                return null;
            }
        };

        assertThat(factory.getExtensions()).containsExactly("dae", "obj");
        assertThat(factory.getDisplayName()).isEqualTo("Model");

        Properties props = new Properties();
        props.setProperty("name", "lamp");
        // Covariant assignment through the generic bound, no cast at the call site.
        ModelCellServerState state = factory.getDefaultCellServerState(props);
        assertThat(state).isInstanceOf(ModelCellServerState.class);
        assertThat(state.getName()).isEqualTo("lamp");
    }

    @Test
    void cellComponentFactorySpiReturnsConcreteServerComponentState() {
        CellComponentFactorySPI spi = new CellComponentFactorySPI() {
            @Override public <T extends CellComponentServerState> T getDefaultCellComponentServerState() {
                @SuppressWarnings("unchecked")
                T ret = (T) new ModelCellComponentServerState();
                return ret;
            }
            @Override public String getDisplayName() {
                return "Model Component";
            }
            @Override public String getDescription() {
                return "renders a Collada model";
            }
        };

        ModelCellComponentServerState comp = spi.getDefaultCellComponentServerState();
        assertThat(comp).isInstanceOf(ModelCellComponentServerState.class);
        assertThat(spi.getDisplayName()).isEqualTo("Model Component");
        assertThat(spi.getDescription()).contains("Collada");
    }

    @Test
    void cellSelectionSpiReturnsFactoryAndThrowsOnUnknown() throws CellCreationException {
        CellFactorySPI known = new CellFactorySPI() {
            @Override public String[] getExtensions() { return new String[0]; }
            @Override public <T extends CellServerState> T getDefaultCellServerState(Properties p) {
                return null;
            }
            @Override public String getDisplayName() { return "Known"; }
            @Override public Image getPreviewImage() { return null; }
        };
        CellSelectionSPI selection = new CellSelectionSPI() {
            @Override public CellFactorySPI getCellSelection(String id) throws CellCreationException {
                if (!"known".equals(id)) {
                    throw new CellCreationException();
                }
                return known;
            }
        };

        assertThat(selection.getCellSelection("known")).isSameAs(known);
        assertThatThrownBy(() -> selection.getCellSelection("nope"))
                .isInstanceOf(CellCreationException.class);
    }
}
