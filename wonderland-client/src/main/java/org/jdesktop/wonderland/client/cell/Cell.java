/*
 * Ported from decompiled Wonderland 0.5 (org.jdesktop.wonderland.client.cell).
 *
 * The cell base class: parent/child tree, the component map, the status machine
 * (DISK -> INACTIVE -> ACTIVE walked one ordinal at a time), local/world
 * transform propagation with world-bounds aggregation, the five change-listener
 * fan-outs, @UsesCellComponent annotation injection (M2a's annotation, resolved
 * reflectively here), reflection-based component injection from a
 * CellClientState, and the cell-message send paths.
 *
 * M2c replacements -- each one is the "rebuild the unrecoverable" rule from
 * docs/wonderland-migration.md applied to a seam the 2006 code hard-wired:
 *   - ChannelComponent (needs ClientConnection / the session) is replaced by the
 *     MessageChannel interface; Cell holds one via setMessageChannel() and every
 *     original "channel == null" guard is kept verbatim, so the cell works with no
 *     channel at all until M2d wires the real one.
 *   - ClientContext.getRendererType() is replaced by the static
 *     setCurrentRendererType() holder here; ClientContext itself (InputManager,
 *     LoginManager, Preferences) lands with the login layer.
 *   - the module classloader (session.getSessionManager().getClassloader(),
 *     unrecoverable SGS-bound) is replaced by Cell.class.getClassLoader(): under
 *     M4's plain-classpath loader the app loader IS the module loader, so the
 *     injection semantics are identical without the session.
 *   - CellCache: M2c shipped the Cell(CellID) convenience constructor while the
 *     cache was still deferred. M2d lands the cache, whose instantiateCell calls
 *     the real (CellID, CellCache) constructor -- so that two-arg form is now the
 *     canonical one (the back-reference the 2006 code used via getCellCache()),
 *     and the one-arg form is kept for headless tests and cache-less cells.
 * The original cached world bounds on each status change; the localBounds null
 * guards below fix the latent NPE the 2006 tree-update walked into for a cell
 * with no bounds hint (it logged "NULL cachedVWBounds" and dumped stack).
 */
package org.jdesktop.wonderland.client.cell;

import com.jme.bounding.BoundingVolume;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.wonderland.client.cell.annotation.UsesCellComponent;
import org.jdesktop.wonderland.common.cell.CellID;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.common.cell.MultipleParentException;
import org.jdesktop.wonderland.common.cell.messages.CellClientComponentMessage;
import org.jdesktop.wonderland.common.cell.messages.CellClientStateMessage;
import org.jdesktop.wonderland.common.cell.messages.CellMessage;
import org.jdesktop.wonderland.common.cell.state.CellClientState;
import org.jdesktop.wonderland.common.cell.state.CellComponentClientState;
import org.jdesktop.wonderland.common.cell.state.CellComponentUtils;
import org.jdesktop.wonderland.common.messages.ResponseMessage;

public class Cell {

    /**
     * Renderer implementations the desktop registers by type; RENDERER_JME names
     * the scene-graph renderer (M2c-tree deferred work), NONE the absence of one.
     */
    public enum RendererType {
        RENDERER_JME,
        RENDERER_2D,
        NONE
    }

    private static RendererType currentRendererType = null;

    protected static Logger logger = Logger.getLogger(Cell.class.getName());

    private BoundingVolume cachedVWBounds;
    private BoundingVolume localBounds;
    private Cell parent;
    private final List<Cell> children = new ArrayList<>();
    private CellTransform localTransform;
    private CellTransform local2VW = new CellTransform();
    private CellTransform worldTransform = new CellTransform();
    private CellID cellID;
    private CellCache cellCache;
    private String name = null;
    private CellStatus currentStatus;
    private final Object statusLock = new Object();

    private MessageChannel channel;
    private CellClientStateMessageReceiver clientStateReceiver;
    private CellComponentMessageReceiver componentReceiver;

    private final Map<Class<? extends CellComponent>, CellComponent> components = new HashMap<>();
    private final Map<RendererType, CellRenderer> cellRenderers = new HashMap<>();

    private final Set<TransformChangeListener> transformChangeListeners = new CopyOnWriteArraySet<>();
    private final Set<ComponentChangeListener> componentChangeListeners = new CopyOnWriteArraySet<>();
    private final Set<CellStatusChangeListener> statusChangeListeners = new CopyOnWriteArraySet<>();
    private final Set<CellParentChangeListener> parentChangeListeners = new CopyOnWriteArraySet<>();
    private final Set<CellChildrenChangeListener> childChangeListeners = new CopyOnWriteArraySet<>();

