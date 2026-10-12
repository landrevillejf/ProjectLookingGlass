package org.jdesktop.wonderland.common.messages;

import java.util.LinkedList;
import java.util.List;
import org.jdesktop.wonderland.common.ExperimentalAPI;

@ExperimentalAPI
public class MessageList extends Message {
   private LinkedList messages = new LinkedList();

   public void addMessage(Message message) {
      this.messages.add(message);
   }

   public List getMessages() {
      return this.messages;
   }

   public int size() {
      return this.messages.size();
   }
}
