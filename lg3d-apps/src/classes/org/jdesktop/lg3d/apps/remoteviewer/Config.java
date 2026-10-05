package org.jdesktop.lg3d.apps.remoteviewer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Config.java
 * @author benbac
 */

public class Config {
    
    public static boolean GUI_disabled = false;
    public static boolean Systray_disabled = false;
    
    public static void loadConfiguration() {
        Main.initConfigPaths();
        if (new File(Main.CONFIG_FILE).canRead())
            try {
                Properties properties = new Properties();
                properties.load(new FileInputStream(Main.CONFIG_FILE));
                GUI_disabled = Boolean.valueOf(properties.getProperty("GUI-disabled"));
                Systray_disabled = Boolean.valueOf(properties.getProperty("Systray-disabled"));         
            }
            catch (Exception e) {
                e.getStackTrace();
            }
       else
            storeConfiguration();  
    }
    
    public static void storeConfiguration () {
        try {
            new File(Main.CONFIG_FILE).createNewFile();
            Properties properties = new Properties();
            properties.put("GUI-disabled", String.valueOf(GUI_disabled));
            properties.put("Systray-disabled", String.valueOf(Systray_disabled));        
            properties.store(new FileOutputStream(Main.CONFIG_FILE),
                "jrdesktop configuration file"); 
        } catch (Exception e) {
            e.getStackTrace();
        }            
    }            
}
