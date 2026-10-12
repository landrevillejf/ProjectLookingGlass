/*
 * M2a pin: the client cell *registry* annotations.
 *
 * The load-bearing fact here is retention. @CellFactory and @CellComponentFactory
 * carry NO @Retention annotation, so they default to RetentionPolicy.CLASS --
 * invisible to runtime reflection. That is exactly why the M2b registry loader
 * must find them by scanning bytecode (ScannedClassLoader / Reflections, the
 * rebuild of the original scannotation scan) rather than by
 * Class.getAnnotation(...). If a future edit adds @Retention(RUNTIME) to either,
 * this test fails and the author must consciously decide whether the scanner
 * should switch to reflection.
 *
 * @UsesCellComponent is the opposite: RUNTIME + FIELD, because it marks a
 * *field* the cell introspects on itself at runtime.
 */
package org.jdesktop.wonderland.client.cell.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jdesktop.wonderland.client.cell.annotation.UsesCellComponent;
import org.jdesktop.wonderland.client.cell.registry.annotation.CellComponentFactory;
import org.jdesktop.wonderland.client.cell.registry.annotation.CellFactory;
import org.junit.jupiter.api.Test;

class CellRegistryAnnotationsTest {

    @Test
    void cellFactoryAnnotationDefaultsToClassRetentionNoReflectionVisibility() {
        // No @Retention meta-annotation => the JVM assigns CLASS, and
        // getAnnotation() at runtime returns null.
        assertThat(CellFactory.class.getAnnotation(Retention.class)).isNull();
        assertThat(CellFactory.class.getAnnotation(UsesCellComponent.class)).isNull();
    }

    @Test
    void cellFactoryTargetsTypesOnly() {
        Target target = CellFactory.class.getAnnotation(Target.class);
        assertThat(target).isNotNull();
        assertThat(target.value()).containsExactly(ElementType.TYPE);
    }

    @Test
    void cellComponentFactoryTargetsTypesOnlyAndIsClassRetained() {
        assertThat(CellComponentFactory.class.getAnnotation(Retention.class)).isNull();
        Target target = CellComponentFactory.class.getAnnotation(Target.class);
        assertThat(target).isNotNull();
        assertThat(target.value()).containsExactly(ElementType.TYPE);
    }

    @Test
    void usesCellComponentIsRuntimeRetainedOnFields() {
        Retention retention = UsesCellComponent.class.getAnnotation(Retention.class);
        assertThat(retention).isNotNull();
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        Target target = UsesCellComponent.class.getAnnotation(Target.class);
        assertThat(target.value()).containsExactly(ElementType.FIELD);
    }

    @Test
    void allThreeAreAnnotationTypes() {
        assertThat(CellFactory.class.isAnnotation()).isTrue();
        assertThat(CellComponentFactory.class.isAnnotation()).isTrue();
        assertThat(UsesCellComponent.class.isAnnotation()).isTrue();
    }
}
