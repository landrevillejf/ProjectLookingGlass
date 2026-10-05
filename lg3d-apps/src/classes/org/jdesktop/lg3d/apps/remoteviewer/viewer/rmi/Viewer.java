package org.jdesktop.lg3d.apps.remoteviewer.viewer.rmi;

import lombok.extern.slf4j.Slf4j;

import org.jdesktop.lg3d.apps.remoteviewer.HostProperties;
import org.jdesktop.lg3d.apps.remoteviewer.Main;
import org.jdesktop.lg3d.apps.remoteviewer.server.main.Config;
import org.jdesktop.lg3d.apps.remoteviewer.server.rmi.ServerInterface;
import org.jdesktop.lg3d.apps.remoteviewer.utilities.FileUtility;
import org.jdesktop.lg3d.apps.remoteviewer.utilities.InetAdrUtility;
import org.jdesktop.lg3d.apps.remoteviewer.utilities.ZipUtility;
import org.jdesktop.lg3d.apps.remoteviewer.viewer.main.Recorder;

import javax.rmi.ssl.SslRMIClientSocketFactory;
import javax.swing.*;
import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;

@Slf4j


/**
 * Viewer.java
 * @author benbac
 */

public class Viewer extends Thread {
    
    private int index = -1;
    private Recorder recorder;
    
    private Registry registry; 
    private ServerInterface rmiServer;
       
    private String server = "127.0.0.1";
    private int port = 6666;
    private String username = "admin";
    private String password = "admin";
    private boolean ssl_enabled = false;
    
    private boolean connected = false;
    
    private ArrayList<Object> Objects;        
        
    public Viewer () {
        Config.loadConfiguration();
        server = Config.server_address;
        port = Config.server_port;  
        username = Config.username;
        password = Config.password;
        ssl_enabled = Config.ssl_enabled;
        
        if (ssl_enabled) {     
            FileUtility.checkFile(Main.KEY_STORE, "keystore");
            FileUtility.checkFile(Main.TRUST_STORE, "truststore");
            Main.setStoreProperties();
        }
        else
            Main.clearStoreProperties();
    }   
    
    public boolean isConnected() {
        return connected;
    }
    
    public void Start() { 
        connect();
        if (connected) {
            recorder = new Recorder(this);        
            recorder.viewerGUI.Start();
        }        
        else Stop();
    }
    
    public void Stop() {
        if (recorder != null) {
            recorder.Stop();
            recorder.interrupt();
        }
        disconnect();    
        interrupt();
    }
    
    public int connect() {  
        connected = false;
        
        try {       
            if (ssl_enabled)
                registry = LocateRegistry.getRegistry(server, port, 
                        new SslRMIClientSocketFactory());
            else
                registry = LocateRegistry.getRegistry(server, port);        
          
            rmiServer = (ServerInterface) registry.lookup("ServerImpl");                          
         
            index = rmiServer.startViewer(InetAdrUtility.getLocalAdr(),
                    username, password);
            if (index == -1) {
                JOptionPane.showMessageDialog(null, 
                        "Wrong username or password !!", "Error !!",
                    JOptionPane.ERROR_MESSAGE);   
                return -1;
            }                         
            
            displayStatus();
            Objects = new ArrayList<Object>();
            Objects.add(HostProperties.getLocalProperties());
            connected = true;
            return index;
       } catch (Exception e) {    
           JOptionPane.showMessageDialog(null, e.getMessage(), "Error !!",
                    JOptionPane.ERROR_MESSAGE);
           return -1;
       }     
    }
    
    public void disconnect() {
        connected = false;
        try {
            if (rmiServer != null) {
                    rmiServer.stopViewer(index);
                    UnicastRemoteObject.unexportObject(rmiServer, true);
            }
        }
        catch (Exception e) {
            e.getStackTrace(); 
        } 
      rmiServer = null;
      registry = null;
    }
    
    public void updateData(Object object) {
        byte[] data;
        try {
            if (recorder.viewerData.isDataCompressionEnabled())
                data = ZipUtility.compressObject(object,
                        recorder.viewerData.getCompressionLevel());
            else
                data = ZipUtility.objecttoByteArray(object);                  

            recorder.connectionInfos.incSentData(data.length);
            updateData(data);  
        }
        catch (IOException e) {
            e.getStackTrace();
        }
    }
    
    public void updateData(byte[] data) {
        try {rmiServer.updateData(data, index);} 
        catch (Exception re) {
            re.getStackTrace();
        }        
    }
    
    public void AddObjects(ArrayList<Object> objects) {
        Objects.addAll(objects);
    } 
    
    public void AddObject(Object object) {
        Objects.add(object);
    }   
   
    public void updateOptions () {     
        try {rmiServer.updateOptions(
                recorder.getViewerData(), index);} 
        catch (Exception re) {
            re.getStackTrace();
        }          
    }
    
    public void sendData() {
        ArrayList SendObjects;       
        synchronized(Objects){              
            if (recorder.viewerData.isClipboardTransferEnabled())
                Objects.add(recorder.clipbrdUtility.getClipboardContent());              
            
            SendObjects = Objects;  
            Objects = new ArrayList<Object>();
        }
        updateData(SendObjects);
    }     
    
    public void recieveData() {
        Object object = null;
        try {
            byte[] data = rmiServer.updateData(index);

            if (recorder.viewerData.isDataCompressionEnabled())        
                object = ZipUtility.decompressObject(data);
            else
                object = ZipUtility.byteArraytoObject(data);   
           
            recorder.connectionInfos.incReceivedData(data.length);
            recorder.updataData((ArrayList) object);    
        }
        catch (Exception e) {
            e.getStackTrace();
        }           
    }   
    
    public byte[] ReceiveFile(String filename) {
        try {            
            byte[] data = rmiServer.ReceiveFile(filename, index);
            recorder.connectionInfos.incReceivedData(data.length);
            return data;
        } catch(RemoteException re) {
            re.getStackTrace();
            return null;
        }
    }
    
    public void SendFile (byte[] buffer, String filename) {
        try {        
            rmiServer.SendFile(buffer, filename, index);
            recorder.connectionInfos.incSentData(buffer.length);
       } catch(RemoteException re) {
            re.getStackTrace();
       }        
    } 
    
    public void displayStatus() {
        boolean auth = (username.length() != 0) || (password.length() != 0);             
        log.info("Viewer connected to {}\n\tauthentication: {}\n\tencryption: {}",
            rmiServer,
            (auth ? "enabled" : "disabled"),
            (ssl_enabled ? "enabled" : "disabled"));        
    }
}
