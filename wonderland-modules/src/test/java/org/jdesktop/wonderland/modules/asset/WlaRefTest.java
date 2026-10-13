/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M5c (assetmgr): the wla:// reference parse + join.
 * Pins the narrow split the loader needs and the path maths it uses to resolve a
 * texture against the model directory, including every null / no-path rejection.
 */
package org.jdesktop.wonderland.modules.asset;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WlaRefTest {

    @Test
    void parsesModuleAndPath() {
        WlaRef ref = WlaRef.parse("wla://mod/dir/file.dep");
        assertThat(ref.module()).isEqualTo("mod");
        assertThat(ref.path()).isEqualTo("dir/file.dep");
    }

    @Test
    void rejectsAnythingThatIsNotAWlaReferenceWithAPath() {
        assertThat(WlaRef.parse(null)).isNull();
        assertThat(WlaRef.parse("http://mod/x")).isNull();          // wrong scheme
        assertThat(WlaRef.parse("wla://")).isNull();                // nothing after scheme
        assertThat(WlaRef.parse("wla://mod")).isNull();             // no slash
        assertThat(WlaRef.parse("wla://mod/")).isNull();            // no path after slash
        assertThat(WlaRef.parse("wla:///path")).isNull();           // no module name
    }

    @Test
    void resolveJoinsBaseAndChildAndStripsTheDotSlash() {
        assertThat(WlaRef.resolve("dir/models", "t.png")).isEqualTo("dir/models/t.png");
        assertThat(WlaRef.resolve("dir/models/", "t.png")).isEqualTo("dir/models/t.png");
        assertThat(WlaRef.resolve("dir", "./t.png")).isEqualTo("dir/t.png");
        // a null / empty base returns the normalised child alone
        assertThat(WlaRef.resolve(null, "./t.png")).isEqualTo("t.png");
        assertThat(WlaRef.resolve("", "./t.png")).isEqualTo("t.png");
        // a null / empty child returns the base
        assertThat(WlaRef.resolve("dir", null)).isEqualTo("dir");
        assertThat(WlaRef.resolve("dir", "")).isEqualTo("dir");
    }

    @Test
    void parentDirIsThePathBeforeTheLastSlash() {
        assertThat(WlaRef.parentDir("a/b/c.dae")).isEqualTo("a/b");
        assertThat(WlaRef.parentDir("c.dae")).isEmpty();
        assertThat(WlaRef.parentDir(null)).isEmpty();
    }

    @Test
    void theSchemeConstantIsExposed() {
        assertThat(WlaRef.SCHEME).isEqualTo("wla://");
    }
}
