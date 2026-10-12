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
   name = "wl-module-list"
)
public class ModuleList {
   @XmlElementRefs({@XmlElementRef})
   private ModuleInfo[] moduleInfos = new ModuleInfo[0];
   private static JAXBContext jaxbContext = null;

   public ModuleList() {
   }

   public ModuleList(ModuleInfo[] moduleInfos) {
      this.moduleInfos = moduleInfos;
   }

   @XmlTransient
   public ModuleInfo[] getModuleInfos() {
      return this.moduleInfos;
   }

   public void setModuleInfos(ModuleInfo[] moduleInfos) {
      this.moduleInfos = moduleInfos;
   }

   public static ModuleList decode(Reader r) throws JAXBException {
      Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
      ModuleList children = (ModuleList)unmarshaller.unmarshal(r);
      return children;
   }

   public void encode(Writer w) throws JAXBException {
      Marshaller marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty("jaxb.formatted.output", true);
      marshaller.marshal(this, w);
   }

   static {
      try {
         jaxbContext = JAXBContext.newInstance(new Class[]{ModuleInfo.class, ModuleList.class});
      } catch (JAXBException excp) {
         Logger.getLogger(ModuleList.class.getName()).log(Level.WARNING, "Unable to create JAXBContext", excp);
      }

   }
}
