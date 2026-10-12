package org.jdesktop.wonderland.common.comms;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.util.HashMap;

public class WonderlandObjectInputStream extends ObjectInputStream {
   private static HashMap idToDesc = new HashMap();
   private ClassLoader classLoader;

   public WonderlandObjectInputStream(InputStream in) throws IOException {
      this(in, (ClassLoader)null);
   }

   public WonderlandObjectInputStream(InputStream in, ClassLoader classLoader) throws IOException {
      super(in);
      this.classLoader = classLoader;
      if (this.classLoader == null) {
         this.classLoader = this.getClass().getClassLoader();
      }

   }

   protected Class resolveClass(ObjectStreamClass osc) throws IOException, ClassNotFoundException {
      return this.classLoader == null ? super.resolveClass(osc) : Class.forName(osc.getName(), true, this.classLoader);
   }

   protected ObjectStreamClass readClassDescriptor() throws ClassNotFoundException, IOException {
      int id = this.readInt();
      Class lookupClass;
      if (id == Integer.MIN_VALUE) {
         String className = this.readUTF();
         lookupClass = Class.forName(className, true, this.classLoader);
      } else {
         lookupClass = Class.forName((String)idToDesc.get(id), true, this.classLoader);
      }

      ObjectStreamClass ret = ObjectStreamClass.lookup(lookupClass);
      if (ret == null) {
         throw new IOException("Unknown class ID " + id);
      } else {
         return ret;
      }
   }

   static {
      WonderlandObjectOutputStream.populateIdToDesc(idToDesc);
   }
}
