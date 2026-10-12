package org.jdesktop.wonderland.common.utils;

public class ClassScanningError extends Error {
   public ClassScanningError() {
   }

   public ClassScanningError(String msg) {
      super(msg);
   }

   public ClassScanningError(Throwable cause) {
      super(cause);
   }

   public ClassScanningError(String msg, Throwable cause) {
      super(msg, cause);
   }
}
