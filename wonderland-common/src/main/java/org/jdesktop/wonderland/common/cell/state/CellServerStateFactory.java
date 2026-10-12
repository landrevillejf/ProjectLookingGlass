package org.jdesktop.wonderland.common.cell.state;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import org.jdesktop.wonderland.common.ModuleURI;
import org.jdesktop.wonderland.common.cell.state.annotation.ServerState;
import org.jdesktop.wonderland.common.cell.state.spi.CellServerStateSPI;
import org.jdesktop.wonderland.common.utils.ScannedClassLoader;

public class CellServerStateFactory {
   private static final List coreClasses = new ArrayList();
   private static JAXBContext systemContext = null;
   private static final Logger logger = Logger.getLogger(CellServerStateFactory.class.getName());

   public static Marshaller getMarshaller(ScannedClassLoader classLoader) {
      try {
         JAXBContext context = getContext(classLoader);
         Marshaller m = context.createMarshaller();
         m.setProperty("jaxb.formatted.output", true);
         return m;
      } catch (JAXBException ex) {
         logger.log(Level.SEVERE, (String)null, ex);
         return null;
      }
   }

   public static Unmarshaller getUnmarshaller(ScannedClassLoader classLoader) {
      try {
         JAXBContext context = getContext(classLoader);
         return context.createUnmarshaller();
      } catch (JAXBException ex) {
         logger.log(Level.SEVERE, (String)null, ex);
         return null;
      }
   }

   private static JAXBContext getContext(ScannedClassLoader classLoader) throws JAXBException {
      if (classLoader == null) {
         return systemContext;
      } else {
         Class[] clazz = getClasses(classLoader);
         return JAXBContext.newInstance(clazz);
      }
   }

   private static Class[] getClasses(ScannedClassLoader classLoader) {
      Set<Class> setupClasses = new LinkedHashSet(coreClasses);
      Iterator<CellState> it = classLoader.getInstances(ServerState.class, CellState.class);

      while(it.hasNext()) {
         setupClasses.add(((CellState)it.next()).getClass());
      }

      Iterator<CellServerStateSPI> it2 = classLoader.getAll(ServerState.class, CellServerStateSPI.class);

      while(it2.hasNext()) {
         setupClasses.add(((CellServerStateSPI)it2.next()).getClass());
      }

      logger.fine("Setup classes: " + setupClasses.toString());
      return (Class[])setupClasses.toArray(new Class[0]);
   }

   static {
      ScannedClassLoader scl = ScannedClassLoader.getSystemScannedClassLoader();
      coreClasses.addAll(Arrays.asList(getClasses(scl)));
      coreClasses.add(AvatarCellServerState.class);
      coreClasses.add(InteractionComponentServerState.class);
      coreClasses.add(ModelCellComponentServerState.class);
      coreClasses.add(ModelCellServerState.class);
      coreClasses.add(PositionComponentServerState.class);
      coreClasses.add(ModuleURI.class);

      try {
         systemContext = JAXBContext.newInstance((Class[])coreClasses.toArray(new Class[0]));
      } catch (JAXBException excp) {
         logger.log(Level.SEVERE, "[CELL] SETUP FACTORY Failed to create JAXBContext", excp);
      }

      logger.fine("Core classes: " + coreClasses.toString());
   }
}
