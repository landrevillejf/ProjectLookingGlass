package org.jdesktop.wonderland.common.cell.state;

import javax.xml.bind.annotation.XmlTransient;
import org.jdesktop.wonderland.common.cell.CellTransform;

@XmlTransient
public class ViewComponentServerState extends CellComponentServerState {
   private CellTransform cellTransform = null;

   public ViewComponentServerState(CellTransform cellTransform) {
      this.cellTransform = cellTransform;
   }

   public String getServerComponentClassName() {
      return null;
   }

   public CellTransform getCellTransform() {
      return this.cellTransform;
   }

   public void setCellTransform(CellTransform cellTransform) {
      this.cellTransform = cellTransform;
   }
}
