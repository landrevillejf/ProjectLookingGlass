package org.jdesktop.wonderland.common.cell.state;

import org.jdesktop.wonderland.common.cell.ComponentLookupClass;

public class CellComponentUtils {
   public static Class getLookupClass(Class clazz) {
      ComponentLookupClass l = (ComponentLookupClass)clazz.getAnnotation(ComponentLookupClass.class);
      return l != null ? l.value() : clazz;
   }
}
