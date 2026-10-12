package org.jdesktop.wonderland.common;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class FileUtils {
   public static String getFileExtension(String filename) {
      try {
         return filename.substring(filename.lastIndexOf(46) + 1);
      } catch (Exception var2) {
         return null;
      }
   }

   public static void copyFile(InputStream in, OutputStream out) throws IOException {
      byte[] buf = new byte[1048576];

      int size;
      while((size = in.read(buf)) != -1) {
         out.write(buf, 0, size);
      }

   }

   public static void deleteDirContents(File dir) {
      for(File content : dir.listFiles()) {
         if (content.isDirectory()) {
            deleteDirContents(content);
         }

         content.delete();
      }

   }

   public static File findDir(File start, String string) {
      if (start.getName().equals(string)) {
         return start;
      } else {
         if (start.isDirectory()) {
            File[] subDirs = start.listFiles();
            if (subDirs == null) {
               return null;
            }

            for(File f : subDirs) {
               File result = findDir(f, string);
               if (result != null && result.isDirectory()) {
                  return result;
               }
            }
         }

         return null;
      }
   }
}
