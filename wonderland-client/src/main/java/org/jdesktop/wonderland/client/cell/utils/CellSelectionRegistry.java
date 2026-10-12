package org.jdesktop.wonderland.client.cell.utils;

import org.jdesktop.wonderland.client.cell.utils.spi.CellSelectionSPI;
import org.jdesktop.wonderland.common.ExperimentalAPI;

/**
 * Holds the process-wide {@link CellSelectionSPI} the cell-creation dialog
 * asks for the current selection. A plain singleton behind {@code synchronized}
 * static accessors: {@link #register} installs the SPI, {@link #unregister}
 * clears it <em>only</em> when the same instance is still installed (so a late
 * unregister from a replaced provider cannot blank the newer one), and
 * {@link #getCellSelectionSPI} returns whatever is current, {@code null} when
 * nothing is registered. No scene-graph dependency: this is the selection
 * registry the M2a {@code CellSelectionSPI} feeds, and the contract the
 * {@code Cell} base class will read through once it lands in M2c.
 */
@ExperimentalAPI
public class CellSelectionRegistry {
   private CellSelectionSPI cellSelectionSPI;

   private CellSelectionRegistry() {
      this.cellSelectionSPI = null;
   }

   private static CellSelectionRegistry getInstance() {
      return CellSelectionRegistry.RegistrySingleton.INSTANCE;
   }

   public static synchronized void register(CellSelectionSPI spi) {
      getInstance().cellSelectionSPI = spi;
   }

   public static synchronized void unregister(CellSelectionSPI spi) {
      if (getInstance().cellSelectionSPI == spi) {
         getInstance().cellSelectionSPI = null;
      }
   }

   public static synchronized CellSelectionSPI getCellSelectionSPI() {
      return getInstance().cellSelectionSPI;
   }

   private static final class RegistrySingleton {
      private static final CellSelectionRegistry INSTANCE = new CellSelectionRegistry();
   }
}
