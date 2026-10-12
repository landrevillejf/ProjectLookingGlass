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
   name = "wfs-children"
)
public class CellList {
   @XmlElements({@XmlElement(
   name = "child"
)})
   private Cell[] children = null;
   @XmlTransient
   private String relativePath = null;
   private static JAXBContext jaxbContext = null;

   public CellList() {
   }

   public CellList(String relativePath, Cell[] children) {
      this.relativePath = relativePath;
      this.children = children;
   }

   @XmlTransient
   public Cell[] getChildren() {
      return this.children;
   }

   @XmlTransient
   public String getRelativePath() {
      return this.relativePath;
   }

   public static CellList decode(String relativePath, InputStream is) throws JAXBException {
      Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
      CellList children = (CellList)unmarshaller.unmarshal(is);
      children.relativePath = relativePath;
      return children;
   }

   public void encode(Writer w) throws JAXBException {
      Marshaller marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty("jaxb.formatted.output", true);
      marshaller.marshal(this, w);
   }

   static {
      try {
         jaxbContext = JAXBContext.newInstance(new Class[]{CellList.class});
      } catch (JAXBException excp) {
         Logger.getLogger(CellList.class.getName()).log(Level.WARNING, "Unable to create JAXBContext", excp);
      }

   }

   public static class Cell {
      @XmlElement(
         name = "name"
      )
      public String name = null;
      @XmlElement(
         name = "last_modified"
      )
      public long lastModified = -1L;

      public Cell() {
      }

      public Cell(String name, long lastModified) {
         this.name = name;
         this.lastModified = lastModified;
      }
   }
}
