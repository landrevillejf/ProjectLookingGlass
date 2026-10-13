package org.jdesktop.wonderland.common.cell.state;

import java.io.Serializable;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;
import org.jdesktop.wonderland.common.cell.state.annotation.ServerState;

@XmlRootElement(
   name = "avatar-cell"
)
@XmlAccessorType(XmlAccessType.FIELD)
@ServerState
public class AvatarCellServerState extends CellServerState implements Serializable {
   private String userName;

   /**
    * M6b: optional client-side hint pointing at the bundled or deployed .dae
    * body this avatar should render. Null means "client picks the pool
    * default"; a value is a wla:// URL the AvatarLoadResolver reads through
    * a ClasspathWorldStore. Declared as an optional XML attribute so an
    * avatar-cell element from a pre-M6b server still unmarshals cleanly.
    */
   @XmlAttribute(name = "avatarModelUrl", required = false)
   private String avatarModelUrl;

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

   public String getAvatarModelUrl() {
      return this.avatarModelUrl;
   }

   public void setAvatarModelUrl(String avatarModelUrl) {
      this.avatarModelUrl = avatarModelUrl;
   }
}
