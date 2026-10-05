package org.jdesktop.lg3d.apps.remoteviewer;

import lombok.extern.slf4j.Slf4j;

import org.jdesktop.lg3d.apps.remoteviewer.server.rmi.Server;
import org.jdesktop.lg3d.apps.remoteviewer.utilities.FileUtility;
import org.jdesktop.lg3d.apps.remoteviewer.viewer.rmi.Viewer;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static org.jdesktop.lg3d.apps.remoteviewer.server.main.Config.setConfiguration;

@Slf4j


/**
 * main.java
 * @author benbac
 */

public class Main {
    
    public static String CONFIG_FILE;
    public static String SERVER_CONFIG_FILE;
    public static String VIEWER_CONFIG_FILE;
    
    public static String KEY_STORE;   
    public static String TRUST_STORE;
        
    public static void main (String args[]) {

        CONFIG_FILE = FileUtility.getConfigDirectory() + "config";
        SERVER_CONFIG_FILE = FileUtility.getConfigDirectory() + "server.config";
        VIEWER_CONFIG_FILE = FileUtility.getConfigDirectory() + "viewer.config";    
    
        KEY_STORE = FileUtility.getConfigDirectory() + "keystore";   
        TRUST_STORE = FileUtility.getConfigDirectory() + "truststore";
    
        System.getProperties().remove("java.rmi.server.hostname");        
                          
        if (args.length > 0) {                    
            String arg;
            boolean serverSide = true;
            String server = "127.0.0.1";
            int port = 6666;
            String username = "";
            String password = "";
            boolean ssl = false;
            boolean multihome = false;
            
            arg = args[0];
            if (arg.equals("-help") || arg.equals("-?")) // display usage information
                displayHelp();     
            else if (arg.equals("-version")) // display version information
                log.info("\t{}", getVersionInfos());               
            else if (arg.equals("server")) // start server with default paramaters
                startServer(6666, "", "", false, false);
            else if (arg.equals("viewer")) // start viewer with default paramaters
                startViewer("127.0.0.1", 6666, "", "", false);            
            else if (arg.equals("display")) // display Remote viewer's main window
                RemoteViewer.show();
            else {
                for (int i=0; i<args.length; i++) {
                    arg = args[i];                  
                    
                    if (arg.startsWith("-a:")) {
                        server = arg.substring(3);   
                        serverSide = false;
                    }
                    else if (arg.startsWith("-p:")) {
                        try {
                            port = Integer.parseInt(arg.substring(3));
                        } catch (NumberFormatException e) {
                            log.error("Invalid port number, using default.");
                        }
                        if( port < 1 || port > 65535) {
                            port = 6666;
                            log.error("Invalid port number, using default.");
                        }
                    }
                    else if (arg.startsWith("-u:"))
                        username = arg.substring(3);
                    else if (arg.startsWith("-d:"))
                        password = arg.substring(3);
                    else if (arg.startsWith("-s"))
                        ssl = true;
                    else if (arg.startsWith("-m"))
                        multihome = true;
                }
                    
                if (serverSide)
                    startServer(port, username, password, ssl , multihome);
                else
                    startViewer(server, port, username, password, ssl);         
            }
        }
        else
            displayHelp();           
        
        Config.loadConfiguration();
        if (!Config.Systray_disabled)
            SysTray.Show();
        if (!Config.GUI_disabled)
            RemoteViewer.show();
    }

    public static void initConfigPaths() {
        if (CONFIG_FILE == null) {
            CONFIG_FILE = FileUtility.getConfigDirectory() + "config";
            SERVER_CONFIG_FILE = FileUtility.getConfigDirectory() + "server.config";
            VIEWER_CONFIG_FILE = FileUtility.getConfigDirectory() + "viewer.config";
            KEY_STORE = FileUtility.getConfigDirectory() + "keystore";
            TRUST_STORE = FileUtility.getConfigDirectory() + "truststore";
        }
    }
       
