package org.jdesktop.wonderland.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.LogManager;

public class LogControl {
   public LogControl(Class refClass, String loggingProperties) {
      LogManager logManager = LogManager.getLogManager();
      InputStream in = refClass.getResourceAsStream(loggingProperties);

      try {
         logManager.readConfiguration(in);
      } catch (IOException e) {
         e.printStackTrace();
      }

   }
}
