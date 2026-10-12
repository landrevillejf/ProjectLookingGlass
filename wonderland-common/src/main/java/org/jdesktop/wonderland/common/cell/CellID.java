package org.jdesktop.wonderland.common.cell;

import java.io.Serializable;
import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.InternalAPI;

@ExperimentalAPI
public class CellID implements Serializable {
   private long id;
   private transient String str = null;
   private static final CellID invalidCellID = new CellID(Long.MIN_VALUE);
   private static final CellID environmentCellID = new CellID(0L);
   private static final long firstCellID = 1L;

   @InternalAPI
   public CellID(long id) {
      this.id = id;
   }

   public boolean equals(Object obj) {
      return obj instanceof CellID && ((CellID)obj).id == this.id;
   }

   public int hashCode() {
      return (int)this.id;
   }

   public String toString() {
      if (this.str == null) {
         this.str = Long.toString(this.id);
      }

      return this.str;
   }

   public static long getFirstCellID() {
      return 1L;
   }

   public static CellID getInvalidCellID() {
      return invalidCellID;
   }

   public static CellID getEnvironmentCellID() {
      return environmentCellID;
   }
}