    public static void displayHelp() {
        log.info(                            
            "Remote Viewer - Java Remote Desktop.\n" + 
            "http://jrdesktop.sourceforge.net/\n\n" + 
            
            "Usage: java -jar remote-viewer.jar <command> [options]\n\n" + 
               
            "   display     display main window.\n" +            
            "   server      start server using default parameters.\n" +
            "   viewer      start viewer using default parameters.\n" +            
            "   (default parameters : local machine with port 6666).\n\n" +
                
            "Options:\n" + 
            "   -a:address      server's address.\n" +
            "   -p:port         server's port.\n" +
            "   -u:username     user's name.\n" +
            "   -d:password     user's password.\n" +
            "   -s              secured connection using SSL.\n" +
            "   -m              multihomed server.\n" +
            "   -version        display version information.\n" +
            "   -help or -?     display usage information.\n"
        );
    }
    
    public static void startServer(int port, 
            String username, String password, 
            boolean ssl_enabled, boolean multihomed_enabled) {
        
        setConfiguration(port, username, password,
                ssl_enabled, multihomed_enabled);
        
        Server.Start();
    }

    public static void startViewer(String server, int port,
                                   String username, String password, boolean ssl_enabled) {

        // Appelez une méthode différente pour le viewer, pas la même que pour le serveur
        setViewerConfiguration(server, port, username, password, ssl_enabled);

        new Viewer().Start();
    }

    // Ajoutez cette nouvelle méthode
    public static void setViewerConfiguration(String server, int port,
                                              String username, String password, boolean ssl_enabled) {

        // Vous devez créer un fichier de configuration séparé pour le viewer
        try {
            new File(VIEWER_CONFIG_FILE).createNewFile();
            Properties properties = new Properties();
            properties.put("server-address", server);
            properties.put("server-port", String.valueOf(port));
            properties.put("username", username);
            properties.put("password", password);
            properties.put("ssl-enabled", String.valueOf(ssl_enabled));

            properties.store(new FileOutputStream(VIEWER_CONFIG_FILE),
                    "remote viewer configuration file");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
   public static void setStoreProperties() {     
        System.setProperty("javax.net.ssl.trustStore", TRUST_STORE); 
        System.setProperty("javax.net.ssl.trustStorePassword", "trustword"); 
        System.setProperty("javax.net.ssl.keyStore", KEY_STORE); 
        System.setProperty("javax.net.ssl.keyStorePassword", "password");   
   }
    
   public static void clearStoreProperties() {
        System.getProperties().remove("javax.net.ssl.trustStore"); 
        System.getProperties().remove("javax.net.ssl.trustStorePassword");         
        System.getProperties().remove("javax.net.ssl.keyStore"); 
        System.getProperties().remove("javax.net.ssl.keyStorePassword");               
    }      
    
    public static String getVersionInfos() {
        try {
            String classContainer = Main.class.getProtectionDomain().
                getCodeSource().getLocation().toString();           
            URL manifestUrl = new URL("jar:" + classContainer + 
                    "!/META-INF/MANIFEST.MF");            
            Manifest manifest = new Manifest(manifestUrl.openStream());  
            Attributes attr = manifest.getMainAttributes();
            final String version = attr.getValue("Implementation-Version");
            final String built_date = attr.getValue("Built-Date");
            return "com/protonmail/landrevillejf/remoteviewer " + version + " \tBuilt date: " + built_date;
        } catch (Exception e){
            e.getStackTrace();
            return null;
        } 
    }
   
    public static void exit() {
        if (Server.isRunning())       
            Server.Stop();
        clearStoreProperties();
        // Never System.exit(0): the Remote Viewer is hosted inside the lg3d
        // desktop JVM (a 3D Frame3D or a 2D MDI internal frame), so exiting here
        // would tear down the whole desktop. This is the system-tray "Exit"
        // path; close the application window instead.
        RemoteViewer.close();
    }
}
