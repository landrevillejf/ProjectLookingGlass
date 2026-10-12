package org.jdesktop.wonderland.common.checksums;

import java.io.Reader;
import java.io.Writer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
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
   name = "checksum-list"
)
public class ChecksumList {
   private static final Logger logger = Logger.getLogger(ChecksumList.class.getName());
   public static final String SHA1_CHECKSUM_ALGORITHM = "SHA-1";
   @XmlElements({@XmlElement(
   name = "checksum",
   type = Checksum.class
)})
   public LinkedList<Checksum> checksumList = new LinkedList<>();
   @XmlTransient
   public Map<String, Checksum> internalChecksums = new HashMap<>();
   private static JAXBContext jaxbContext = null;

   public synchronized void setChecksums(Map<String, Checksum> checksumMap) {
      this.internalChecksums.clear();
      this.internalChecksums.putAll(checksumMap);

      for(String assetPath : checksumMap.keySet()) {
         this.checksumList.add(checksumMap.get(assetPath));
      }

   }

   @XmlTransient
   public synchronized Map<String, Checksum> getChecksumMap() {
      return new HashMap<>(this.internalChecksums);
   }

   public synchronized Checksum getChecksum(String assetPath) {
      return (Checksum)this.internalChecksums.get(assetPath);
   }

   public synchronized void putChecksum(Checksum checksum) {
      this.internalChecksums.put(checksum.getChecksum(), checksum);
      this.checksumList.add(checksum);
   }

   public synchronized void putChecksums(Map<String, Checksum> checksumMap) {
      this.internalChecksums.putAll(checksumMap);

      for(String assetPath : checksumMap.keySet()) {
         this.checksumList.add(checksumMap.get(assetPath));
      }

   }

   public static ChecksumList decode(Reader r) throws JAXBException {
      Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
      ChecksumList rc = (ChecksumList)unmarshaller.unmarshal(r);

      for(Checksum checksum : rc.checksumList) {
         rc.internalChecksums.put(checksum.getPathName(), checksum);
      }

      return rc;
   }

   public void encode(Writer w) throws JAXBException {
      Marshaller marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty("jaxb.formatted.output", true);
      marshaller.marshal(this, w);
   }

   static {
      try {
         jaxbContext = JAXBContext.newInstance(new Class[]{ChecksumList.class});
      } catch (JAXBException excp) {
         Logger.getLogger(ChecksumList.class.getName()).log(Level.WARNING, "Unable to create JAXBContext", excp);
      }

   }
}
