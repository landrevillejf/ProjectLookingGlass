package org.jdesktop.wonderland.common.cell.state;

import java.io.Serializable;
import javax.xml.bind.annotation.XmlRootElement;
import org.jdesktop.wonderland.common.cell.state.annotation.ServerState;

@XmlRootElement(
   name = "model-cell"
)
@ServerState
public class ModelCellServerState extends CellServerState implements Serializable {
   public String getServerClassName() {
      return "org.jdesktop.wonderland.server.cell.ModelCellMO";
   }
}
