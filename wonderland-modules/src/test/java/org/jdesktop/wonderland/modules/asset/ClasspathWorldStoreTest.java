/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M6b: ClasspathWorldStore behaviour.
 *
 * Drives the classpath store through the real M5 test resources: the
 * wonderland-almostempty-world directory is already on the test classpath
 * as a plain resource tree, so `module.xml` and `requires.xml` under it are
 * exactly what a bundled pool would look like to the store. The test
 * therefore asserts the abstraction against the same shape the
 * wonderland-avatars module ships at runtime.
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClasspathWorldStoreTest {

    private final ClasspathWorldStore store = new ClasspathWorldStore();

    @Test
    void openReadsAnExistingClasspathResource() throws Exception {
        Optional<InputStream> found = store.open(
                "wonderland-almostempty-world", "module.xml");
        assertThat(found).isPresent();
        try (InputStream in = found.get()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).isNotEmpty();
        }
    }

    @Test
    void presentReportsTrueForAKnownResourceAndFalseOtherwise() {
        assertThat(store.present("wonderland-almostempty-world", "module.xml")).isTrue();
        assertThat(store.present("wonderland-almostempty-world", "no-such-file.xml")).isFalse();
    }

    @Test
    void openReturnsEmptyForAMissingResourceWithoutThrowing() {
        assertThat(store.open("wonderland-almostempty-world", "does-not-exist.dae"))
                .isEmpty();
    }

    @Test
    void aPathEscapeAttemptIsRefusedNotResolved() {
        // The confinement rule mirrors DirectoryWorldStore's: a `../` in a
        // descriptor cannot read outside its module's resource directory.
        assertThat(store.open("wonderland-almostempty-world", "../escape.xml")).isEmpty();
        assertThat(store.present("wonderland-almostempty-world", "../escape.xml")).isFalse();
        assertThat(store.open("wonderland-almostempty-world", "..")).isEmpty();
        assertThat(store.open("wonderland-almostempty-world", "nested/../../x.xml")).isEmpty();
    }

    @Test
    void anAbsolutePathIsRefusedRatherThanTreatedAsRootRelative() {
        // Classpath resources are always relative to a classpath root; a
        // caller passing an absolute path is either buggy or probing.
        assertThat(store.open("wonderland-almostempty-world", "/etc/passwd")).isEmpty();
        assertThat(store.present("wonderland-almostempty-world", "/etc/passwd")).isFalse();
    }

    @Test
    void nullModuleOrPathDegradesToNotFoundRatherThanNpe() {
        assertThat(store.open(null, "module.xml")).isEmpty();
        assertThat(store.open("wonderland-almostempty-world", null)).isEmpty();
        assertThat(store.open("", "module.xml")).isEmpty();
        assertThat(store.open("wonderland-almostempty-world", "")).isEmpty();
        assertThat(store.present(null, "module.xml")).isFalse();
        assertThat(store.present("wonderland-almostempty-world", null)).isFalse();
    }

    @Test
    void listAssetsIsADocumentedNoOpOnTheClasspathStore() {
        // See the class header: the pool is a fixed catalog, not enumerated.
        assertThat(store.listAssets("wonderland-almostempty-world", ".dae")).isEmpty();
        assertThat(store.listAssets(null, ".dae")).isEmpty();
    }

    @Test
    void backslashSeparatorsAreNormalisedToForwardSlashBeforeLookup() {
        // A Windows-authored descriptor might use '\' in a path; the store
        // rewrites to '/' before confinement so the lookup works on any OS.
        // The target resource does not exist here, so we are asserting the
        // normalisation reaches the confinement path and returns empty
        // rather than throwing on a malformed name.
        assertThat(store.open("wonderland-almostempty-world", "nested\\missing.dae"))
                .isEmpty();
    }

    @Test
    void aCallerSuppliedClassLoaderIsHonouredRatherThanTheThreadContextOne() {
        ClassLoader explicit = ClasspathWorldStoreTest.class.getClassLoader();
        ClasspathWorldStore withLoader = new ClasspathWorldStore(explicit);
        assertThat(withLoader.present("wonderland-almostempty-world", "module.xml")).isTrue();
        // A null loader falls back to the class's own loader (not the TCCL),
        // so a caller passing null still gets a working store.
        assertThat(new ClasspathWorldStore(null)
                .present("wonderland-almostempty-world", "module.xml")).isTrue();
    }
}
