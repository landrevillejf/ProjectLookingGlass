package org.jdesktop.lg3d.apps.remoteviewer.utilities;


import com.protonmail.landrevillejf.IconManager;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.datatransfer.*;
import java.io.File;
import java.util.List;

/**
 * ClipbrdUtility.java
 * @author benbac
 */

public class ClipbrdUtility {
    
    private Image getImageFromIcon(Icon icon) {
        if (icon instanceof ImageIcon) {
            return ((ImageIcon) icon).getImage();
        }
        BufferedImage image = new BufferedImage(
                icon.getIconWidth(),
                icon.getIconHeight(),
                BufferedImage.TYPE_INT_ARGB
        );
        Graphics2D g2d = image.createGraphics();
        icon.paintIcon(null, g2d, 0, 0);
        g2d.dispose();
        return image;
    }
    
    private Clipboard clipboard;
    
    private FlavorListener flavorlistener;
    private Object object;
    
    private String txt = "com/protonmail/landrevillejf/remoteviewer";
    private ImageIcon img = new ImageIcon(getImageFromIcon(IconManager.loadIcon(IconManager.IconCategory.GENERAL,"About",16,16)));

    public ClipbrdUtility() {
        initClipboard();
        clipboard.addFlavorListener(flavorlistener);        
    }
    
    public void initClipboard() {
        clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
    
        flavorlistener = new FlavorListener() {
            public void flavorsChanged(FlavorEvent event) {
                try {                
                    Transferable content = clipboard.getContents(this);
                    if (content == null) return;
                        
                    if(content.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        String newtxt = (String) clipboard.getData(DataFlavor.stringFlavor);
                        if (!txt.equals(newtxt)) {
                            txt = newtxt;
                            object = txt;
                        }
                    } 
                    else if (content.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                        Image image = (Image) clipboard.getData(DataFlavor.imageFlavor);                        
                        if (!img.getImage().equals(image)) {
                            img = new ImageIcon(image);
                            object = img;
                        }
                    }                             
                }    
                catch (Exception ex) {
                    ex.printStackTrace();
                }          
            }
        };
    }

    public Object getClipboardContent() {
        return object;
    }
    
    public File[] getFilesFromClipboard() {
        File[] files = new File[]{};
        try {
            Transferable transferable = clipboard.getContents(this);
            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                List list = (List) clipboard.getData(DataFlavor.javaFileListFlavor);  
                files = (File[]) list.toArray().clone();
            }
        } catch (Exception e) {
            e.getStackTrace();
        }
        return files;
    }
    
   public void addFlavorListener() {
        clipboard.addFlavorListener(flavorlistener);
   }
   
   public void removeFlavorListener() {
       clipboard.removeFlavorListener(flavorlistener);
   }
   
   public void setTextToClipboard(String string) { 
       if (txt.equals(string)) return;
       txt = string;
       clipboard.setContents(new StringSelection(txt), null);     
    }
    
    public void setImageToClipboard(ImageIcon image) {
        if (img.getImage().equals(image.getImage())) return;
        img = image;
        clipboard.setContents(new ImageSelection(img.getImage()), null);             
    }   
    
    /*
    * Helper class to put an Image on a clipboard as DataFlavor.imageFlavor.
    */
    public static class ImageSelection implements Transferable {

        private final Image img;

        public ImageSelection(Image img) {
            this.img = img;
        }
        static DataFlavor[] flavors = new DataFlavor[]{DataFlavor.imageFlavor};

        public DataFlavor[] getTransferDataFlavors() {
            return (DataFlavor[]) flavors.clone();         
        }

        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return flavor.equals(DataFlavor.imageFlavor);
        }

        public Object getTransferData(DataFlavor flavor)
                throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor))
                throw new UnsupportedFlavorException(flavor);
            return img;
        }
    } 
}
