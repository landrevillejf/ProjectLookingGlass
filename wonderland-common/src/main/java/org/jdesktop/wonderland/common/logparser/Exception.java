package org.jdesktop.wonderland.common.logparser;

import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
   name = "",
   propOrder = {"message", "frame"}
)
@XmlRootElement(
   name = "exception"
)
public class Exception {
   protected String message;
   @XmlElement(
      required = true
   )
   protected List frame;

   public String getMessage() {
      return this.message;
   }

   public void setMessage(String value) {
      this.message = value;
   }

   public List getFrame() {
      if (this.frame == null) {
         this.frame = new ArrayList();
      }

      return this.frame;
   }
}
