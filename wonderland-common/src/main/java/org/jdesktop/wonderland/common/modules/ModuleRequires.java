package org.jdesktop.wonderland.common.modules;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElements;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

@XmlRootElement(
   name = "module-requires"
)
public class ModuleRequires {
   @XmlElements({@XmlElement(
   name = "requires"
)})
   private ModuleInfo[] requires = new ModuleInfo[0];
   private static JAXBContext jaxbContext = null;

   public ModuleRequires() {
   }

   public ModuleRequires(ModuleInfo[] requires) {
      this.requires = requires;
   }

   @XmlTransient
   public ModuleInfo[] getRequires() {
      return this.requires;
   }

   public void setRequires(ModuleInfo[] requires) {
      this.requires = requires;
   }

   public String toString() {
      StringBuilder str = new StringBuilder();

      for(ModuleInfo info : this.requires) {
         str.append("  " + info.toString() + " ");
      }

      return str.toString();
   }

   public boolean isRequired(String uniqueName) {
      for(ModuleInfo info : this.requires) {
         if (uniqueName.compareTo(info.getName()) == 0) {
            return true;
         }
      }

      return false;
   }

   public static ModuleRequires decode(InputStream is) throws JAXBException {
      Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
      return (ModuleRequires)unmarshaller.unmarshal(is);
   }

   public static ModuleRequires decode(Reader r) throws JAXBException {
      Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
      return (ModuleRequires)unmarshaller.unmarshal(r);
   }

   public void encode(OutputStream os) throws JAXBException {
      Marshaller marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty("jaxb.formatted.output", true);
      marshaller.marshal(this, os);
   }

   static {
      try {
         jaxbContext = JAXBContext.newInstance(new Class[]{ModuleRequires.class});
      } catch (JAXBException excp) {
         Logger.getLogger(ModuleRequires.class.getName()).log(Level.WARNING, "Unable to create JAXBContext", excp);
      }

   }
}
