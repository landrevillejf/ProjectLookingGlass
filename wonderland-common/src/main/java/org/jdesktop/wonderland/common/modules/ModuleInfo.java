package org.jdesktop.wonderland.common.modules;

import java.io.OutputStream;
import java.io.Reader;
import java.io.Serializable;
import java.io.Writer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAccessOrder;
import javax.xml.bind.annotation.XmlAccessorOrder;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElements;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import org.jdesktop.wonderland.common.checksums.ChecksumList;

@XmlRootElement(
   name = "wonderland-module"
)
public class ModuleInfo implements Serializable {
   public static final int VERSION_UNSET = -1;
   @XmlElement(
      name = "name",
      required = true
   )
   private String name;
   @XmlElement(
      name = "version"
   )
   private Version version;
   @XmlElement(
      name = "description"
   )
   private String description;
   @XmlElements({@XmlElement(
   name = "attribute"
)})
   private Attribute[] attributes;
   @XmlTransient
   private Map attributeMap;
   @XmlElement
   private ChecksumList checksumList;
   @XmlElement
   private ModuleRepository repository;
   private static JAXBContext jaxbContext = null;

   public ModuleInfo() {
      this.name = null;
      this.version = new Version();
      this.description = null;
      this.attributes = new Attribute[0];
      this.attributeMap = new HashMap();
   }

   public ModuleInfo(String name, int major, int minor, int mini, String description) {
      this(name, major, minor, mini);
      this.description = description;
   }

   public ModuleInfo(String name, int major, int minor, int mini) {
      this.name = null;
      this.version = new Version();
      this.description = null;
      this.attributes = new Attribute[0];
      this.attributeMap = new HashMap();
      this.name = name;
      this.version.major = major;
      this.version.minor = minor;
      this.version.mini = mini;
   }

   @XmlTransient
   public String getName() {
      return this.name;
   }

   public void setName(String name) {
      this.name = name;
   }

   @XmlTransient
   public int getMajor() {
      return this.version.major;
   }

   public void setMajor(int major) {
      this.version.major = major;
   }

   @XmlTransient
   public int getMinor() {
      return this.version.minor;
   }

   public void setMinor(int minor) {
      this.version.minor = minor;
   }

   @XmlTransient
   public int getMini() {
      return this.version.mini;
   }

   public void setMini(int mini) {
      this.version.mini = mini;
   }

   @XmlTransient
   public String getDescription() {
      return this.description;
   }

   public void setDescription(String description) {
      this.description = description;
   }

   @XmlTransient
   public ChecksumList getChecksumList() {
      return this.checksumList;
   }

   public void setChecksumList(ChecksumList checksumList) {
      this.checksumList = checksumList;
   }

   @XmlTransient
   public ModuleRepository getRepository() {
      return this.repository;
   }

   public void setRepository(ModuleRepository repository) {
      this.repository = repository;
   }

   public Map getAttributes() {
      return new HashMap(this.attributeMap);
   }

   public void putAttribute(String key, String value) {
      this.attributeMap.put(key, value);
   }

   public void putAttibutes(Map attributes) {
      this.attributeMap.putAll(attributes);
   }

   public String getAttribute(String key) {
      return (String)this.attributeMap.get(key);
   }

   public void removeAttribute(String key) {
      this.attributeMap.remove(key);
   }

   public String toString() {
      return this.getName() + "(v" + Integer.toString(this.getMajor()) + "." + Integer.toString(this.getMinor()) + "." + Integer.toString(this.getMini()) + ")";
   }

   public static ModuleInfo decode(Reader r) throws JAXBException {
      Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
      ModuleInfo info = (ModuleInfo)unmarshaller.unmarshal(r);
      info.attributeMap.clear();

      for(Attribute attribute : info.attributes) {
         info.attributeMap.put(attribute.key, attribute.value);
      }

      return info;
   }

   public void encode(Writer w) throws JAXBException {
      List<Attribute> list = new LinkedList();

      for(Map.Entry entry : (java.util.Set<Map.Entry>) this.attributeMap.entrySet()) {
         list.add(new Attribute((String)entry.getKey(), (String)entry.getValue()));
      }

      this.attributes = (Attribute[])list.toArray(new Attribute[0]);
      Marshaller marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty("jaxb.formatted.output", true);
      marshaller.marshal(this, w);
   }

   public void encode(OutputStream os) throws JAXBException {
      List<Attribute> list = new LinkedList();

      for(Map.Entry entry : (java.util.Set<Map.Entry>) this.attributeMap.entrySet()) {
         list.add(new Attribute((String)entry.getKey(), (String)entry.getValue()));
      }

      this.attributes = (Attribute[])list.toArray(new Attribute[0]);
      Marshaller marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty("jaxb.formatted.output", true);
      marshaller.marshal(this, os);
   }

   public boolean equals(Object object) {
      if (!(object instanceof ModuleInfo)) {
         return false;
      } else {
         ModuleInfo info = (ModuleInfo)object;
         if (!this.getName().equals(info.getName())) {
            return false;
         } else {
            return this.version.equals(info.version);
         }
      }
   }

   public int hashCode() {
      int hash = 7;
      hash = 13 * hash + (this.name != null ? this.name.hashCode() : 0);
      hash = 13 * hash + (this.version != null ? this.version.hashCode() : 0);
      return hash;
   }

   static {
      try {
         jaxbContext = JAXBContext.newInstance(new Class[]{ModuleInfo.class});
      } catch (JAXBException excp) {
         Logger.getLogger(ModuleInfo.class.getName()).log(Level.WARNING, "Unable to create JAXBContext", excp);
      }

   }

   public static class Version {
      @XmlElement(
         name = "major"
      )
      public int major = -1;
      @XmlElement(
         name = "minor"
      )
      public int minor = -1;
      @XmlElement(
         name = "mini"
      )
      public int mini = -1;

      public boolean equals(Object obj) {
         if (obj == null) {
            return false;
         } else if (this.getClass() != obj.getClass()) {
            return false;
         } else {
            Version other = (Version)obj;
            if (this.major != other.major) {
               return false;
            } else if (this.minor != other.minor) {
               return false;
            } else {
               return this.mini == other.mini;
            }
         }
      }

      public int hashCode() {
         int hash = 7;
         hash = 71 * hash + this.major;
         hash = 71 * hash + this.minor;
         hash = 71 * hash + this.mini;
         return hash;
      }
   }

   @XmlAccessorOrder(XmlAccessOrder.ALPHABETICAL)
   @XmlRootElement(
      name = "attribute"
   )
   public static class Attribute {
      @XmlAttribute(
         name = "key"
      )
      public String key = null;
      @XmlAttribute(
         name = "value"
      )
      public String value = null;

      public Attribute() {
      }

      public Attribute(String key, String value) {
         this.key = key;
         this.value = value;
      }
   }
}
