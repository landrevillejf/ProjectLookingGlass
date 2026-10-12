package org.jdesktop.wonderland.common.wfs;

import java.io.Reader;
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
   name = "wfs-world-root"
)
public class WorldRoot {
   @XmlElement(
      name = "root-path"
   )
   private String rootPath = null;
   private static JAXBContext jaxbContext = null;

   public WorldRoot() {
   }

   public WorldRoot(String rootPath) {
      this.rootPath = rootPath;
   }

   @XmlTransient
   public String getRootPath() {
      return this.rootPath;
   }

   public static WorldRoot decode(Reader r) throws JAXBException {
      Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
      return (WorldRoot)unmarshaller.unmarshal(r);
   }

   public void encode(Writer w) throws JAXBException {
      Marshaller marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty("jaxb.formatted.output", true);
      marshaller.marshal(this, w);
   }

   public String toString() {
      return this.rootPath;
   }

   static {
      try {
         jaxbContext = JAXBContext.newInstance(new Class[]{WorldRoot.class});
      } catch (JAXBException excp) {
         Logger.getLogger(WorldRoot.class.getName()).log(Level.WARNING, "Unable to create JAXBContext", excp);
      }

   }
}
