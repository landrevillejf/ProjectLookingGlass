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
import javax.xml.bind.annotation.XmlElements;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import org.jdesktop.wonderland.common.JarURI;
import org.jdesktop.wonderland.common.JarURIAdapter;

@XmlRootElement(
   name = "module-plugin-list"
)
public class ModulePluginList implements Serializable {
   @XmlElements({@XmlElement(
   name = "jar-uri"
)})
   private JarURI[] jarURIs = null;
   private static JAXBContext jaxbContext = null;

   @XmlTransient
   public JarURI[] getJarURIs() {
      return this.jarURIs;
   }

   public void setJarURIs(JarURI[] jarURIs) {
      this.jarURIs = jarURIs;
   }

   public String toString() {
      return this.jarURIs.toString();
   }

   public static ModulePluginList decode(Reader r, String server) throws JAXBException {
      Unmarshaller u = jaxbContext.createUnmarshaller();
      JarURIAdapter adapter = new JarURIAdapter(server);
      u.setAdapter(adapter);
      return (ModulePluginList)u.unmarshal(r);
   }

   public void encode(Writer w) throws JAXBException {
      Marshaller m = jaxbContext.createMarshaller();
      m.setProperty("jaxb.formatted.output", true);
      m.marshal(this, w);
   }

   static {
      try {
         jaxbContext = JAXBContext.newInstance(new Class[]{ModulePluginList.class, JarURI.class});
      } catch (JAXBException excp) {
         Logger.getLogger(ModulePluginList.class.getName()).log(Level.WARNING, "Unable to create JAXBContext", excp);
      }

   }
}
