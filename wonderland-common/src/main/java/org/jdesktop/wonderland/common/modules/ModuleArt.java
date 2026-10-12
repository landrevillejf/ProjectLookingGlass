package org.jdesktop.wonderland.common.modules;

import java.io.Reader;
import java.io.Serializable;
import java.io.Writer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

@XmlRootElement(
   name = "wl-module-art"
)
public class ModuleArt implements Serializable {
   @XmlElement(
      name = "path",
      required = true
   )
   private String path = null;
   private static JAXBContext jaxbContext = null;

   public ModuleArt() {
   }

   public ModuleArt(String path) {
      this.path = path;
   }

   @XmlTransient
   public String getPath() {
      return this.path;
   }

   public void setPath(String path) {
      this.path = path;
   }

   public static ModuleArt decode(Reader r) throws JAXBException {
      Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
      return (ModuleArt)unmarshaller.unmarshal(r);
   }

   public void encode(Writer w) throws JAXBException {
      Marshaller marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty("jaxb.formatted.output", true);
      marshaller.marshal(this, w);
   }

   static {
      try {
         jaxbContext = JAXBContext.newInstance(new Class[]{ModuleArt.class});
      } catch (JAXBException excp) {
         Logger.getLogger(ModuleArt.class.getName()).log(Level.WARNING, "Unable to create JAXBContext", excp);
      }

   }
}
