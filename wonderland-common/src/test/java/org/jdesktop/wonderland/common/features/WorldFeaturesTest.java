/*
 * The feature vocabulary the wiring phase depends on. Two claims matter and
 * neither is obvious from the code: a missing or unreadable property means ON
 * (the default that makes a wired feature actually reachable), and the master
 * switch beats an explicit per-feature "on" (the lever exists to get the old
 * renderer back in one argument, not seven).
 */
package org.jdesktop.wonderland.common.features;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorldFeaturesTest {

    @Test
    void noPropertyAnywhereMeansEveryFeatureIsOn() {
        assertThat(WorldFeatures.enabled(null, null)).isTrue();
        assertThat(WorldFeatures.enabled("on", null)).isTrue();
    }

    @Test
    void theFourOffSpellingsAllDisableAndTheFourOnSpellingsAllEnable() {
        for (String off : new String[] {"off", "false", "no", "0", " OFF ", "False"}) {
            assertThat(WorldFeatures.enabled(null, off))
                    .as("value [%s] must disable", off).isFalse();
        }
        for (String on : new String[] {"on", "true", "yes", "1", " TRUE", "Yes "}) {
            assertThat(WorldFeatures.enabled(null, on))
                    .as("value [%s] must enable", on).isTrue();
        }
    }

    @Test
    void anUnreadableValueIsNotAnInstructionToShipWithoutTheFeature() {
        // A typo ("flase") must not silently cost the user its lighting.
        assertThat(WorldFeatures.enabled(null, "flase")).isTrue();
        assertThat(WorldFeatures.enabled(null, "")).isTrue();
    }

    @Test
    void theMasterSwitchOverridesAnExplicitlyEnabledFeature() {
        // off wins even against own=on: one lever for the whole program.
        assertThat(WorldFeatures.enabled("off", "on")).isFalse();
        assertThat(WorldFeatures.enabled("0", null)).isFalse();
        // and a master that is merely typo'd leaves the per-feature rule in charge
        assertThat(WorldFeatures.enabled("of", "off")).isFalse();
        assertThat(WorldFeatures.enabled("of", null)).isTrue();
    }

    @Test
    void enabledReadsTheTwoPropertiesItDocuments() {
        // The live accessor, driven through real system properties, restored
        // afterwards so no other suite in this shared JVM inherits the switch.
        String key = WorldFeatures.PREFIX + WorldFeatures.LIGHTING;
        String previous = System.getProperty(key);
        try {
            if (previous == null) {
                System.clearProperty(key);
            }
            assertThat(WorldFeatures.enabled(WorldFeatures.LIGHTING)).isTrue();
            System.setProperty(key, "off");
            assertThat(WorldFeatures.enabled(WorldFeatures.LIGHTING)).isFalse();
            assertThat(WorldFeatures.enabled(WorldFeatures.COLLISION)).isTrue();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @Test
    void theSummaryNamesEveryFeatureAndTheMasterThatWasRead() {
        Map<String, String> props = new HashMap<>();
        props.put(WorldFeatures.MASTER, "off");
        String line = WorldFeatures.summary(props::get);
        assertThat(line).contains("lg.wonderland.features=off");
        for (String name : new String[] {WorldFeatures.LIGHTING, WorldFeatures.CULLING,
                WorldFeatures.COLLISION, WorldFeatures.AUDIO, WorldFeatures.CAMERA,
                WorldFeatures.BEHAVIOUR, WorldFeatures.WATER, WorldFeatures.TRIGGER,
                WorldFeatures.SHADOWS, WorldFeatures.BLOOM}) {
            assertThat(line).contains(name + "=off");
        }

        // With nothing set at all, every feature reads on and an absent master
        // prints the default it behaves as.
        Map<String, String> unset = new HashMap<>();
        String defaultLine = WorldFeatures.summary(unset::get);
        assertThat(defaultLine).contains("lg.wonderland.features=on")
                .contains("lighting=on").doesNotContain("=off");
        // and one feature switched off shows up in the line, so the console is
        // evidence of what the JVM read rather than a guess
        Map<String, String> one = new HashMap<>();
        one.put(WorldFeatures.PREFIX + WorldFeatures.AUDIO, "off");
        assertThat(WorldFeatures.summary(one::get))
                .contains("audio=off").contains("lighting=on");
    }
}