    /** The canonical 2006 constructor: a cell knows its cache (load-time back-ref). */
    public Cell(CellID cellID, CellCache cellCache) {
        this.currentStatus = CellStatus.DISK;
        this.cellID = cellID;
        this.cellCache = cellCache;
        logger.fine("Cell: Creating new Cell ID=" + cellID);
    }

    /** Cache-less convenience form for headless tests and standalone cells. */
    public Cell(CellID cellID) {
        this(cellID, null);
    }

    public CellCache getCellCache() {
        return cellCache;
    }

    /** Replaces the 2006 ClientContext.getRendererType() global; null creates none. */
    public static void setCurrentRendererType(RendererType rendererType) {
        currentRendererType = rendererType;
    }

    public static RendererType getRendererType() {
        return currentRendererType;
    }

    /** M2d wires the real channel here; null keeps the original no-channel guards. */
    public void setMessageChannel(MessageChannel channel) {
        this.channel = channel;
    }

    public CellID getCellID() {
        return cellID;
    }

    public Cell getParent() {
        return parent;
    }

    public List<Cell> getChildren() {
        synchronized (children) {
            return new ArrayList<>(children);
        }
    }

    public void addChild(Cell child) throws MultipleParentException {
        if (child.getParent() != null) {
            throw new MultipleParentException();
        }
        synchronized (children) {
            children.add(child);
            child.setParent(this);
        }
        notifyChildChangeListeners(child, true);
    }

    public void removeChild(Cell child) {
        synchronized (children) {
            if (children.remove(child)) {
                child.setParent(null);
            }
        }
        notifyChildChangeListeners(child, false);
    }

    /**
     * Lookup is by the @ComponentLookupClass key, which in the 2006 convention is
     * any interface type -- not necessarily a CellComponent subclass -- so the
     * parameter stays unbounded like the original raw Class. The caller's target
     * type is what the unchecked cast promises.
     */
    @SuppressWarnings("unchecked")
    public <T> T getComponent(Class<T> cellComponentClass) {
        synchronized (components) {
            return (T) components.get(cellComponentClass);
        }
    }

    public void addComponent(CellComponent component) {
        addComponent(component, lookupClassOf(component.getClass()));
    }

    /** CellComponentUtils.getLookupClass predates generics; pin its result for the map key. */
    @SuppressWarnings("unchecked")
    static Class<? extends CellComponent> lookupClassOf(Class<?> clazz) {
        return (Class<? extends CellComponent>) CellComponentUtils.getLookupClass(clazz);
    }

    @SuppressWarnings("unchecked")
    public void addComponent(CellComponent component, Class<?> componentClass) {
        synchronized (components) {
            CellComponent previous = components.put((Class<? extends CellComponent>) componentClass, component);
            if (previous != null) {
                throw new IllegalArgumentException("Adding duplicate component of class "
                        + component.getClass().getName());
            }
        }

        synchronized (statusLock) {
            if (currentStatus.ordinal() > CellStatus.DISK.ordinal()) {
                resolveAutoComponentAnnotationsForComponents(component);
            }
            component.setComponentStatus(currentStatus, true);
        }

        notifyComponentChangeListeners(ComponentChangeListener.ChangeType.ADDED, component);
    }

    public void removeComponent(Class<? extends CellComponent> componentClass) {
        CellComponent component;
        synchronized (components) {
            component = components.remove(componentClass);
        }
        if (component != null) {
            component.setComponentStatus(CellStatus.DISK, false);
            notifyComponentChangeListeners(ComponentChangeListener.ChangeType.REMOVED, component);
        }
    }

    public Collection<CellComponent> getComponents() {
        synchronized (components) {
            return new ArrayList<>(components.values());
        }
    }

    private CellComponent[] getComponentsArray() {
        synchronized (components) {
            return components.values().toArray(new CellComponent[components.size()]);
        }
    }

    void setParent(Cell parent) {
        this.parent = parent;
        notifyParentChangeListeners(parent);
    }

    public int getNumChildren() {
        synchronized (children) {
            return children.size();
        }
    }

    public CellTransform getLocalTransform() {
        return localTransform == null ? null : localTransform.clone(null);
    }

