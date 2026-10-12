package org.jdesktop.wonderland.common.logparser;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
   name = "",
   propOrder = {"clazz", "method", "line"}
)
@XmlRootElement(
   name = "frame"
)
public class Frame {
   @XmlElement(
      name = "class",
      required = true
   )
   protected String clazz;
   @XmlElement(
      required = true
   )
   protected String method;
   protected String line;

   public String getClazz() {
      return this.clazz;
   }

   public void setClazz(String value) {
      this.clazz = value;
   }

   public String getMethod() {
      return this.method;
   }

   public void setMethod(String value) {
      this.method = value;
   }

   public String getLine() {
      return this.line;
   }

   public void setLine(String value) {
      this.line = value;
   }
}
