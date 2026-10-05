package org.jdesktop.lg3d.apps.remoteviewer.utilities;


import org.jdesktop.lg3d.apps.remoteviewer.Main;

import java.io.*;
import java.util.ArrayList;

/**
 * FileUtility.java
 * @author benbac
 */

public class FileUtility {

    public static void checkFile(String pathname, String filename) {
        File file = new File(pathname);
        
        if (!file.canRead()) 
            try {    
                file.createNewFile();
                InputStream is =  Main.class.getResourceAsStream(filename);
                BufferedInputStream bis = new BufferedInputStream(is);            
                FileOutputStream fos = new FileOutputStream(file);
            
                int ch;
                while ((ch = bis.read()) != -1) fos.write(ch);
                
                is.close();
                bis.close();
                fos.close();
            } catch(Exception e) {
                e.getStackTrace();
                return;
            }    
    }    
    
    public static ArrayList<File> getFiles(File[] files){
        ArrayList<File> _files = new ArrayList<File>();    
        for (int i=0; i<files.length; i++)
            if (files[i].isDirectory())
                _files.addAll(getFiles(new File(files[i].toString()).listFiles()));
            else 
                _files.add(files[i]);
        return _files;
    } 
    
    public static File[] getAllFiles(File[] files) {
        ArrayList<File> fs = getFiles(files);
        return (File[]) fs.toArray(new File[fs.size()]); 
    }
    
    public static String getCurrentDirectory () {
        String currentDirectory = null;
        try {
            currentDirectory = new File(".").getCanonicalPath() + File.separatorChar;            
        } catch (IOException e) {
            e.getStackTrace();
        }
        return currentDirectory;
    }     

    /**
     * Directory for the app's persistent configuration and keystore files
     * ({@code config}, {@code server.config}, {@code viewer.config},
     * {@code keystore}, {@code truststore}).
     *
     * <p>Deliberately <em>not</em> {@link #getCurrentDirectory()}: inside the
     * lg3d desktop the process working directory is the {@code lg3d-core}
     * source tree, so writing config there litters the repository with runtime
     * artifacts - and {@code viewer.config} / {@code server.config} hold a
     * plaintext password, which would then sit in the checkout (and risk being
     * committed). Store them per-user under {@code ~/.lg3d/remoteviewer/}
     * instead, creating the directory on demand.
     */
    public static String getConfigDirectory () {
        File dir = new File(System.getProperty("user.home"),
                ".lg3d" + File.separator + "remoteviewer");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            // Fall back to the working directory only if the home dir is
            // unusable, so the app still persists something rather than
            // throwing on every store/load.
            return getCurrentDirectory();
        }
        return dir.getAbsolutePath() + File.separatorChar;
    }
}
