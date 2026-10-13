package org.jdesktop.wonderland.common.messages;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.comms.WonderlandObjectInputStream;
import org.jdesktop.wonderland.common.comms.WonderlandObjectOutputStream;

public class MessagePacker {
   private static MessageMonitor messageMonitor = null;
   private static HashMap packClassIds;
   private static short NOT_PACKED = Short.MIN_VALUE;
   private static Logger logger = Logger.getLogger(MessagePacker.class.getName());

   public static short getPackClassId(Object obj) {
      Short ret = (Short)packClassIds.get(obj.getClass());
      return ret == null ? NOT_PACKED : ret;
   }

   public static ByteBuffer pack(Message message, short clientID) throws PackerException {
      ObjectOutputStream out = null;

      try {
         ByteArrayOutputStream baos = new ByteArrayOutputStream();
         out = new WonderlandObjectOutputStream(baos);
         serializationPack(message, clientID, out);
         ByteBuffer buf = ByteBuffer.wrap(baos.toByteArray());
         if (messageMonitor != null) {
            messageMonitor.sending(message, buf.capacity());
         }

         if (logger.isLoggable(Level.FINEST)) {
            logger.finest("Packed " + message.getClass().getName() + "   size " + buf.capacity());
         }

         ByteBuffer var5 = buf;
         return var5;
      } catch (IOException ex) {
         Logger.getLogger(MessagePacker.class.getName()).log(Level.SEVERE, (String)null, ex);
      } finally {
         try {
            if (out != null) {
               out.close();
            }
         } catch (IOException ex) {
            Logger.getLogger(MessagePacker.class.getName()).log(Level.SEVERE, (String)null, ex);
         }

      }

      throw new PackerException();
   }

   private static void serializationPack(Message message, short clientID, ObjectOutputStream out) throws PackerException {
      try {
         out.writeObject(message.getMessageID());
         out.writeShort(clientID);
         out.writeObject(message);
      } catch (IOException ex) {
         Logger.getLogger(MessagePacker.class.getName()).log(Level.SEVERE, (String)null, ex);
         throw new PackerException();
      }
   }

   public static ReceivedMessage unpack(ByteBuffer buf) throws PackerException {
      return unpack(buf, (ClassLoader)null);
   }

   public static ReceivedMessage unpack(ByteBuffer buf, ClassLoader classLoader) throws PackerException {
      ObjectInputStream in = null;

      try {
         in = new WonderlandObjectInputStream(getInputStream(buf), classLoader);
         ReceivedMessage msg = serializationUnpack(buf, in);
         in.close();
         if (messageMonitor != null) {
            messageMonitor.received(msg.getMessage(), buf.capacity());
         }

         ReceivedMessage var4 = msg;
         return var4;
      } catch (IOException ex) {
         Logger.getLogger(MessagePacker.class.getName()).log(Level.SEVERE, (String)null, ex);
      } finally {
         try {
            if (in != null) {
               in.close();
            }
         } catch (IOException ex) {
            Logger.getLogger(MessagePacker.class.getName()).log(Level.SEVERE, (String)null, ex);
         }

      }

      throw new PackerException();
   }

   private static ReceivedMessage serializationUnpack(ByteBuffer buf, ObjectInputStream in) {
      try {
         MessageID messageID = (MessageID)in.readObject();
         short clientID = in.readShort();
         Message message = (Message)in.readObject();
         message.setMessageID(messageID);
         return new ReceivedMessage(message, clientID);
      } catch (IOException ex) {
         Logger.getLogger(MessagePacker.class.getName()).log(Level.SEVERE, (String)null, ex);
      } catch (ClassNotFoundException ex) {
         Logger.getLogger(MessagePacker.class.getName()).log(Level.SEVERE, (String)null, ex);
      }

      return null;
   }

   protected static InputStream getInputStream(ByteBuffer data) {
      ByteArrayInputStream bais;
      if (data.hasArray()) {
         bais = new ByteArrayInputStream(data.array(), data.arrayOffset(), data.remaining());
      } else {
         byte[] arr = new byte[data.remaining()];
         data.get(arr);
         bais = new ByteArrayInputStream(arr);
      }

      return bais;
   }

   public static void setMessageMonitor(MessageMonitor monitor) {
      messageMonitor = monitor;
   }

   static {
      short id = -32767;
      packClassIds = new HashMap();
      packClassIds.put(CellID.class, id++);
   }

   public static class ReceivedMessage {
      private final Message message;
      private final short clientID;

      private ReceivedMessage(Message message, short clientID) {
         this.message = message;
         this.clientID = clientID;
      }

      public Message getMessage() {
         return this.message;
      }

      public short getClientID() {
         return this.clientID;
      }
   }

   public static class PackerException extends Exception {
      public MessageID getMessageID() {
         return null;
      }

      public short getClientID() {
         return Short.MIN_VALUE;
      }
   }
}
