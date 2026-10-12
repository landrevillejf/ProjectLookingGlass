/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell).
 * Applies server-pushed component ADD/REMOVE messages onto a live cell, through
 * the same reflection-injection path Cell.setClientState uses (the unrecoverable
 * module classloader is replaced by Cell.class.getClassLoader(); see Cell's
 * header for why that is the faithful equivalent under M4's plain-classpath
 * loader). REMOVE originally NPE'd on the session call chain BEFORE the try
 * block when no channel existed; the classloader seam removes that hazard.
 */
package org.jdesktop.wonderland.client.cell;

import java.lang.reflect.Constructor;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.wonderland.common.cell.messages.CellClientComponentMessage;
import org.jdesktop.wonderland.common.cell.messages.CellMessage;
import org.jdesktop.wonderland.common.cell.state.CellComponentClientState;

public class CellComponentMessageReceiver implements MessageChannel.MessageReceiver {

    private static final Logger logger =
            Logger.getLogger(CellComponentMessageReceiver.class.getName());

    private final Cell cell;

    public CellComponentMessageReceiver(Cell cell) {
        this.cell = cell;
    }

    @Override
    public void messageReceived(CellMessage message) {
        CellClientComponentMessage cccm = (CellClientComponentMessage) message;
        switch (cccm.getComponentAction()) {
            case ADD:
                addComponent(cccm);
                break;
            case REMOVE:
                removeComponent(cccm);
                break;
            default:
                break;
        }
    }

    private void addComponent(CellClientComponentMessage message) {
        CellComponentClientState clientState = message.getCellComponentClientState();
        String compClassname = message.getCellComponentClientClassName();
        if (compClassname == null) {
            return;
        }
        try {
            ClassLoader cl = Cell.class.getClassLoader();
            Class<?> compClazz = cl.loadClass(compClassname);
            Class<? extends CellComponent> lookupClazz = Cell.lookupClassOf(compClazz);
            CellComponent component = cell.getComponent(lookupClazz);
            if (component == null) {
                Constructor<? extends CellComponent> constructor =
                        compClazz.asSubclass(CellComponent.class).getConstructor(Cell.class);
                component = constructor.newInstance(cell);
                component.setClientState(clientState);
                cell.addComponent(component, lookupClazz);
            } else {
                component.setClientState(clientState);
            }
        } catch (ClassNotFoundException e) {
            logger.log(Level.SEVERE, "Can't find component class " + compClassname, e);
        } catch (Exception e) {
            // InstantiationException and friends arrive wrapped as ReflectiveOperationException
            logger.log(Level.SEVERE, (String) null, e);
        }
    }

    private void removeComponent(CellClientComponentMessage message) {
        String className = message.getCellComponentClientClassName();
        if (className == null) {
            return;
        }
        ClassLoader cl = Cell.class.getClassLoader();
        try {
            Class<?> compClazz = cl.loadClass(className);
            Class<? extends CellComponent> clazz = Cell.lookupClassOf(compClazz);
            cell.removeComponent(clazz);
        } catch (ClassNotFoundException e) {
            logger.log(Level.WARNING, "Cannot remove component", e);
        }
    }
}
