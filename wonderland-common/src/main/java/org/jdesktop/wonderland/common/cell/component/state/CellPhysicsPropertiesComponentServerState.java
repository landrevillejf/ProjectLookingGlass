package org.jdesktop.wonderland.common.cell.component.state;

import java.util.HashMap;
import java.util.Map;
import org.jdesktop.wonderland.common.cell.state.CellComponentClientState;
import org.jdesktop.wonderland.common.cell.state.CellComponentServerState;

public class CellPhysicsPropertiesComponentServerState extends CellComponentServerState {
   private HashMap propertiesMap = new HashMap();
   public static final String DEFAULT_NAME = "default_entity";

   public String getServerComponentClassName() {
      return "org.jdesktop.wonderland.server.cell.component.CellPhysicsPropertiesComponentMO";
   }

   public PhysicsProperties getPhyiscsProperties(String entityName) {
      return (PhysicsProperties)this.propertiesMap.get(entityName);
   }

   public void addPhysicsProperties(String entityName, PhysicsProperties properties) {
      System.err.println("ServerState addPhyProp " + entityName);
      this.propertiesMap.put(entityName, properties);
   }

   public void getClientState(CellComponentClientState clientState) {
      assert clientState != null;

      System.err.println("SET CLIENT STATE");

      for(Map.Entry e : (java.util.Set<Map.Entry>) this.propertiesMap.entrySet()) {
         System.err.println((String)e.getKey());
         ((CellPhysicsPropertiesComponentClientState)clientState).addPhysicsProperties((String)e.getKey(), (PhysicsProperties)e.getValue());
      }

   }

   public CellPhysicsPropertiesComponentServerState clone(CellPhysicsPropertiesComponentServerState ret) {
      if (ret == null) {
         ret = new CellPhysicsPropertiesComponentServerState();
      }

      System.err.println("CLONING");

      for(Map.Entry e : (java.util.Set<Map.Entry>) this.propertiesMap.entrySet()) {
         System.err.println((String)e.getKey());
         ret.addPhysicsProperties((String)e.getKey(), (PhysicsProperties)e.getValue());
      }

      return ret;
   }
}
