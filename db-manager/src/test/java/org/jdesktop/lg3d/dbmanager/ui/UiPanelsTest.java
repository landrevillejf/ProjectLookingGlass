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
package org.jdesktop.lg3d.dbmanager.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.dbmanager.jdbc.ColumnMeta;
import org.jdesktop.lg3d.dbmanager.jdbc.QueryResult;
import org.jdesktop.lg3d.dbmanager.model.AppSettings;
import org.jdesktop.lg3d.dbmanager.model.ConnectionProfile;
import org.jdesktop.lg3d.dbmanager.model.ProfileStore;
import org.jdesktop.lg3d.dbmanager.session.ConnectionManager;
import org.jdesktop.lg3d.dbmanager.session.DbSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless construction and light-interaction tests for the Swing components.
 *
 * <p>The panels must build with {@code java.awt.headless=true} (set by the
 * module's test task) and never touch a display, a database or the user's real
 * config dir in their constructors: modal dialogs are created only on user
 * action, and the manager is backed by a {@link TempDir} store. This is the same
 * contract the desktop relies on when it reflects the panel into an MDI frame.</p>
 */
class UiPanelsTest {

    private static QueryResult sampleResult() {
        List<ColumnMeta> cols = List.of(
                new ColumnMeta("a", "INTEGER", Types.INTEGER, true, "java.lang.Integer", 1));
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of(1));
        rows.add(List.of(2));
        return QueryResult.ofResultSet("SELECT a FROM t", cols, rows, false, 3L);
    }

    /** A Runner whose session can be swapped between null and a live H2 session. */
    private static final class StubRunner implements SqlEditorTab.Runner {
        final List<String> logs = new ArrayList<>();
        final AppSettings settings = new AppSettings();
        DbSession session;

        @Override
        public DbSession activeSession() {
            return session;
        }

        @Override
        public AppSettings settings() {
            return settings;
        }

        @Override
        public void log(String message) {
            logs.add(message);
        }
    }

    @Test
    @DisplayName("ResultsTable builds headless and shows a result then clears it")
    void resultsTable() {
        ResultsTable table = new ResultsTable();
        table.configure(new AppSettings());
        assertThat(table.getResult()).isNull();

        table.setQueryResult(sampleResult());
        assertThat(table.getResult()).isNotNull();
        assertThat(table.getResult().getRowCount()).isEqualTo(2);

        table.setQueryResult(null);
        assertThat(table.getResult()).isNull();
    }

    @Test
    @DisplayName("SqlEditorTab builds headless; run/cancel degrade without a connection")
    void sqlEditorTab() throws Exception {
        StubRunner runner = new StubRunner();
        SqlEditorTab tab = new SqlEditorTab(runner, "SELECT 1");
        assertThat(tab.getSql()).isEqualTo("SELECT 1");
        assertThat(tab.getResults()).isNotNull();

        tab.setSql("SELECT 2");
        assertThat(tab.getSql()).isEqualTo("SELECT 2");
        tab.setSql(null);
        assertThat(tab.getSql()).isEmpty();

        // No active session -> logs a hint and returns without a worker.
        tab.setSql("SELECT 3");
        tab.execute();
        assertThat(runner.logs).anyMatch(s -> s.contains("No active connection"));

        // With a live session, an empty buffer is rejected before any worker runs.
        String url = "jdbc:h2:mem:uitab;DB_CLOSE_DELAY=-1";
        Connection c = DriverManager.getConnection(url, "sa", "");
        ConnectionProfile p = new ConnectionProfile("h2", url);
        p.setDriverId("h2");
        p.setUser("sa");
        DbSession session = new DbSession(c, p, new AppSettings());
        try {
            runner.session = session;
            tab.setSql("   ");
            tab.execute();
            assertThat(runner.logs).anyMatch(s -> s.contains("Nothing to run"));
        } finally {
            runner.session = null;
            session.close();
        }

        // cancel with nothing running is a no-op.
        assertThatCode(tab::cancel).doesNotThrowAnyException();
        assertThatCode(tab::applySettings).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NavigatorTree builds headless and lists saved profiles on refresh")
    void navigatorTree(@TempDir Path dir) {
        ConnectionManager manager = new ConnectionManager(new ProfileStore(dir));
        NavigatorTree nav = new NavigatorTree(manager);
        nav.setActions(new StubActions());
        assertThat(nav.getTree()).isNotNull();
        assertThat(nav.selectedProfileId()).isNull();

        manager.addProfile(new ConnectionProfile("p1", "jdbc:h2:mem:nav"));
        nav.refresh();
        assertThat(nav.getTree().getModel().getRoot()).isNotNull();
        assertThatCode(nav::refresh).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DbManagerMainPanel builds headless at its advertised size; log/dispose are safe")
    void mainPanel(@TempDir Path dir) {
        ConnectionManager manager = new ConnectionManager(new ProfileStore(dir));
        DbManagerMainPanel panel = new DbManagerMainPanel(manager);
        assertThat(panel.getPreferredSize().width).isEqualTo(DbManagerMainPanel.WIDTH_PX);
        assertThat(panel.getPreferredSize().height).isEqualTo(DbManagerMainPanel.HEIGHT_PX);

        assertThatCode(() -> {
            panel.log("hello");
            panel.log(null); // ignored
            panel.dispose();
        }).doesNotThrowAnyException();
    }

    /** Records the callbacks without doing any real work. */
    private static final class StubActions implements NavigatorTree.Actions {
        @Override
        public DbSession sessionFor(String profileId) {
            return null;
        }

        @Override
        public void onOpenTable(NavigatorTree.TableSelection sel) {
            // no-op
        }

        @Override
        public void onProfileSelected(String profileId) {
            // no-op
        }

        @Override
        public void log(String message) {
            // no-op
        }
    }
}
