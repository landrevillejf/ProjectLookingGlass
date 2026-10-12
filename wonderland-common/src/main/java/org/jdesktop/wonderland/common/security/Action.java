package org.jdesktop.wonderland.common.security;

import java.io.Serializable;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class Action implements Serializable {
   private static final Logger logger = Logger.getLogger(Action.class.getName());
   private String name;
   private Class parentClass;
   private transient Action parent;
   private String displayName;
   private String toolTip;

   protected Action(String name) {
      this(name, (Class)null);
   }

   protected Action(String name, Class parentClass) {
      this(name, parentClass, (String)null, (String)null);
   }

   protected Action(String name, Class parentClass, String displayName, String toolTip) {
      this.name = name;
      this.parentClass = parentClass;
      this.displayName = displayName;
      this.toolTip = toolTip;
   }

   public String getName() {
      return this.name;
   }

   public synchronized Action getParent() {
      if (this.parent != null) {
         return this.parent;
      } else {
         if (this.getParentClass() != null) {
            this.parent = getInstance(this.getParentClass());
         }

         return this.parent;
      }
   }

   public Class getParentClass() {
      return this.parentClass;
   }

   public String getDisplayName() {
      return this.displayName;
   }

   public String getToolTip() {
      return this.toolTip;
   }

   public boolean equals(Object obj) {
      if (obj == null) {
         return false;
      } else if (this.getClass() != obj.getClass()) {
         return false;
      } else {
         Action other = (Action)obj;
         if (this.name == null) {
            if (other.name != null) {
               return false;
            }
         } else if (!this.name.equals(other.name)) {
            return false;
         }

         return true;
      }
   }

   public int hashCode() {
      int hash = 7;
      hash = 47 * hash + (this.name != null ? this.name.hashCode() : 0);
      return hash;
   }

   public static Action getInstance(Class actionClazz) {
      try {
         return (Action)actionClazz.newInstance();
      } catch (InstantiationException ex) {
         logger.log(Level.SEVERE, "Error creating action", ex);
      } catch (IllegalAccessException ex) {
         logger.log(Level.SEVERE, "Error creating action", ex);
      }

      return null;
   }
}
