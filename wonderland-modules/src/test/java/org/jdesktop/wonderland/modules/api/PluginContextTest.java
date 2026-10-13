/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4: the PluginContext lookup surface -- name lookup, the
 * typed overload (including the wrong-type cast failure and the absent-name
 * null), the loaded-name snapshot, and the defensive copy the constructor makes
 * of the loader's live registry.
 */
package org.jdesktop.wonderland.modules.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jdesktop.wonderland.common.modules.ModuleInfo;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginContextTest {

    private static WonderlandPlugin named(String name) {
        return new WonderlandPlugin() {
            @Override
            public ModuleInfo getModuleInfo() {
                return new ModuleInfo(name, 1, 0, 0);
            }

            @Override
            public void initialize(PluginContext context) {
            }
        };
    }

    /** A plugin type the typed lookup can be asked for. */
    private interface Special extends WonderlandPlugin {
    }

    @Test
    void looksUpByModuleName() {
        WonderlandPlugin alpha = named("alpha");
        Map<String, WonderlandPlugin> registry = new LinkedHashMap<>();
        registry.put("alpha", alpha);
        PluginContext context = new PluginContext(registry);

        assertThat(context.plugin("alpha")).isSameAs(alpha);
        assertThat(context.plugin("absent")).isNull();
    }

    @Test
    void typedLookupCastsReturnsNullOrThrowsOnWrongType() {
        WonderlandPlugin plain = named("plain");
        WonderlandPlugin special = new Special() {
            @Override
            public ModuleInfo getModuleInfo() {
                return new ModuleInfo("special", 1, 0, 0);
            }

            @Override
            public void initialize(PluginContext context) {
            }
        };
        Map<String, WonderlandPlugin> registry = new LinkedHashMap<>();
        registry.put("plain", plain);
        registry.put("special", special);
        PluginContext context = new PluginContext(registry);

        assertThat(context.plugin("special", Special.class)).isSameAs(special);
        assertThat(context.plugin("missing", Special.class)).isNull();
        assertThatThrownBy(() -> context.plugin("plain", Special.class))
                .isInstanceOf(ClassCastException.class);
    }

    @Test
    void loadedNamesFollowInsertionOrder() {
        Map<String, WonderlandPlugin> registry = new LinkedHashMap<>();
        registry.put("first", named("first"));
        registry.put("second", named("second"));
        PluginContext context = new PluginContext(registry);

        assertThat(context.loadedModuleNames()).containsExactly("first", "second");
    }

    @Test
    void theContextCopiesTheRegistrySoLaterMutationsAreInvisible() {
        Map<String, WonderlandPlugin> registry = new LinkedHashMap<>();
        registry.put("alpha", named("alpha"));
        PluginContext context = new PluginContext(registry);

        registry.put("beta", named("beta")); // loader adds another module after the fact

        assertThat(context.loadedModuleNames()).containsExactly("alpha");
    }
}
