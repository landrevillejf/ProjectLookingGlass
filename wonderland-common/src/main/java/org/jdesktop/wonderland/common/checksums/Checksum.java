package org.jdesktop.wonderland.common.checksums;

import java.io.Reader;
import java.io.Writer;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlTransient;

public class Checksum {
   @XmlElement(
      name = "checksum-hex-encoded"
   )
   private String checksum = null;
   @XmlElement(
      name = "last-modified"
   )
   private long lastModified = 0L;
   @XmlElement(
      name = "resource-path"
   )
   private String pathName = null;
   private static JAXBContext jaxbContext = null;

   public Checksum() {
   }

   public Checksum(String checksum) {
      this.checksum = checksum;
   }

   @Override
   public boolean equals(Object obj) {
      if (this == obj) {
         return true;
      }
      if (!(obj instanceof Checksum)) {
         return false;
      }
      Checksum other = (Checksum) obj;
      return Objects.equals(this.checksum, other.checksum);
   }

   @Override
   public int hashCode() {
      return Objects.hashCode(this.checksum);
   }

   public void setChecksum(String checksum) {
      this.checksum = checksum;
   }

   @XmlTransient
   public String getChecksum() {
      return this.checksum;
   }

   public void setLastModified(long lastModified) {
      this.lastModified = lastModified;
   }

   @XmlTransient
   public long getLastModified() {
      return this.lastModified;
   }

   public void setPathName(String pathName) {
      this.pathName = pathName;
   }

   @XmlTransient
   public String getPathName() {
      return this.pathName;
   }

   public static Checksum decode(Reader r) throws JAXBException {
      Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
      return (Checksum)unmarshaller.unmarshal(r);
   }

   public void encode(Writer w) throws JAXBException {
      Marshaller marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty("jaxb.formatted.output", true);
      marshaller.marshal(this, w);
   }

   public static String toHexString(byte[] bytes) {
      StringBuffer ret = new StringBuffer();

      for(int i = 0; i < bytes.length; ++i) {
         ret.append(Integer.toHexString(256 + (bytes[i] & 255)).substring(1));
      }

      return ret.toString();
   }

   static {
      try {
         jaxbContext = JAXBContext.newInstance(new Class[]{Checksum.class});
      } catch (JAXBException excp) {
         Logger.getLogger(Checksum.class.getName()).log(Level.WARNING, "Unable to create JAXBContext", excp);
      }

   }
}
