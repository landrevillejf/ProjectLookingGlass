package org.jdesktop.wonderland.common.comms;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;
import java.util.HashMap;

public class WonderlandObjectOutputStream extends ObjectOutputStream {
   protected static final int UNKNOWN_DESCRIPTOR = Integer.MIN_VALUE;
   protected static int firstID = -2147483647;
   private static HashMap descToId = new HashMap();
   private static String[] coreClass = new String[]{"org.jdesktop.wonderland.common.cell.messages.MovableMessage", "org.jdesktop.wonderland.common.cell.messages.MovableMessage$ActionType", "org.jdesktop.wonderland.common.cell.messages.CellMessage", "org.jdesktop.wonderland.common.messages.MessageID", "java.lang.Enum", "com.jme.math.Vector3f", "com.jme.math.Quaternion", "org.jdesktop.wonderland.common.messages.Message", "org.jdesktop.wonderland.common.cell.CellID"};

   public WonderlandObjectOutputStream(OutputStream out) throws IOException {
      super(out);
   }

   protected void writeClassDescriptor(ObjectStreamClass desc) throws IOException {
      Integer idObj = (Integer)descToId.get(desc.getName());
      if (idObj == null) {
         this.writeInt(Integer.MIN_VALUE);
         this.writeUTF(desc.forClass().getName());
      } else {
         this.writeInt(idObj);
      }

   }

   static void populateDescToId(HashMap map) {
      int id = firstID;

      for(String clazz : coreClass) {
         map.put(clazz, id++);
      }

   }

   static void populateIdToDesc(HashMap map) {
      int id = firstID;

      for(String clazz : coreClass) {
         map.put(id++, clazz);
      }

   }

   static {
      populateDescToId(descToId);
   }
}