    void setLocalTransform(CellTransform localTransform, TransformChangeListener.ChangeSource source) {
        if (this.localTransform == null || !this.localTransform.equals(localTransform)) {
            if (localTransform == null) {
                this.localTransform = null;
                Cell current = getParent();
                while (current != null) {
                    CellTransform parentWorldTransform = current.getWorldTransform();
                    if (parentWorldTransform != null) {
                        setWorldTransform(parentWorldTransform, source);
                        current = null;
                    } else {
                        current = current.getParent();
                    }
                }
            } else {
                this.localTransform = localTransform.clone(null);
                if (parent != null) {
                    worldTransform = localTransform.clone(null);
                    worldTransform = parent.getWorldTransform().mul(worldTransform);
                    local2VW = null;
                } else {
                    worldTransform = localTransform.clone(null);
                    local2VW = null;
                }
                refreshWorldBounds();

                notifyTransformChangeListeners(source);
            }

            if (cachedVWBounds == null) {
                logger.warning("********** NULL cachedVWBounds " + getName() + "  "
                        + localBounds + "  " + localTransform);
            }

            for (Cell child : getChildren()) {
                transformTreeUpdate(this, child, source);
            }

            for (CellRenderer rend : getCellRenderers()) {
                rend.cellTransformUpdate(localTransform);
            }
        }
    }

    public CellTransform getLocalToWorldTransform() {
        if (local2VW == null) {
            local2VW = worldTransform.clone(null);
            local2VW.invert();
        }
        return local2VW.clone(null);
    }

    public CellTransform getWorldTransform() {
        return worldTransform.clone(null);
    }

    void setWorldTransform(CellTransform worldTransform, TransformChangeListener.ChangeSource source) {
        this.worldTransform = worldTransform.clone(null);
        refreshWorldBounds();
        local2VW = null;
        notifyTransformChangeListeners(source);
    }

    /** cachedVWBounds = localBounds transformed by worldTransform, or null without a hint. */
    private void refreshWorldBounds() {
        if (localBounds != null) {
            cachedVWBounds = localBounds.clone(cachedVWBounds);
            worldTransform.transform(cachedVWBounds);
        }
    }

    private void transformTreeUpdate(Cell parent, Cell child, TransformChangeListener.ChangeSource source) {
        CellTransform parentWorldTransform = parent.getWorldTransform();
        CellTransform childTransform = child.getLocalTransform();
        if (childTransform != null) {
            childTransform = parentWorldTransform.mul(childTransform);
            child.setWorldTransform(childTransform, source);
        } else {
            child.setWorldTransform(parentWorldTransform, source);
        }

        BoundingVolume ret = child.getWorldBounds();
        for (Cell grandChild : child.getChildren()) {
            transformTreeUpdate(child, grandChild, source);
            BoundingVolume grandChildBounds = grandChild.getWorldBounds();
            if (ret == null) {
                ret = grandChildBounds;
            } else if (grandChildBounds != null) {
                ret.mergeLocal(grandChildBounds);
            }
        }
        // the original returned null from the recursion and merged it blindly; the
        // guard above keeps the same aggregation for real bounds and drops the
        // no-hint case instead of dumping stack
        child.setWorldBounds(ret);
    }

    public BoundingVolume getWorldBounds() {
        return cachedVWBounds;
    }

    private void setWorldBounds(BoundingVolume cachedVWBounds) {
        this.cachedVWBounds = cachedVWBounds;
    }

