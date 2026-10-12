package org.jdesktop.wonderland.common.logparser;

import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(
   name = "",
   propOrder = {"date", "millis", "sequence", "logger", "level", "clazz", "method", "thread", "message", "key", "catalog", "param", "exception"}
)
@XmlRootElement(
   name = "record"
)
public class Record {
   @XmlElement(
      required = true
   )
   protected String date;
   @XmlElement(
      required = true
   )
   protected String millis;
   @XmlElement(
      required = true
   )
   protected String sequence;
   protected String logger;
   @XmlElement(
      required = true
   )
   protected String level;
   @XmlElement(
      name = "class"
   )
   protected String clazz;
   protected String method;
   protected String thread;
   @XmlElement(
      required = true
   )
   protected String message;
   protected String key;
   protected String catalog;
   protected List param;
   protected Exception exception;

   public String getDate() {
      return this.date;
   }

   public void setDate(String value) {
      this.date = value;
   }

   public String getMillis() {
      return this.millis;
   }

   public void setMillis(String value) {
      this.millis = value;
   }

   public String getSequence() {
      return this.sequence;
   }

   public void setSequence(String value) {
      this.sequence = value;
   }

   public String getLogger() {
      return this.logger;
   }

   public void setLogger(String value) {
      this.logger = value;
   }

   public String getLevel() {
      return this.level;
   }

   public void setLevel(String value) {
      this.level = value;
   }

   public String getClazz() {
      return this.clazz;
   }

   public void setClazz(String value) {
      this.clazz = value;
   }

   public String getMethod() {
      return this.method;
   }

   public void setMethod(String value) {
      this.method = value;
   }

   public String getThread() {
      return this.thread;
   }

   public void setThread(String value) {
      this.thread = value;
   }

   public String getMessage() {
      return this.message;
   }

   public void setMessage(String value) {
      this.message = value;
   }

   public String getKey() {
      return this.key;
   }

   public void setKey(String value) {
      this.key = value;
   }

   public String getCatalog() {
      return this.catalog;
   }

   public void setCatalog(String value) {
      this.catalog = value;
   }

   public List getParam() {
      if (this.param == null) {
         this.param = new ArrayList();
      }

      return this.param;
   }

   public Exception getException() {
      return this.exception;
   }

   public void setException(Exception value) {
      this.exception = value;
   }
}
