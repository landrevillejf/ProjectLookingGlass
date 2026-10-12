package org.jdesktop.wonderland.common.cell.state;

import java.io.Serializable;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import org.jdesktop.wonderland.common.cell.state.annotation.ServerState;

@XmlRootElement(
   name = "interaction-component"
)
@ServerState
public class InteractionComponentServerState extends CellComponentServerState implements Serializable {
   private boolean collidable = true;
   private boolean selectable = true;

   @XmlTransient
   public String getServerComponentClassName() {
      return "org.jdesktop.wonderland.server.cell.InteractionComponentMO";
   }

   @XmlElement
   public boolean isCollidable() {
      return this.collidable;
   }

   public void setCollidable(boolean collidable) {
      this.collidable = collidable;
   }

   @XmlElement
   public boolean isSelectable() {
      return this.selectable;
   }

   public void setSelectable(boolean selectable) {
      this.selectable = selectable;
   }
}
