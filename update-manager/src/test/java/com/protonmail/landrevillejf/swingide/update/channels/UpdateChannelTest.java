package com.protonmail.landrevillejf.swingide.update.channels;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class UpdateChannelTest {
    
    @Test
    void testStableChannel() {
        UpdateChannel channel = UpdateChannel.STABLE;
        
        assertThat(channel.getDisplayName()).isEqualTo("Stable");
        assertThat(channel.getDescription()).contains("Production");
        assertThat(channel.getCheckIntervalMs()).isGreaterThan(0);
    }
    
    @Test
    void testBetaChannel() {
        UpdateChannel channel = UpdateChannel.BETA;
        
        assertThat(channel.getDisplayName()).isEqualTo("Beta");
        assertThat(channel.getDescription()).contains("Pre-release");
        assertThat(channel.getCheckIntervalMs())
            .isLessThan(UpdateChannel.STABLE.getCheckIntervalMs());
    }
    
    @Test
    void testNightlyChannel() {
        UpdateChannel channel = UpdateChannel.NIGHTLY;
        
        assertThat(channel.getDisplayName()).isEqualTo("Nightly");
        assertThat(channel.getDescription()).contains("Daily");
        assertThat(channel.getCheckIntervalMs())
            .isLessThan(UpdateChannel.BETA.getCheckIntervalMs());
    }
    
    @Test
    void testParseStable() {
        UpdateChannel channel = UpdateChannel.parse("stable");
        assertThat(channel).isEqualTo(UpdateChannel.STABLE);
    }
    
    @Test
    void testParseBeta() {
        UpdateChannel channel = UpdateChannel.parse("BETA");
        assertThat(channel).isEqualTo(UpdateChannel.BETA);
    }
    
    @Test
    void testParseInvalidDefaultsToStable() {
        UpdateChannel channel = UpdateChannel.parse("invalid");
        assertThat(channel).isEqualTo(UpdateChannel.STABLE);
    }
    
    @Test
    void testParseNullAndBlankDefaultToStable() {
        assertThat(UpdateChannel.parse(null)).isEqualTo(UpdateChannel.STABLE);
        assertThat(UpdateChannel.parse("   ")).isEqualTo(UpdateChannel.STABLE);
    }
    
    @Test
    void testParseTrimsAndIgnoresCase() {
        assertThat(UpdateChannel.parse(" NiGhTly ")).isEqualTo(UpdateChannel.NIGHTLY);
    }
    
    @Test
    void testConfigValues() {
        assertThat(UpdateChannel.STABLE.getConfigValue()).isEqualTo("stable");
        assertThat(UpdateChannel.BETA.getConfigValue()).isEqualTo("beta");
        assertThat(UpdateChannel.NIGHTLY.getConfigValue()).isEqualTo("nightly");
    }
    
    @Test
    void testConfigValueRoundTrips() {
        for (UpdateChannel channel : UpdateChannel.values()) {
            assertThat(UpdateChannel.parse(channel.getConfigValue())).isEqualTo(channel);
        }
    }
    
    @Test
    void testStableAcceptsOnlyFinalReleases() {
        assertThat(UpdateChannel.STABLE.accepts("1.2.3")).isTrue();
        assertThat(UpdateChannel.STABLE.accepts("1.2.3-beta1")).isFalse();
        assertThat(UpdateChannel.STABLE.accepts("1.2.3-RC2")).isFalse();
        assertThat(UpdateChannel.STABLE.accepts("1.2.3-alpha")).isFalse();
        assertThat(UpdateChannel.STABLE.accepts("0.5.1-SNAPSHOT")).isFalse();
    }
    
    @Test
    void testBetaAcceptsFinalAndPreReleases() {
        assertThat(UpdateChannel.BETA.accepts("1.2.3")).isTrue();
        assertThat(UpdateChannel.BETA.accepts("1.2.3-beta1")).isTrue();
        assertThat(UpdateChannel.BETA.accepts("1.2.3-rc2")).isTrue();
        assertThat(UpdateChannel.BETA.accepts("1.2.3-alpha")).isFalse();
    }
    
    @Test
    void testNightlyAcceptsEveryBuild() {
        assertThat(UpdateChannel.NIGHTLY.accepts("1.2.3")).isTrue();
        assertThat(UpdateChannel.NIGHTLY.accepts("1.2.3-alpha")).isTrue();
        assertThat(UpdateChannel.NIGHTLY.accepts("1.2.3-beta1")).isTrue();
        assertThat(UpdateChannel.NIGHTLY.accepts("0.5.1-SNAPSHOT")).isTrue();
    }
    
    @Test
    void testAcceptsRejectsUnusableVersions() {
        assertThat(UpdateChannel.NIGHTLY.accepts(null)).isFalse();
        assertThat(UpdateChannel.NIGHTLY.accepts("")).isFalse();
        assertThat(UpdateChannel.NIGHTLY.accepts("not-a-version")).isFalse();
    }
}
