package org.jdesktop.wonderland.common.messages;

import java.util.LinkedHashMap;
import java.util.Map;

public class MessageMonitorImpl implements MessageMonitor {
   private Map totalData = new LinkedHashMap(50, 0.75F, true);

   public void sending(Message msg, int size) {
      MonitorRecord rec = (MonitorRecord)this.totalData.get(msg.getClass().getName());
      if (rec == null) {
         rec = new MonitorRecord();
         this.totalData.put(msg.getClass().getName(), rec);
      }

      rec.sending(size);
   }

   public void received(Message msg, int size) {
      MonitorRecord rec = (MonitorRecord)this.totalData.get(msg.getClass().getName());
      if (rec == null) {
         rec = new MonitorRecord();
         this.totalData.put(msg.getClass().getName(), rec);
      }

      rec.receiving(size);
   }

   class MonitorRecord {
      int sendMsgCount = 0;
      int recvMsgCount = 0;
      int sendTotalSize = 0;
      int recvTotalSize = 0;

      public void sending(int size) {
         ++this.sendMsgCount;
         this.sendTotalSize += size;
      }

      public void receiving(int size) {
         ++this.recvMsgCount;
         this.recvTotalSize += size;
      }
   }
}