    public String getName() {
        return name == null ? cellID.toString() : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BoundingVolume getLocalBounds() {
        return localBounds == null ? null : localBounds.clone(null);
    }

    public void setLocalBounds(BoundingVolume localBounds) {
        this.localBounds = localBounds;
    }

    public CellStatus getStatus() {
        synchronized (statusLock) {
            return currentStatus;
        }
    }

    private void setRendererStatus(CellRenderer rend, CellStatus status) {
        synchronized (statusLock) {
            int currentRendStatus = rend.getStatus().ordinal();
            int requiredRendStatus = status.ordinal();
            if (currentRendStatus != requiredRendStatus) {
                int dir = requiredRendStatus > currentRendStatus ? 1 : -1;
                boolean increasing = dir == 1;
                while (currentRendStatus != requiredRendStatus) {
                    currentRendStatus += dir;
                    rend.setStatus(CellStatus.values()[currentRendStatus], increasing);
                }
            }
        }
    }

    protected void setStatus(CellStatus status, boolean increasing) {
        synchronized (statusLock) {
            if (status == CellStatus.INACTIVE && increasing) {
                resolveAutoComponentAnnotationsForCell();
                for (CellComponent c : getComponentsArray()) {
                    resolveAutoComponentAnnotationsForComponents(c);
                }
            }

            currentStatus = status;

            for (CellComponent component : getComponentsArray()) {
                component.setComponentStatus(status, increasing);
            }

            for (CellRenderer renderer : getCellRenderers()) {
                setRendererStatus(renderer, status);
            }

            switch (status) {
                case DISK:
                    if (!increasing) {
                        if (channel != null) {
                            channel.removeMessageReceiver(CellClientStateMessage.class);
                            channel.removeMessageReceiver(CellClientComponentMessage.class);
                        }
                        clientStateReceiver = null;
                        componentReceiver = null;
                        synchronized (components) {
                            components.clear();
                        }
                        synchronized (cellRenderers) {
                            cellRenderers.clear();
                        }
                    }
                    break;
                case ACTIVE:
                    if (increasing && clientStateReceiver == null) {
                        clientStateReceiver = new CellClientStateMessageReceiver(this);
                        componentReceiver = new CellComponentMessageReceiver(this);
                        if (channel != null) {
                            channel.addMessageReceiver(CellClientStateMessage.class, clientStateReceiver);
                            channel.addMessageReceiver(CellClientComponentMessage.class, componentReceiver);
                        }
                        try {
                            createCellRendererImpl(currentRendererType);
                        } catch (Exception e) {
                            logger.log(Level.SEVERE, "Failed to get Cell Renderer for cell "
                                    + getClass().getName(), e);
                        }
                    }
                    break;
                default:
                    break;
            }
        }
    }

    protected void fireCellStatusChanged(CellStatus status) {
        notifyStatusChangeListeners(status);
        CellManager.getCellManager().notifyCellStatusChange(this, status);
    }

    private void resolveAutoComponentAnnotationsForComponents(CellComponent c) {
        for (Class<?> clazz = c.getClass(); clazz != CellComponent.class; clazz = clazz.getSuperclass()) {
            resolveAnnotations(clazz, c);
        }
    }

    private void resolveAutoComponentAnnotationsForCell() {
        for (Class<?> clazz = getClass(); clazz != Cell.class; clazz = clazz.getSuperclass()) {
            resolveAnnotations(clazz, this);
        }
    }

    private void resolveAnnotations(Class<?> clazz, Object target) {
        for (Field f : clazz.getDeclaredFields()) {
            UsesCellComponent a = f.getAnnotation(UsesCellComponent.class);
            if (a == null) {
                continue;
            }
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("****** GOT ANNOTATION for field " + f.getName() + "  " + f.getType());
            }

            Class<? extends CellComponent> componentClazz =
                    (Class<? extends CellComponent>) f.getType();
            CellComponent comp = getComponent(lookupClassOf(componentClazz));
            if (comp == null) {
                try {
                    Constructor<? extends CellComponent> constructor =
                            componentClazz.getConstructor(Cell.class);
                    comp = constructor.newInstance(this);
                    addComponent(comp);
                } catch (ReflectiveOperationException | IllegalArgumentException e) {
                    logger.log(Level.SEVERE, (String) null, e);
                }
            }

            try {
                f.setAccessible(true);
                f.set(target, comp);
            } catch (IllegalAccessException e) {
                logger.log(Level.SEVERE, (String) null, e);
            }
        }
    }

    public void setClientState(CellClientState configData) {
        setName(configData.getName());
        logger.fine("configure cell " + getCellID() + "  " + getClass());

        for (String compClassname : configData.getClientComponentClasses()) {
            try {
                // M2c seam: the module classloader is the app classloader (see header)
                ClassLoader cl = Cell.class.getClassLoader();
                Class<?> compClazz = cl.loadClass(compClassname);
                Class<? extends CellComponent> lookupClazz = lookupClassOf(compClazz);
                CellComponent component = getComponent(lookupClazz);
                if (component == null) {
                    Constructor<? extends CellComponent> constructor =
                            compClazz.asSubclass(CellComponent.class).getConstructor(Cell.class);
                    component = constructor.newInstance(this);
                    CellComponentClientState clientState =
                            configData.getCellComponentClientState(compClassname);
                    if (clientState != null) {
                        component.setClientState(clientState);
                    }
                    addComponent(component, lookupClazz);
                } else {
                    CellComponentClientState clientState =
                            configData.getCellComponentClientState(compClassname);
                    if (clientState != null) {
                        component.setClientState(clientState);
                    }
                }
            } catch (ClassNotFoundException e) {
                logger.log(Level.SEVERE, "Can't find component class " + compClassname, e);
            } catch (Exception e) {
                logger.log(Level.SEVERE, (String) null, e);
            }
        }
    }

