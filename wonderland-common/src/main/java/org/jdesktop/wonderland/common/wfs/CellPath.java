package org.jdesktop.wonderland.common.wfs;

import java.io.File;
import java.util.regex.Pattern;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

@XmlRootElement(
   name = "wfs-cell-parent"
)
public class CellPath {
   @XmlElement(
      name = "parent-path"
   )
   private String parentPath = null;

   public CellPath() {
   }

   public CellPath(String parentPath) {
      this.parentPath = parentPath;
   }

   @XmlTransient
   public String getParentPath() {
      return this.parentPath;
   }

   public String[] getParentPaths() {
      return this.parentPath.split(Pattern.quote(File.separator));
   }

   public CellPath getChildPath(String childName) {
      return new CellPath(this.parentPath + File.separator + childName);
   }

   public String toString() {
      return this.parentPath;
   }
}
