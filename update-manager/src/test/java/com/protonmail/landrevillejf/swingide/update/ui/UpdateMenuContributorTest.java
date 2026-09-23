package com.protonmail.landrevillejf.swingide.update.ui;

import com.protonmail.landrevillejf.swingide.update.UpdateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The contributor only creates {@code JMenuItem}s, which need no display, so the
 * whole contract is observable headless.
 */
class UpdateMenuContributorTest {

    private static final String UNAVAILABLE_TOOLTIP = "Update service unavailable";

    private UpdatePresenter presenter;

    @BeforeEach
    void setUp() {
        presenter = mock(UpdatePresenter.class);
        when(presenter.getService()).thenReturn(mock(UpdateService.class));
    }

    @Test
    @DisplayName("The three entries are created in the Help menu order")
    void testCreateMenuItemsBuildsTheThreeEntries() {
        List<JMenuItem> items = UpdateMenuContributor.createMenuItems(presenter);

        assertThat(items).hasSize(3);
        assertThat(items).extracting(JMenuItem::getText).containsExactly(
            UpdateMenuContributor.CHECK_LABEL,
            UpdateMenuContributor.SETTINGS_LABEL,
            UpdateMenuContributor.CHANGELOG_LABEL
        );
        assertThat(items).allSatisfy(item -> assertThat(item.isEnabled()).isTrue());
    }

    @Test
    @DisplayName("Each entry drives the matching presenter action")
    void testTheItemsDriveThePresenter() {
        List<JMenuItem> items = UpdateMenuContributor.createMenuItems(presenter);

        items.get(0).doClick();
        items.get(1).doClick();
        items.get(2).doClick();

        verify(presenter).checkForUpdates();
        verify(presenter).showSettings();
        verify(presenter).showChangelog();
    }

    @Test
    @DisplayName("Without a presenter the entries stay visible but inert")
    void testTheItemsAreDisabledWithoutAPresenter() {
        List<JMenuItem> items = UpdateMenuContributor.createMenuItems(null);

        assertThat(items).hasSize(3);
        assertThat(items).allSatisfy(item -> {
            assertThat(item.isEnabled()).isFalse();
            assertThat(item.getToolTipText()).isEqualTo(UNAVAILABLE_TOOLTIP);
            assertThat(item.getActionListeners()).isEmpty();
        });
    }

    @Test
    @DisplayName("A presenter without a service leaves the entries inert")
    void testTheItemsAreDisabledWithoutAService() {
        List<JMenuItem> items = UpdateMenuContributor.createMenuItems(new UpdatePresenter(null));

        assertThat(items).hasSize(3);
        assertThat(items).allSatisfy(item -> assertThat(item.isEnabled()).isFalse());
    }

    @Test
    @DisplayName("addTo appends the entries after the existing ones")
    void testAddToAppendsTheItemsToAMenu() {
        JMenu help = new JMenu("Help");
        help.add(new JMenuItem("Documentation"));

        UpdateMenuContributor.addTo(help, presenter);

        assertThat(help.getItemCount()).isEqualTo(4);
        assertThat(help.getItem(1).getText()).isEqualTo(UpdateMenuContributor.CHECK_LABEL);
        assertThat(help.getItem(3).getText()).isEqualTo(UpdateMenuContributor.CHANGELOG_LABEL);
    }

    @Test
    @DisplayName("A missing menu is ignored")
    void testAddToIgnoresAMissingMenu() {
        assertThatCode(() -> UpdateMenuContributor.addTo(null, presenter))
            .doesNotThrowAnyException();
    }
}
