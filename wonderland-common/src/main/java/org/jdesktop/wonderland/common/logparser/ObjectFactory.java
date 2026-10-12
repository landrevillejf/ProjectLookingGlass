package org.jdesktop.wonderland.common.logparser;

import javax.xml.bind.annotation.XmlRegistry;

@XmlRegistry
public class ObjectFactory {
   public Record createRecord() {
      return new Record();
   }

   public Frame createFrame() {
      return new Frame();
   }

   public Param createParam() {
      return new Param();
   }

   public Log createLog() {
      return new Log();
   }

   public Exception createException() {
      return new Exception();
   }
}
