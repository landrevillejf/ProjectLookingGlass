/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M4: the loader brain, exercised headless with hand-built
 * plugins. Covers the four ordering/dependency behaviours the class header
 * claims -- dependency-before-dependent order, the skip (and its cascade) for an
 * unsatisfiable requirement, the loud cycle failure, and the duplicate / missing
 * name guards -- plus reverse-order dispose and the PluginContext peer lookup a
 * dependent sees at its own initialisation.
 */
package org.jdesktop.wonderland.modules;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.wonderland.common.modules.ModuleInfo;
import org.jdesktop.wonderland.common.modules.ModuleRequires;
import org.jdesktop.wonderland.modules.api.PluginContext;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class WonderlandPluginLoaderTest {

    /** A scriptable plugin: a name, a set of required names, and a shared trace. */
    private static final class TracePlugin implements WonderlandPlugin {
        final String name;
        final List<String> requires;
        final List<String> trace;
        final List<PluginContext> seenContexts = new ArrayList<>();

        TracePlugin(String name, List<String> requires, List<String> trace) {
            this.name = name;
            this.requires = requires;
            this.trace = trace;
        }

        @Override
        public ModuleInfo getModuleInfo() {
            return new ModuleInfo(name, 1, 0, 0);
        }

        @Override
        public ModuleRequires getRequires() {
            ModuleInfo[] deps = requires.stream()
                    .map(dep -> new ModuleInfo(dep, 1, 0, 0))
                    .toArray(ModuleInfo[]::new);
            return new ModuleRequires(deps);
        }

        @Override
        public void initialize(PluginContext context) {
            trace.add("init:" + name);
            seenContexts.add(context);
        }

        @Override
        public void dispose() {
            trace.add("dispose:" + name);
        }
    }

    private List<String> names(List<WonderlandPlugin> loaded) {
        return loaded.stream().map(p -> p.getModuleInfo().getName()).toList();
    }

    @Test
    void aDependencyInitialisesBeforeItsDependent() {
        List<String> trace = new ArrayList<>();
        WonderlandPlugin c = new TracePlugin("c", List.of(), trace);
        WonderlandPlugin b = new TracePlugin("b", List.of("c"), trace);
        WonderlandPlugin a = new TracePlugin("a", List.of("b"), trace);

        WonderlandPluginLoader.LoadResult result =
                new WonderlandPluginLoader().load(List.of(a, b, c));

        assertThat(names(result.loaded())).containsExactly("c", "b", "a");
        assertThat(trace).containsExactly("init:c", "init:b", "init:a");
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void aDependentSeesItsAlreadyInitialisedRequirementInItsContext() {
        List<String> trace = new ArrayList<>();
        TracePlugin base = new TracePlugin("base", List.of(), trace);
        TracePlugin dependent = new TracePlugin("dependent", List.of("base"), trace);

        new WonderlandPluginLoader().load(List.of(dependent, base));

        // when "dependent" initialised, "base" was already in its context
        PluginContext seen = dependent.seenContexts.get(0);
        assertThat(seen.plugin("base")).isSameAs(base);
        assertThat(seen.loadedModuleNames()).contains("base");
    }

    @Test
    void aModuleRequiringAnAbsentNameIsSkippedAndSoIsItsDependent() {
        List<String> trace = new ArrayList<>();
        WonderlandPlugin ghost = new TracePlugin("ghost-user", List.of("does-not-exist"), trace);
        WonderlandPlugin dependsOnGhost = new TracePlugin("follower", List.of("ghost-user"), trace);
        WonderlandPlugin healthy = new TracePlugin("healthy", List.of(), trace);

        WonderlandPluginLoader.LoadResult result =
                new WonderlandPluginLoader().load(List.of(ghost, dependsOnGhost, healthy));

        assertThat(names(result.loaded())).containsExactly("healthy");
        assertThat(result.skipped()).containsExactlyInAnyOrder("ghost-user", "follower");
        assertThat(trace).containsExactly("init:healthy");
    }

    @Test
    void aRequirementCycleFailsTheLoad() {
        List<String> trace = new ArrayList<>();
        WonderlandPlugin p = new TracePlugin("p", List.of("q"), trace);
        WonderlandPlugin q = new TracePlugin("q", List.of("p"), trace);

        PluginCycleException thrown = catchThrowableOfType(
                () -> new WonderlandPluginLoader().load(List.of(p, q)),
                PluginCycleException.class);

        assertThat(thrown).hasMessageContaining("cyclic module requirements");
        assertThat(trace).isEmpty(); // nothing initialises when the set is cyclic
    }

    @Test
    void aModuleRequiringItselfIsTreatedAsACycle() {
        List<String> trace = new ArrayList<>();
        WonderlandPlugin s = new TracePlugin("self", List.of("self"), trace);
        WonderlandPlugin ok = new TracePlugin("ok", List.of(), trace);

        assertThatThrownBy(() -> new WonderlandPluginLoader().load(List.of(s, ok)))
                .isInstanceOf(PluginCycleException.class);
    }

    @Test
    void twoPluginsSharingAModuleNameAreRejected() {
        List<String> trace = new ArrayList<>();
        WonderlandPlugin one = new TracePlugin("dupe", List.of(), trace);
        WonderlandPlugin other = new TracePlugin("dupe", List.of(), trace);

        assertThatThrownBy(() -> new WonderlandPluginLoader().load(List.of(one, other)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dupe");
    }

    @Test
    void aPluginWithNoModuleNameIsRejected() {
        WonderlandPlugin nameless = new WonderlandPlugin() {
            @Override
            public ModuleInfo getModuleInfo() {
                return new ModuleInfo(); // name stays null
            }

            @Override
            public void initialize(PluginContext context) {
            }
        };

        assertThatThrownBy(() -> new WonderlandPluginLoader().load(List.of(nameless)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no module name");
    }

    @Test
    void shutdownDisposesInReverseInitialisationOrder() {
        List<String> trace = new ArrayList<>();
        WonderlandPlugin c = new TracePlugin("c", List.of(), trace);
        WonderlandPlugin a = new TracePlugin("a", List.of("c"), trace);

        WonderlandPluginLoader.LoadResult result =
                new WonderlandPluginLoader().load(List.of(a, c));
        trace.clear();
        result.shutdown();

        assertThat(trace).containsExactly("dispose:a", "dispose:c");
    }

    @Test
    void eachShutdownPassDisposesEveryLoadedPluginOnce() {
        List<String> trace = new ArrayList<>();
        WonderlandPlugin only = new TracePlugin("only", List.of(), trace);
        WonderlandPluginLoader.LoadResult result =
                new WonderlandPluginLoader().load(List.of(only));
        trace.clear();

        result.shutdown();

        // a single shutdown pass tears each loaded plugin down exactly once
        assertThat(trace).containsExactly("dispose:only");
    }

    @Test
    void aPluginWithNoModuleInfoObjectAtAllIsRejected() {
        WonderlandPlugin infoless = new WonderlandPlugin() {
            @Override
            public ModuleInfo getModuleInfo() {
                return null; // not a blank name -- no ModuleInfo at all
            }

            @Override
            public void initialize(PluginContext context) {
            }
        };

        assertThatThrownBy(() -> new WonderlandPluginLoader().load(List.of(infoless)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no module name");
    }

    @Test
    void aRequiresEntryWithNoNameIsIgnoredRatherThanRequired() {
        List<String> trace = new ArrayList<>();
        WonderlandPlugin sloppy = new WonderlandPlugin() {
            @Override
            public ModuleInfo getModuleInfo() {
                return new ModuleInfo("sloppy", 1, 0, 0);
            }

            @Override
            public ModuleRequires getRequires() {
                // a requires entry that carries no module name: requiredNames()
                // must skip it, not add a null requirement no module could match.
                return new ModuleRequires(new ModuleInfo[] {new ModuleInfo()});
            }

            @Override
            public void initialize(PluginContext context) {
                trace.add("init:sloppy");
            }
        };

        WonderlandPluginLoader.LoadResult result =
                new WonderlandPluginLoader().load(List.of(sloppy));

        assertThat(names(result.loaded())).containsExactly("sloppy");
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void aDiamondDependencyInitialisesBaseFirstAndTopLast() {
        List<String> trace = new ArrayList<>();
        WonderlandPlugin base = new TracePlugin("base", List.of(), trace);
        WonderlandPlugin left = new TracePlugin("left", List.of("base"), trace);
        WonderlandPlugin right = new TracePlugin("right", List.of("base"), trace);
        WonderlandPlugin top = new TracePlugin("top", List.of("left", "right"), trace);

        WonderlandPluginLoader.LoadResult result =
                new WonderlandPluginLoader().load(List.of(top, left, right, base));

        // "top" has indegree 2: the first dependency to finish leaves it at 1
        // (not ready), only the second brings it to 0 -- the branch a linear
        // chain never reaches.
        assertThat(names(result.loaded())).containsExactly("base", "left", "right", "top");
        assertThat(trace).containsExactly("init:base", "init:left", "init:right", "init:top");
    }

    @Test
    void shutdownDisposesADuplicatedPluginOnlyOnce() {
        List<String> trace = new ArrayList<>();
        WonderlandPlugin only = new TracePlugin("only", List.of(), trace);
        // LoadResult's package-private constructor lets a test build the
        // degenerate case production never produces -- the same plugin twice --
        // so the dedup guard in shutdown() is exercised on its false arm.
        WonderlandPluginLoader.LoadResult result =
                new WonderlandPluginLoader.LoadResult(List.of(only, only), List.of());

        result.shutdown();

        assertThat(trace).containsExactly("dispose:only");
    }

    @Test
    void aBrokenServiceEntryIsLoggedAndSkippedRatherThanThrown(@TempDir Path dir)
            throws Exception {
        Path services = dir.resolve("META-INF").resolve("services");
        Files.createDirectories(services);
        Files.writeString(services.resolve(WonderlandPlugin.class.getName()),
                "com.example.DoesNotExistPlugin\n");
        URL[] urls = {dir.toUri().toURL()};
        // A loader parented on the bootstrap loader sees ONLY this bogus service
        // file, so the very first provider lookup fails -- exactly the broken-jar
        // case discover() must survive (log and return what it found) rather
        // than propagate as a ServiceConfigurationError.
        try (URLClassLoader broken = new URLClassLoader(urls, null)) {
            List<WonderlandPlugin> found = new WonderlandPluginLoader().discover(broken);
            assertThat(found).isEmpty();
        }
    }
}
