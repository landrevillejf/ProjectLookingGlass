package org.jdesktop.wonderland.common.cell.state;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class CellClientState implements Serializable {
   private String name = null;
   private Map clientComponentClasses = new HashMap();

   public String getName() {
      return this.name;
   }

   public void setName(String name) {
      this.name = name;
   }

   public String[] getClientComponentClasses() {
      return this.clientComponentClasses == null ? new String[0] : (String[])this.clientComponentClasses.keySet().toArray(new String[this.clientComponentClasses.size()]);
   }

   public CellComponentClientState getCellComponentClientState(String className) {
      return this.clientComponentClasses == null ? null : (CellComponentClientState)this.clientComponentClasses.get(className);
   }

   public void addClientComponentClasses(String[] cellComponenClasses) {
      if (cellComponenClasses != null) {
         for(String s : cellComponenClasses) {
            this.clientComponentClasses.put(s, (Object)null);
         }
      }

   }

   public void addClientComponentClasses(String clientClass, CellComponentClientState clientState) {
      this.clientComponentClasses.put(clientClass, clientState);
   }
}
