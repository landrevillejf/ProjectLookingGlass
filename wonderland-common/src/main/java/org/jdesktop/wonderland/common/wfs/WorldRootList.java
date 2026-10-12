package org.jdesktop.wonderland.common.wfs;

import java.io.InputStream;
import java.io.Writer;
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
   name = "wfs-roots"
)
public class WorldRootList {
   @XmlElements({@XmlElement(
   name = "root"
)})
   private String[] roots = new String[0];
   private static JAXBContext jaxbContext = null;

   public WorldRootList() {
   }

   public WorldRootList(String[] roots) {
      this.roots = roots;
   }

   @XmlTransient
   public String[] getRoots() {
      return this.roots;
   }

   public static WorldRootList decode(InputStream is) throws JAXBException {
      Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
      return (WorldRootList)unmarshaller.unmarshal(is);
   }

   public void encode(Writer w) throws JAXBException {
      Marshaller marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty("jaxb.formatted.output", true);
      marshaller.marshal(this, w);
   }

   static {
      try {
         jaxbContext = JAXBContext.newInstance(new Class[]{WorldRootList.class});
      } catch (JAXBException excp) {
         Logger.getLogger(WorldRootList.class.getName()).log(Level.WARNING, "Unable to create JAXBContext", excp);
      }

   }
}
