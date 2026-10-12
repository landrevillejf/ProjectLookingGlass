/*
 * Ported org.jdesktop.wonderland.common.utils.ScannedClassLoader.
 *
 * The original extended sun.misc.Service (removed in JDK 9+) and scanned the
 * classpath with scannotation (a dead library that cannot read modern class
 * files) to discover the @ServerState cell-state types for a JAXBContext. This
 * rebuilds the same public surface on java.util.ServiceLoader and Reflections;
 * the plugin/annotation-scanning concern is finalised in milestone M4
 * (wonderland-modules). CellServerStateFactory only ever uses the singleton plus
 * getInstances/getAll, and it adds the core state classes explicitly, so the
 * JAXBContext is built whether or not the scan finds anything on the classpath.
 */
package org.jdesktop.wonderland.common.utils;

import java.lang.annotation.Annotation;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;
import org.reflections.util.ClasspathHelper;

public class ScannedClassLoader {

    private static final Logger logger = Logger.getLogger(ScannedClassLoader.class.getName());

    private final Reflections reflections;

    private static final class SingletonHolder {
        private static final ScannedClassLoader INSTANCE = new ScannedClassLoader();
    }

    public static ScannedClassLoader getSystemScannedClassLoader() {
        return SingletonHolder.INSTANCE;
    }

    public ScannedClassLoader() {
        this(new URL[0]);
    }

    public ScannedClassLoader(URL[] urls) {
        ConfigurationBuilder builder = new ConfigurationBuilder()
                .setScanners(Scanners.SubTypes, Scanners.TypesAnnotated);
        if (urls != null && urls.length > 0) {
            builder.setUrls(urls);
        } else {
            // Fall back to the Wonderland packages so the default scan stays cheap.
            builder.setUrls(ClasspathHelper.forPackage("org.jdesktop.wonderland"));
        }
        this.reflections = new Reflections(builder);
    }

    /** Names of the classes annotated with {@code clazz}, as the original returned. */
    @SuppressWarnings("unchecked")
    public Set<String> getClasses(Class<?> clazz) {
        Set<String> out = new LinkedHashSet<>();
        for (Class<?> type : reflections.getTypesAnnotatedWith((Class<? extends Annotation>) clazz)) {
            out.add(type.getName());
        }
        return out;
    }

    /** Instances of the concrete {@code clazz}-assignable types annotated with {@code annot}. */
    @SuppressWarnings("unchecked")
    public Iterator getInstances(Class<?> annot, final Class<?> clazz) {
        List<Object> instances = new ArrayList<>();
        for (String name : getClasses(annot)) {
            try {
                Class<?> loaded = Class.forName(name, false, Thread.currentThread().getContextClassLoader());
                if (clazz.isAssignableFrom(loaded)
                        && !java.lang.reflect.Modifier.isAbstract(loaded.getModifiers())) {
                    instances.add(loaded.getDeclaredConstructor().newInstance());
                }
            } catch (ReflectiveOperationException | LinkageError ex) {
                throw new ClassScanningError("Error loading " + name, ex);
            }
        }
        return instances.iterator();
    }

    /** ServiceLoader-provided instances, then the annotation-scanned instances. */
    @SuppressWarnings("unchecked")
    public Iterator getAll(Class<?> annot, Class<?> clazz) {
        List<Object> out = new ArrayList<>();
        try {
            ServiceLoader<?> loader = ServiceLoader.load(clazz, Thread.currentThread().getContextClassLoader());
            for (Object provider : loader) {
                out.add(provider);
            }
        } catch (ServiceConfigurationError sce) {
            logger.log(Level.WARNING, "Service load failed for " + clazz, sce);
        }
        Iterator<Object> scanned = getInstances(annot, clazz);
        while (scanned.hasNext()) {
            out.add(scanned.next());
        }
        return out.iterator();
    }
}
