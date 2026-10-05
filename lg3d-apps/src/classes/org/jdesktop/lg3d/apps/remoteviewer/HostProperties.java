package org.jdesktop.lg3d.apps.remoteviewer;

import org.jdesktop.lg3d.apps.remoteviewer.utilities.InetAdrUtility;

import javax.swing.*;
import java.awt.*;
import java.util.Hashtable;

/**
 * HostProperties.java
 * @author Admin
 */
public class HostProperties {

    public static Hashtable getLocalProperties() {
        Toolkit tk = Toolkit.getDefaultToolkit();        
        Hashtable<String, Object> localProperties = new Hashtable<String, Object>();        
        localProperties.put("host-address", InetAdrUtility.getLocalAdr().toString());
        localProperties.put("java.version", System.getProperty("java.version"));
        localProperties.put("os.name", System.getProperty("os.name"));
        localProperties.put("os.arch", System.getProperty("os.arch"));
        localProperties.put("os.version", System.getProperty("os.version"));
        localProperties.put("user.name", System.getProperty("user.name"));
        localProperties.put("user.dir", System.getProperty("user.dir"));
        localProperties.put("screen.size", tk.getScreenSize());
        localProperties.put("screen.resolution", tk.getScreenResolution());

        return localProperties;
    }  
    
    public static void displayRemoteProperties(Hashtable prop) {
        Dimension size = (Dimension) prop.get("screen.size");
        
        JOptionPane.showMessageDialog(null,
            "Host: \t" + prop.get("host-address") + "\n\n" +        
            
            "Java version: \t" + prop.get("java.version") + "\n\n" +
            
            "OS: \t" + prop.get("os.name") + ", " +
            prop.get("os.arch") + ", " + prop.get("os.version") + "\n\n" +
            
            "User's name: \t" + prop.get("user.name") + "\n" +
            "User's current directory: \t" + prop.get("user.dir") + "\n\n" +
            
            "Screen resolution: \t" + 
            String.valueOf(size.width) + "x" + String.valueOf(size.height) + "\n" +               
            "Screen size: \t" + prop.get("screen.resolution").toString() + 
            " PPI (Pixels Per Inch)",
            "Remote host properties", JOptionPane.INFORMATION_MESSAGE);
    }    
}
