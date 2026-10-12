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
   name = "wfs-recordings"
)
public class WFSRecordingList {
   @XmlElements({@XmlElement(
   name = "recording"
)})
   private String[] recordings = new String[0];
   private static JAXBContext jaxbContext = null;

   public WFSRecordingList() {
   }

   public WFSRecordingList(String[] recordings) {
      this.recordings = recordings;
   }

   @XmlTransient
   public String[] getRecordings() {
      return this.recordings;
   }

   public static WFSRecordingList decode(InputStream is) throws JAXBException {
      Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
      return (WFSRecordingList)unmarshaller.unmarshal(is);
   }

   public void encode(Writer w) throws JAXBException {
      Marshaller marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty("jaxb.formatted.output", true);
      marshaller.marshal(this, w);
   }

   static {
      try {
         jaxbContext = JAXBContext.newInstance(new Class[]{WFSRecordingList.class});
      } catch (JAXBException excp) {
         Logger.getLogger(WFSRecordingList.class.getName()).log(Level.WARNING, "Unable to create JAXBContext", excp);
      }

   }
}
