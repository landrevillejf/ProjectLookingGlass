package org.jdesktop.wonderland.common.modules.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.xml.bind.JAXBException;
import org.jdesktop.wonderland.common.FileUtils;
import org.jdesktop.wonderland.common.modules.ModuleInfo;
import org.jdesktop.wonderland.common.modules.ModuleRepository;
import org.jdesktop.wonderland.common.modules.ModuleRequires;

public class ModuleJarWriter {
   private ModuleInfo moduleInfo = null;
   private ModuleRequires moduleRequires = null;
   private ModuleRepository moduleRepository = null;
   private Map moduleArt = null;
   private Set directories = null;

   public ModuleInfo getModuleInfo() {
      return this.moduleInfo;
   }

   public void setModuleInfo(ModuleInfo moduleInfo) {
      this.moduleInfo = moduleInfo;
   }

   public ModuleRepository getModuleRepository() {
      return this.moduleRepository;
   }

   public void setModuleRepository(ModuleRepository moduleRepository) {
      this.moduleRepository = moduleRepository;
   }

   public ModuleRequires getModuleRequires() {
      return this.moduleRequires;
   }

   public void setModuleRequires(ModuleRequires moduleRequires) {
      this.moduleRequires = moduleRequires;
   }

   public void addArtFile(String path, File file) {
      if (this.moduleArt == null) {
         this.moduleArt = new HashMap();
      }

      this.moduleArt.put(this.fixPath(path), file);
   }

   public Map getArtFiles() {
      return this.moduleArt;
   }

   public void addDirectory(File dir) {
      if (this.directories == null) {
         this.directories = new HashSet();
      }

      this.directories.add(dir);
   }

   public void removeDirectory(File dir) {
      this.directories.remove(dir);
   }

   public void removeArtFile(String path) {
      if (this.moduleArt != null) {
         this.moduleArt.remove(this.fixPath(path));
      }

   }

   public void writeToJar(File file) throws IOException, JAXBException {
      Set<String> writtenPaths = new HashSet();
      JarOutputStream jos = new JarOutputStream(new FileOutputStream(file));
      this.writeManifest(jos);
      if (this.moduleInfo != null) {
         JarEntry entry = new JarEntry("module.xml");
         jos.putNextEntry(entry);
         this.moduleInfo.encode((OutputStream)jos);
      }

      if (this.moduleRequires != null) {
         JarEntry entry = new JarEntry("requires.xml");
         jos.putNextEntry(entry);
         this.moduleRequires.encode(jos);
      }

      if (this.moduleRepository != null) {
         JarEntry entry = new JarEntry("repository.xml");
         jos.putNextEntry(entry);
         this.moduleRepository.encode((OutputStream)jos);
      }

      if (this.moduleArt != null) {
         for(Map.Entry mapEntry : (java.util.Set<Map.Entry>) this.moduleArt.entrySet()) {
            String path = "art/" + (String)mapEntry.getKey();
            this.writeDirectory(jos, path, writtenPaths);
            JarEntry entry = new JarEntry(this.fixPath(path));
            jos.putNextEntry(entry);
            FileUtils.copyFile(new FileInputStream((File)mapEntry.getValue()), jos);
         }
      }

      if (this.directories != null) {
         for(File dir : (java.util.Collection<File>) this.directories) {
            this.writeDirectoryTree(jos, dir, dir.getParent().toString().length() + 1, writtenPaths);
         }
      }

      jos.close();
   }

   private void writeDirectoryTree(JarOutputStream jos, File dir, int parentTrimStart, Set written) throws IOException {
      String dirName = dir.getAbsolutePath().substring(parentTrimStart, dir.getAbsolutePath().length());
      JarEntry entry = new JarEntry(this.fixPath(dirName + "/"));
      jos.putNextEntry(entry);
      File[] files = dir.listFiles();
      if (files != null) {
         for(File f : files) {
            if (f.isDirectory()) {
               this.writeDirectoryTree(jos, f, parentTrimStart, written);
            } else {
               String path = f.getAbsolutePath().substring(parentTrimStart, f.getAbsolutePath().length());
               JarEntry fileEntry = new JarEntry(this.fixPath(path));
               jos.putNextEntry(fileEntry);
               FileUtils.copyFile(new FileInputStream(f), jos);
            }
         }

      }
   }

   private void writeDirectory(JarOutputStream jos, String path, Set written) throws IOException {
      String[] tokens = this.fixPath(path).split("/");
      if (tokens != null) {
         String directory = "";

         for(int i = 0; i < tokens.length - 1; ++i) {
            directory = directory + tokens[i] + "/";
            if (!written.contains(directory)) {
               JarEntry entry = new JarEntry(directory);
               jos.putNextEntry(entry);
               written.add(directory);
            }
         }

      }
   }

   private void writeManifest(JarOutputStream jos) throws IOException {
      JarEntry entry1 = new JarEntry("META-INF/");
      jos.putNextEntry(entry1);
      Manifest mf = new Manifest();
      JarEntry entry2 = new JarEntry("META-INF/MANIFEST.MF");
      jos.putNextEntry(entry2);
      mf.write(jos);
   }

   public static void main(String[] args) throws IOException, JAXBException {
      ModuleJarWriter mjw = new ModuleJarWriter();
      ModuleInfo info = new ModuleInfo("Fubar", 1, 0, 0, "Fubar module");
      mjw.setModuleInfo(info);
      mjw.addArtFile("models/castle.tiff", new File("/Users/jordanslott/Desktop/yuval.tiff"));
      mjw.addArtFile("models/castle/mystuff.tiff", new File("/Users/jordanslott/Desktop/yuval.tiff"));
      mjw.writeToJar(new File("mymodule.jar"));
   }

   private String fixPath(String path) {
      if (File.separatorChar == '\\') {
         path = path.replace('\\', '/');
      }

      return path;
   }
}
