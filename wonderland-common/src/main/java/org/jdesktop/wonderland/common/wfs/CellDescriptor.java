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
import javax.xml.bind.annotation.XmlElementRef;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

@XmlRootElement(
   name = "wfs-cell-descriptor"
)
public class CellDescriptor {
   private WorldRoot worldRoot = null;
   private CellPath cellParent = null;
   private String cellID = null;
   private String cellName = null;
   private String setupInfo = null;
   private static JAXBContext jaxbContext = null;

   public CellDescriptor() {
   }

   public CellDescriptor(WorldRoot worldRoot, CellPath cellParent, String cellID, String cellName, String setupInfo) {
      this.worldRoot = worldRoot;
      this.cellParent = cellParent;
      this.cellID = cellID;
      this.cellName = cellName;
      this.setupInfo = setupInfo;
   }

   @XmlElement(
      name = "cell-id"
   )
   public String getCellID() {
      return this.cellID;
   }

   public void setCellID(String cellID) {
      this.cellID = cellID;
   }

   @XmlElement(
      name = "cell-name"
   )
   public String getCellName() {
      return this.cellName;
   }

   public void setCellName(String cellName) {
      this.cellName = cellName;
   }

   @XmlTransient
   public String getCellUniqueName() {
      return this.getCellName() + "-" + this.getCellID();
   }

   @XmlElementRef
   public CellPath getParentPath() {
      return this.cellParent;
   }

   public void setParentPath(CellPath cellParent) {
      this.cellParent = cellParent;
   }

   @XmlElementRef
   public WorldRoot getRootPath() {
      return this.worldRoot;
   }

   public void setRootPath(WorldRoot worldRoot) {
      this.worldRoot = worldRoot;
   }

   @XmlElement(
      name = "xml-setup-info"
   )
   public String getSetupInfo() {
      return this.setupInfo;
   }

   public void setSetupInfo(String setupInfo) {
      this.setupInfo = setupInfo;
   }

   public static CellDescriptor decode(Reader r) throws JAXBException {
      Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
      return (CellDescriptor)unmarshaller.unmarshal(r);
   }

   public void encode(Writer w) throws JAXBException {
      Marshaller marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty("jaxb.formatted.output", true);
      marshaller.marshal(this, w);
   }

   static {
      try {
         jaxbContext = JAXBContext.newInstance(new Class[]{CellDescriptor.class});
      } catch (JAXBException excp) {
         Logger.getLogger(CellDescriptor.class.getName()).log(Level.WARNING, "Unable to create JAXBContext", excp);
      }

   }
}
