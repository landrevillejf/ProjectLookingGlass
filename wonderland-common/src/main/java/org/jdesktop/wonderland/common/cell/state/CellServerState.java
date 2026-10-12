package org.jdesktop.wonderland.common.cell.state;

import java.io.Reader;
import java.io.Serializable;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementRef;
import javax.xml.bind.annotation.XmlElementRefs;
import javax.xml.bind.annotation.XmlElements;
import javax.xml.bind.annotation.XmlTransient;
import org.jdesktop.wonderland.common.utils.ScannedClassLoader;

public abstract class CellServerState implements CellState, Serializable {
   @XmlElement(
      name = "name"
   )
   private String name = null;
   @XmlElements({@XmlElement(
   name = "metadata"
)})
   private MetaDataHashMap metadata = new MetaDataHashMap();
   @XmlElementRefs({@XmlElementRef})
   private CellComponentServerState[] components = new CellComponentServerState[0];
   @XmlTransient
   private Map internalComponentMap = new HashMap();
   @XmlTransient
   private Map internalMetaData = new HashMap();
   @XmlTransient
   private BoundingVolumeHint boundingVolumeHint = new BoundingVolumeHint();

   public abstract String getServerClassName();

   public void addComponentServerState(CellComponentServerState serverState) {
      Class lookupClass = CellComponentUtils.getLookupClass(serverState.getClass());
      this.internalComponentMap.put(lookupClass, serverState);
   }

   public CellComponentServerState getComponentServerState(Class clazz) {
      Class lookupClass = CellComponentUtils.getLookupClass(clazz);
      return (CellComponentServerState)this.internalComponentMap.get(lookupClass);
   }

   public Map getComponentServerStates() {
      return new HashMap(this.internalComponentMap);
   }

   public void removeComponentServerState(Class clazz) {
      Class lookupClass = CellComponentUtils.getLookupClass(clazz);
      this.internalComponentMap.remove(lookupClass);
   }

   public void removeAllComponentServerStates() {
      this.internalComponentMap.clear();
   }

   @XmlTransient
   public Map getMetaData() {
      return this.internalMetaData;
   }

   public void setMetaData(Map metadata) {
      this.internalMetaData = metadata;
   }

   @XmlTransient
   public BoundingVolumeHint getBoundingVolumeHint() {
      return this.boundingVolumeHint;
   }

   public void setBoundingVolumeHint(BoundingVolumeHint boundingVolumeHint) {
      this.boundingVolumeHint = boundingVolumeHint;
   }

   @XmlTransient
   public String getName() {
      return this.name;
   }

   public void setName(String name) {
      this.name = name;
   }

   public static CellServerState decode(Reader r) throws JAXBException {
      return decode(r, (Unmarshaller)null);
   }

   public static CellServerState decode(Reader r, Unmarshaller unmarshaller) throws JAXBException {
      if (unmarshaller == null) {
         unmarshaller = CellServerStateFactory.getUnmarshaller((ScannedClassLoader)null);
      }

      CellServerState setup = (CellServerState)unmarshaller.unmarshal(r);
      if (setup.metadata != null) {
         ListIterator<HashMapEntry> iterator = setup.metadata.entries.listIterator();
         setup.internalMetaData = new HashMap();

         while(iterator.hasNext()) {
            HashMapEntry entry = (HashMapEntry)iterator.next();
            setup.internalMetaData.put(entry.key, entry.value);
         }
      } else {
         setup.internalMetaData = null;
      }

      if (setup.components != null) {
         setup.internalComponentMap = new HashMap();

         for(CellComponentServerState state : setup.components) {
            Class lookupClass = CellComponentUtils.getLookupClass(state.getClass());
            setup.internalComponentMap.put(lookupClass, state);
         }
      } else {
         setup.internalComponentMap = null;
      }

      return setup;
   }

   public void encode(Writer w) throws JAXBException {
      this.encode(w, (Marshaller)null);
   }

   public void encode(Writer w, Marshaller marshaller) throws JAXBException {
      if (marshaller == null) {
         marshaller = CellServerStateFactory.getMarshaller((ScannedClassLoader)null);
      }

      if (this.internalMetaData != null) {
         this.metadata = new MetaDataHashMap();

         for(Map.Entry e : (java.util.Set<Map.Entry>) this.internalMetaData.entrySet()) {
            HashMapEntry entry = new HashMapEntry();
            entry.key = (String)e.getKey();
            entry.value = (String)e.getValue();
            this.metadata.entries.add(entry);
         }
      } else {
         this.metadata = null;
      }

      if (this.internalComponentMap != null) {
         this.components = new CellComponentServerState[this.internalComponentMap.size()];
         int i = 0;

         for(Map.Entry e : (java.util.Set<Map.Entry>) this.internalComponentMap.entrySet()) {
            this.components[i] = (CellComponentServerState)e.getValue();
            ++i;
         }
      } else {
         this.components = null;
      }

      marshaller.marshal(this, w);
   }

   public String toString() {
      StringBuilder sb = new StringBuilder("[" + this.getClass().getSimpleName() + "] ");

      for(Map.Entry e : (java.util.Set<Map.Entry>) this.internalComponentMap.entrySet()) {
         sb.append(((CellComponentServerState)e.getValue()).toString());
      }

      return sb.toString();
   }

   private static class MetaDataHashMap implements Serializable {
      @XmlElements({@XmlElement(
   name = "entry"
)})
      public List entries = new ArrayList();

      public MetaDataHashMap() {
      }
   }

   private static class HashMapEntry implements Serializable {
      @XmlAttribute
      public String key;
      @XmlAttribute
      public String value;

      public HashMapEntry() {
      }
   }
}
