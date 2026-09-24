/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package com.protonmail.landrevillejf.swingide.update.ui;

import com.protonmail.landrevillejf.swingide.update.channels.UpdateChannel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings form is a plain {@code JPanel}, so it can be exercised headless.
 * Only the dialog wrapper needs a display, and its headless guard is asserted
 * separately.
 */
class UpdateSettingsPanelTest {

    private UpdateSettingsPanel panel;

    @BeforeEach
    void setUp() {
        panel = new UpdateSettingsPanel();
    }

    @Test
    void testDefaultsMatchThePackagedConfiguration() {
        assertThat(panel.isUpdateEnabled()).isTrue();
        assertThat(panel.isAutoDownloadEnabled()).isFalse();
        assertThat(panel.isAutoInstallEnabled()).isFalse();
        assertThat(panel.isNotifyOnStartupEnabled()).isTrue();
        assertThat(panel.isTrayNotificationsEnabled()).isTrue();
        assertThat(panel.getSelectedChannel()).isEqualTo(UpdateChannel.STABLE);
        assertThat(panel.getCheckIntervalHours()).isEqualTo(24);
    }

    @Test
    void testLoadFromReflectsEverySetting() {
        Properties properties = new Properties();
        properties.setProperty("update.enabled", "false");
        properties.setProperty("update.auto.download", "true");
        properties.setProperty("update.auto.install", "true");
        properties.setProperty("update.notify.on.startup", "false");
        properties.setProperty("update.notifications.tray.enabled", "false");
        properties.setProperty("update.channel", "nightly");
        properties.setProperty("update.check.interval.hours", "6");

        panel.loadFrom(properties);

        assertThat(panel.isUpdateEnabled()).isFalse();
        assertThat(panel.isAutoDownloadEnabled()).isTrue();
        assertThat(panel.isAutoInstallEnabled()).isTrue();
        assertThat(panel.isNotifyOnStartupEnabled()).isFalse();
        assertThat(panel.isTrayNotificationsEnabled()).isFalse();
        assertThat(panel.getSelectedChannel()).isEqualTo(UpdateChannel.NIGHTLY);
        assertThat(panel.getCheckIntervalHours()).isEqualTo(6);
    }

    @Test
    void testLoadFromIgnoresNullAndBlankValues() {
        panel.setUpdateChannel("beta");
        panel.setCheckIntervalHours(12);

        panel.loadFrom(null);

        assertThat(panel.getSelectedChannel()).isEqualTo(UpdateChannel.BETA);
        assertThat(panel.getCheckIntervalHours()).isEqualTo(12);

        Properties blankInterval = new Properties();
        blankInterval.setProperty("update.check.interval.hours", "   ");
        panel.loadFrom(blankInterval);

        // An empty interval means "use the channel default": the spinner is left alone.
        assertThat(panel.getCheckIntervalHours()).isEqualTo(12);
    }

    @Test
    void testLoadFromIgnoresAnUnparsableInterval() {
        panel.setCheckIntervalHours(48);

        Properties invalid = new Properties();
        invalid.setProperty("update.check.interval.hours", "soon");
        panel.loadFrom(invalid);

        assertThat(panel.getCheckIntervalHours()).isEqualTo(48);
    }

    @Test
    void testApplyToWritesEverySetting() {
        panel.setUpdateEnabled(false);
        panel.setAutoDownloadEnabled(true);
        panel.setAutoInstallEnabled(true);
        panel.setNotifyOnStartupEnabled(false);
        panel.setTrayNotificationsEnabled(false);
        panel.setUpdateChannel("beta");
        panel.setCheckIntervalHours(3);

        Properties written = panel.applyTo(new Properties());

        assertThat(written)
            .containsEntry("update.enabled", "false")
            .containsEntry("update.auto.download", "true")
            .containsEntry("update.auto.install", "true")
            .containsEntry("update.notify.on.startup", "false")
            .containsEntry("update.notifications.tray.enabled", "false")
            .containsEntry("update.channel", "beta")
            .containsEntry("update.check.interval.hours", "3");
    }

    @Test
    void testApplyToCreatesTheConfigurationWhenNull() {
        Properties written = panel.applyTo(null);

        assertThat(written).isNotNull();
        assertThat(written).containsEntry("update.channel", "stable");
    }

    @Test
    void testApplyToKeepsUnrelatedKeys() {
        Properties configuration = new Properties();
        configuration.setProperty("update.url", "https://example.com/version.json");
        configuration.setProperty("update.downgrade.keep", "5");

        Properties written = panel.applyTo(configuration);

        assertThat(written)
            .containsEntry("update.url", "https://example.com/version.json")
            .containsEntry("update.downgrade.keep", "5");
    }

    @Test
    void testRoundTripPreservesTheSelection() {
        panel.setUpdateChannel("nightly");
        panel.setCheckIntervalHours(1);
        panel.setTrayNotificationsEnabled(false);

        UpdateSettingsPanel reloaded = new UpdateSettingsPanel();
        reloaded.loadFrom(panel.applyTo(new Properties()));

        assertThat(reloaded.getSelectedChannel()).isEqualTo(UpdateChannel.NIGHTLY);
        assertThat(reloaded.getUpdateChannel()).isEqualTo("nightly");
        assertThat(reloaded.getCheckIntervalHours()).isEqualTo(1);
        assertThat(reloaded.isTrayNotificationsEnabled()).isFalse();
    }

    @Test
    void testUnknownChannelFallsBackOnStable() {
        panel.setUpdateChannel("experimental");

        assertThat(panel.getSelectedChannel()).isEqualTo(UpdateChannel.STABLE);
        assertThat(panel.getUpdateChannel()).isEqualTo("stable");
    }

    @Test
    void testCreateSettingsPanelFactory() {
        assertThat(UpdateSettingsPanel.createSettingsPanel()).isInstanceOf(UpdateSettingsPanel.class);
    }

    @Test
    void testDialogIsNotShownWhenHeadless() {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless(),
            "The dialog guard is only observable without a display");

        Optional<Properties> result = UpdateSettingsDialog.show(null, new Properties());

        assertThat(result).isEmpty();
    }
}
