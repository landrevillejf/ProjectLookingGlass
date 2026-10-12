package org.jdesktop.wonderland.common.modules;

import java.io.Reader;
import java.io.Writer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlElementRef;
import javax.xml.bind.annotation.XmlElementRefs;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

@XmlRootElement(
   name = "wl-module-art-list"
)
public class ModuleArtList {
   @XmlElementRefs({@XmlElementRef})
   private ModuleArt[] moduleArt = new ModuleArt[0];
   private static JAXBContext jaxbContext = null;

   public ModuleArtList() {
   }

   public ModuleArtList(ModuleArt[] moduleArt) {
      this.moduleArt = moduleArt;
   }

   @XmlTransient
   public ModuleArt[] getModuleArt() {
      return this.moduleArt;
   }

   public void setModuleArt(ModuleArt[] moduleArt) {
      this.moduleArt = moduleArt;
   }

   public static ModuleArtList decode(Reader r) throws JAXBException {
      Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
      ModuleArtList children = (ModuleArtList)unmarshaller.unmarshal(r);
      return children;
   }

   public void encode(Writer w) throws JAXBException {
      Marshaller marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty("jaxb.formatted.output", true);
      marshaller.marshal(this, w);
   }

   static {
      try {
         jaxbContext = JAXBContext.newInstance(new Class[]{ModuleArt.class, ModuleArtList.class});
      } catch (JAXBException excp) {
         Logger.getLogger(ModuleArtList.class.getName()).log(Level.WARNING, "Unable to create JAXBContext", excp);
      }

   }
}
