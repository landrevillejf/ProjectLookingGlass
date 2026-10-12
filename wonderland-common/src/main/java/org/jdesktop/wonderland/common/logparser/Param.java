package org.jdesktop.wonderland.common.logparser;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.XmlValue;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
   name = "",
   propOrder = {"value"}
)
@XmlRootElement(
   name = "param"
)
public class Param {
   @XmlValue
   protected String value;

   public String getvalue() {
      return this.value;
   }

   public void setvalue(String value) {
      this.value = value;
   }
}