    public void sendCellMessage(CellMessage message) {
        if (channel == null) {
            logger.severe("Unable to find channel on cell id " + getCellID() + " with name " + getName());
            return;
        }
        channel.send(message);
    }

    public ResponseMessage sendCellMessageAndWait(CellMessage message) {
        if (channel == null) {
            logger.severe("Unable to find channel on cell id " + getCellID() + " with name " + getName());
            return null;
        }
        try {
            return channel.sendAndWait(message);
        } catch (InterruptedException e) {
            logger.log(Level.WARNING, "Sending message and waiting got interrupted on cell id "
                    + getCellID() + " with name " + getName(), e);
            return null;
        }
    }

    /** Subclass hook: cell types that own renderers create them here (M2d+ tree). */
    protected CellRenderer createCellRenderer(RendererType rendererType) {
        return null;
    }

    private void createCellRendererImpl(RendererType rendererType) {
        if (rendererType == null) {
            return;
        }
        CellRenderer created = null;
        synchronized (cellRenderers) {
            CellRenderer ret = cellRenderers.get(rendererType);
            if (ret == null) {
                ret = createCellRenderer(rendererType);
                if (ret != null) {
                    cellRenderers.put(rendererType, ret);
                    created = ret;
                }
            }
        }
        if (created != null) {
            synchronized (statusLock) {
                setRendererStatus(created, getStatus());
            }
        }
    }

    public CellRenderer getCellRenderer(RendererType rendererType) {
        synchronized (cellRenderers) {
            return cellRenderers.get(rendererType);
        }
    }

    private CellRenderer[] getCellRenderers() {
        synchronized (cellRenderers) {
            return cellRenderers.values().toArray(new CellRenderer[cellRenderers.size()]);
        }
    }

    public void addTransformChangeListener(TransformChangeListener listener) {
        transformChangeListeners.add(listener);
    }

    public void removeTransformChangeListener(TransformChangeListener listener) {
        transformChangeListeners.remove(listener);
    }

    private void notifyTransformChangeListeners(TransformChangeListener.ChangeSource source) {
        for (TransformChangeListener listener : transformChangeListeners) {
            listener.transformChanged(this, source);
        }
    }

    public void addComponentChangeListener(ComponentChangeListener listener) {
        componentChangeListeners.add(listener);
    }

    public void removeComponentChangeListener(ComponentChangeListener listener) {
        componentChangeListeners.remove(listener);
    }

    private void notifyComponentChangeListeners(ComponentChangeListener.ChangeType type,
            CellComponent component) {
        for (ComponentChangeListener listener : componentChangeListeners) {
            listener.componentChanged(this, type, component);
        }
    }

    public void addStatusChangeListener(CellStatusChangeListener listener) {
        statusChangeListeners.add(listener);
    }

    public void removeStatusChangeListener(CellStatusChangeListener listener) {
        statusChangeListeners.remove(listener);
    }

    private void notifyStatusChangeListeners(CellStatus status) {
        for (CellStatusChangeListener listener : statusChangeListeners) {
            listener.cellStatusChanged(this, status);
        }
    }

    public void addParentChangeListener(CellParentChangeListener listener) {
        parentChangeListeners.add(listener);
    }

    public void removeParentChangeListener(CellParentChangeListener listener) {
        parentChangeListeners.remove(listener);
    }

    private void notifyParentChangeListeners(Cell parent) {
        for (CellParentChangeListener listener : parentChangeListeners) {
            listener.parentChanged(this, parent);
        }
    }

    public void addChildrenChangeListener(CellChildrenChangeListener listener) {
        childChangeListeners.add(listener);
    }

    public void removeChildChangeListener(CellChildrenChangeListener listener) {
        childChangeListeners.remove(listener);
    }

    private void notifyChildChangeListeners(Cell child, boolean added) {
        for (CellChildrenChangeListener listener : childChangeListeners) {
            if (added) {
                listener.childAdded(this, child);
            } else {
                listener.childRemoved(this, child);
            }
        }
    }
}
