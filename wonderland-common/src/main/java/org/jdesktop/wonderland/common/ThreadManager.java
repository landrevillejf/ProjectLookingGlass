package org.jdesktop.wonderland.common;

public class ThreadManager {
   private static final ThreadGroup threadGroup = new ThreadGroup("Wonderland");

   public static ThreadGroup getThreadGroup() {
      return threadGroup;
   }
}
