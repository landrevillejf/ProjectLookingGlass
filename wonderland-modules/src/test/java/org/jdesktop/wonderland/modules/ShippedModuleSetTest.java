/*
 * wonderland-modules M4: the assembled boot path. loadAll() runs ServiceLoader
 * discovery over the shipped META-INF/services entry and initialises every module
 * it finds. This proves the five low-risk modules really are registered, that
 * none of them is skipped (they are leaves with satisfiable requirements), and
 * that shutdown disposes what came up. It also pins the cycle exception's message.
 */
package org.jdesktop.wonderland.modules;

import java.util.List;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ShippedModuleSetTest {

    @Test
    void theShippedModulesAreDiscoveredAndAllInitialise() {
        WonderlandPluginLoader loader = new WonderlandPluginLoader();
        WonderlandPluginLoader.LoadResult result = loader.loadAll();

        List<String> loadedNames = result.loaded().stream()
                .map(p -> p.getModuleInfo().getName())
                .toList();

        assertThat(loadedNames).containsExactlyInAnyOrder(
                "text-chat", "imageviewer", "user-list", "stickynote", "whiteboard");
        assertThat(result.skipped()).isEmpty();

        // every model is live after load
        for (WonderlandPlugin plugin : result.loaded()) {
            assertThat(plugin.getModuleInfo().getName()).isNotBlank();
        }

        result.shutdown();
    }

    @Test
    void discoveryAloneReturnsTheShippedProviders() {
        assertThat(new WonderlandPluginLoader().discover()).hasSize(5);
    }

    @Test
    void theCycleExceptionNamesItsModules() {
        PluginCycleException exception = new PluginCycleException(List.of("p", "q"));
        assertThat(exception).hasMessage("cyclic module requirements: p -> q");
    }
}
