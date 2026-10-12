package org.jdesktop.wonderland.common.cell.state;

import java.io.Serializable;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import org.jdesktop.wonderland.common.cell.state.annotation.ServerState;

@XmlRootElement(
   name = "avatar-cell"
)
@XmlAccessorType(XmlAccessType.FIELD)
@ServerState
public class AvatarCellServerState extends CellServerState implements Serializable {
   private String userName;

   public AvatarCellServerState() {
   }

   public AvatarCellServerState(String userName) {
      this();
      this.userName = userName;
   }

   public String getServerClassName() {
      return null;
   }

   public String getUserName() {
      return this.userName;
   }
}
