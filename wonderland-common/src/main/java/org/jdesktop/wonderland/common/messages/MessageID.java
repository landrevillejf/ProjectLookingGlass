package org.jdesktop.wonderland.common.messages;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import org.jdesktop.wonderland.common.ExperimentalAPI;
import org.jdesktop.wonderland.common.InternalAPI;

@ExperimentalAPI
public class MessageID implements Externalizable {
   private static MessageIDGenerator idGen = new DefaultMessageIDGenerator();
   private long id;

   public MessageID() {
      this(Long.MIN_VALUE);
   }

   public MessageID(long id) {
      this.id = id;
   }

   public static MessageID generateMessageID() {
      return idGen.generateID();
   }

   public long getID() {
      return this.id;
   }

   public static void setMessageIDGenerator(MessageIDGenerator idGen) {
      MessageID.idGen = idGen;
   }

   public void writeExternal(ObjectOutput out) throws IOException {
      out.writeLong(this.id);
      out.flush();
   }

   public void readExternal(ObjectInput in) throws IOException {
      this.id = in.readLong();
   }

   public boolean equals(Object o) {
      if (!(o instanceof MessageID)) {
         return false;
      } else {
         MessageID m = (MessageID)o;
         if (this.id == Long.MIN_VALUE) {
            return m.id == Long.MIN_VALUE;
         } else {
            return this.id == m.id;
         }
      }
   }

   public int hashCode() {
      return (int)this.id;
   }

   public String toString() {
      return "{MessageID: " + this.id + "}";
   }

   @InternalAPI
   public static class DefaultMessageIDGenerator implements MessageIDGenerator {
      private static long id = Long.MIN_VALUE;

      public synchronized MessageID generateID() {
         return new MessageID(++id);
      }
   }

   @ExperimentalAPI
   public interface MessageIDGenerator {
      MessageID generateID();
   }
}
