package org.jdesktop.wonderland.common.modules;

import java.io.OutputStream;
import java.io.Reader;
import java.io.Serializable;
import java.io.Writer;
import java.util.LinkedList;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElements;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlValue;

@XmlRootElement(
   name = "module-repository"
)
public class ModuleRepository implements Serializable {
   public static final String WL_SERVER = "%WLSERVER%";
   @XmlElements({@XmlElement(
   name = "resource"
)})
   private String[] resources = null;
   @XmlElement(
      name = "master"
   )
   private Repository master = null;
   @XmlElements({@XmlElement(
   name = "mirror"
)})
   private Repository[] mirrors = null;
   private LinkedList repositoryList = null;
   private static JAXBContext jaxbContext = null;

   public ModuleRepository() {
   }

   public ModuleRepository(ModuleRepository repository) {
      this.master = repository.getMaster() != null ? new Repository(repository.getMaster()) : null;
      this.mirrors = repository.getMirrors() != null ? new Repository[repository.getMirrors().length] : null;
      if (this.mirrors != null) {
         for(int i = 0; i < this.mirrors.length; ++i) {
            this.mirrors[i] = this.mirrors[i];
         }
      }

      this.resources = repository.getResources() != null ? new String[repository.getResources().length] : null;
      if (this.resources != null) {
         for(int i = 0; i < this.resources.length; ++i) {
            this.resources[i] = this.resources[i];
         }
      }

      this.updateRepositoryList();
   }

   @XmlTransient
   public String[] getResources() {
      return this.resources;
   }

   public void setResources(String[] resources) {
      this.resources = resources;
   }

   @XmlTransient
   public Repository getMaster() {
      return this.master;
   }

   public void setMaster(Repository master) {
      this.master = master;
      this.updateRepositoryList();
   }

   @XmlTransient
   public Repository[] getMirrors() {
      return this.mirrors;
   }

   public void setMirrors(Repository[] mirrors) {
      this.mirrors = mirrors;
      this.updateRepositoryList();
   }

   public Repository[] getAllRepositories() {
      return (Repository[])this.repositoryList.toArray(new Repository[0]);
   }

   public void updateRepositoryList() {
      this.repositoryList = new LinkedList();
      if (this.getMaster() != null) {
         this.repositoryList.addFirst(new Repository(this.master));
      }

      if (this.getMirrors() != null) {
         for(Repository mirror : this.getMirrors()) {
            this.repositoryList.addLast(new Repository(mirror));
         }
      }

   }

   public String toString() {
      StringBuilder str = new StringBuilder("Module Repository:\n");
      str.append("Master:\n  " + this.getMaster() + "\n");
      str.append("Mirrors:\n");
      if (this.mirrors != null) {
         for(Repository mirror : this.mirrors) {
            str.append("  " + mirror.url + "\n");
         }
      }

      if (this.resources != null) {
         str.append("Resources:\n");

         for(String resource : this.resources) {
            str.append("  " + resource + "\n");
         }
      }

      return str.toString();
   }

   public static ModuleRepository decode(Reader r) throws JAXBException {
      Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
      ModuleRepository list = (ModuleRepository)unmarshaller.unmarshal(r);
      list.updateRepositoryList();
      return list;
   }

   public void encode(Writer w) throws JAXBException {
      Marshaller marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty("jaxb.formatted.output", true);
      marshaller.marshal(this, w);
   }

   public void encode(OutputStream os) throws JAXBException {
      Marshaller marshaller = jaxbContext.createMarshaller();
      marshaller.setProperty("jaxb.formatted.output", true);
      marshaller.marshal(this, os);
   }

   private String stripTrailingSlash(String str) {
      return str.endsWith("/") ? str.substring(0, str.length() - 1) : str;
   }

   static {
      try {
         jaxbContext = JAXBContext.newInstance(new Class[]{ModuleRepository.class});
      } catch (JAXBException excp) {
         Logger.getLogger(ModuleRepository.class.getName()).log(Level.WARNING, "Unable to create JAXBContext", excp);
      }

   }

   public static class Repository {
      @XmlValue
      public String url = null;
      @XmlAttribute(
         name = "isServer"
      )
      public boolean isServer = false;

      public Repository() {
      }

      public Repository(Repository repository) {
         this.url = repository.url;
         this.isServer = repository.isServer;
      }
   }
}
