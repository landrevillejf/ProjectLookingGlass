package org.jdesktop.wonderland.common.messages;

public interface MessageMonitor {
   void sending(Message var1, int var2);

   void received(Message var1, int var2);
}
