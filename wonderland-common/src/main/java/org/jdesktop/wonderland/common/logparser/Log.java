package org.jdesktop.wonderland.common.logparser;

import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
   name = "",
   propOrder = {"record"}
)
@XmlRootElement(
   name = "log"
)
public class Log {
   protected List record;

   public List getRecord() {
      if (this.record == null) {
         this.record = new ArrayList();
      }

      return this.record;
   }
}
