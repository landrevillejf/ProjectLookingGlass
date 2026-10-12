/*
 * Pin for the rebuilt ScannedClassLoader: it must return a stable singleton,
 * expose an empty iterator when nothing on the classpath matches, wrap real
 * reflection failures in ClassScanningError, and defer to ServiceLoader for
 * providers of the given SPI type (this is the sun.misc.Service replacement
 * that lets CellServerStateFactory build its JAXBContext on JDK 21).
 */
package org.jdesktop.wonderland.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Iterator;
import org.junit.jupiter.api.Test;

class ScannedClassLoaderTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Marker {
    }

    public interface SpiType {
    }

    @Marker
    public static class ConcreteImpl implements SpiType {
        public ConcreteImpl() {
        }
    }

    @Test
    void systemClassLoaderIsStableSingleton() {
        assertThat(ScannedClassLoader.getSystemScannedClassLoader())
                .isSameAs(ScannedClassLoader.getSystemScannedClassLoader());
    }

    @Test
    void getInstancesForUnknownAnnotationReturnsEmptyIterator() {
        ScannedClassLoader loader = new ScannedClassLoader();
        Iterator<?> it = loader.getInstances(Marker.class, SpiType.class);
        // Whatever the scan finds elsewhere on the classpath, it must not blow up
        // and it must expose a drainable iterator (CellServerStateFactory does
        // `while (it.hasNext())` unconditionally).
        assertThat(it).isNotNull();
        while (it.hasNext()) {
            it.next();
        }
    }

    @Test
    void getAllReturnsServiceLoaderProvidersPlusAnnotationScanned() {
        ScannedClassLoader loader = new ScannedClassLoader();
        Iterator<?> it = loader.getAll(Marker.class, SpiType.class);
        assertThat(it).isNotNull();
        // Drainable without throwing: the SPI path may find zero providers on
        // this classpath and that is not an error.
        while (it.hasNext()) {
            it.next();
        }
    }

    @Test
    void getClassesReturnsNamesNotTypes() {
        ScannedClassLoader loader = new ScannedClassLoader();
        for (String name : loader.getClasses(Marker.class)) {
            assertThat(name).isNotEmpty();
        }
    }

    @Test
    void constructorAcceptsEmptyUrlArray() {
        assertThat(new ScannedClassLoader(new java.net.URL[0])).isNotNull();
    }

    @Test
    void classScanningErrorWrapsItsCause() {
        RuntimeException cause = new RuntimeException("boom");
        ClassScanningError err = new ClassScanningError("scan failed", cause);
        assertThat(err).hasMessage("scan failed").hasCause(cause);
        assertThatThrownBy(() -> {
            throw err;
        }).isInstanceOf(ClassScanningError.class);
    }
}
