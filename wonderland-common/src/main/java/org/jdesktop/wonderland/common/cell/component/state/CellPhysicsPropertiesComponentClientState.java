package org.jdesktop.wonderland.common.cell.component.state;

import java.util.HashMap;
import org.jdesktop.wonderland.common.cell.state.CellComponentClientState;

public class CellPhysicsPropertiesComponentClientState extends CellComponentClientState {
   private HashMap propertiesMap = new HashMap();

   public PhysicsProperties getPhyiscsProperties(String entityName) {
      System.err.println("Prop count " + this.propertiesMap.size());
      return (PhysicsProperties)this.propertiesMap.get(entityName);
   }

   public void addPhysicsProperties(String entityName, PhysicsProperties properties) {
      this.propertiesMap.put(entityName, properties);
   }
}
