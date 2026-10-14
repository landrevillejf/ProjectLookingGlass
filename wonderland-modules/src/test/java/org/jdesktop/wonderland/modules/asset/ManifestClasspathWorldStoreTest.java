/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules (world seeding): ManifestClasspathWorldStore behaviour.
 *
 * The store is the enumeration half the plain ClasspathWorldStore deliberately
 * refuses: it answers listAssets from an explicit <module>/world-index.txt
 * catalog while every other operation delegates to the classpath store. These
 * tests drive it against the real test resources -- wonderland-almostempty-world
 * (a classpath module with NO index) and wonderland-indexed-world (a module that
 * ships one) -- so the manifest discovery, comment/blank filtering, suffix
 * matching and sort are proved on the same shape a bundled world presents, and the
 * delegate/open-present contract is proved to be the confinement rule it reuses.
 */
package org.jdesktop.wonderland.modules.asset;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ManifestClasspathWorldStoreTest {

    private static final String ALMOST_EMPTY = "wonderland-almostempty-world";
    private static final String INDEXED = "wonderland-indexed-world";

    private final ManifestClasspathWorldStore store = new ManifestClasspathWorldStore();

    @Test
    void openDelegatesToTheClasspathStore() throws Exception {
        Optional<InputStream> found = store.open(ALMOST_EMPTY, "module.xml");
        assertThat(found).isPresent();
        try (InputStream in = found.get()) {
            assertThat(in.readAllBytes()).isNotEmpty();
        }
    }

    @Test
    void presentDelegatesToTheClasspathStoreAndRefusesEscapes() {
        assertThat(store.present(ALMOST_EMPTY, "module.xml")).isTrue();
        assertThat(store.present(ALMOST_EMPTY, "no-such-file.xml")).isFalse();
        // the same confinement rule the file/avatar store enforces
        assertThat(store.open(ALMOST_EMPTY, "../escape.xml")).isEmpty();
        assertThat(store.present(ALMOST_EMPTY, "/etc/passwd")).isFalse();
    }

    @Test
    void listAssetsReadsTheManifestFiltersBySuffixAndSorts() {
        assertThat(store.listAssets(INDEXED, "-wlc.xml")).containsExactly(
                "wfs/indexed-wfs/alpha-wlc.xml",
                "wfs/indexed-wfs/beta-wlc.xml",
                "wfs/indexed-wfs/gamma-wlc.xml");
    }

    @Test
    void listAssetsWithANullSuffixReturnsEveryCatalogedAsset() {
        // '#' comments and blank lines are dropped regardless of suffix
        assertThat(store.listAssets(INDEXED, null)).containsExactly(
                "assets/readme.txt",
                "wfs/indexed-wfs/alpha-wlc.xml",
                "wfs/indexed-wfs/beta-wlc.xml",
                "wfs/indexed-wfs/gamma-wlc.xml");
    }

    @Test
    void aModuleWithoutAnIndexAdvertisesNoAssets() {
        // almostempty-world ships no world-index.txt: the "absent is empty" degrade
        assertThat(store.listAssets(ALMOST_EMPTY, "-wlc.xml")).isEmpty();
        assertThat(store.listAssets("no-such-module", "-wlc.xml")).isEmpty();
    }

    @Test
    void aCorruptIndexStreamDegradesToNoAssetsRatherThanThrowing() {
        ClassLoader hostile = new ClassLoader() {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (name.endsWith(ManifestClasspathWorldStore.INDEX_FILE)) {
                    return new InputStream() {
                        @Override
                        public int read() throws IOException {
                            throw new IOException("index is unreadable");
                        }
                    };
                }
                return null;
            }
        };
        ManifestClasspathWorldStore withHostile = new ManifestClasspathWorldStore(hostile);

        List<String> assets = withHostile.listAssets(INDEXED, "-wlc.xml");
        assertThat(assets).isEmpty();
    }
}
