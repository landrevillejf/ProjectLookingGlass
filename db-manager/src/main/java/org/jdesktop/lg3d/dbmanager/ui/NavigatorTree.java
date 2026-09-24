/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
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

import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingWorker;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import org.jdesktop.lg3d.dbmanager.jdbc.MetadataReader;
import org.jdesktop.lg3d.dbmanager.model.ConnectionProfile;
import org.jdesktop.lg3d.dbmanager.session.ConnectionManager;
import org.jdesktop.lg3d.dbmanager.session.DbSession;

/**
 * The database navigator: a lazy {@link JTree} of saved connection profiles that
 * expands into schemas, tables/views and columns on demand.
 *
 * <p>Metadata is fetched off the EDT with a {@link SwingWorker} the first time a
 * node is expanded (a placeholder child marks "not yet loaded"), so opening a
 * profile against a large schema never walks the whole catalog up front and never
 * freezes the UI. All structural mutations happen back on the EDT.</p>
 */
public final class NavigatorTree extends JPanel {

    /** The table a user double-clicked, resolved to its qualified coordinates. */
    public record TableSelection(String catalog, String schema, String name, boolean view) {
    }

    /** Callbacks the navigator needs from the hosting panel. */
    public interface Actions {
        /** @return the live session for a profile id, or {@code null} when not connected. */
        DbSession sessionFor(String profileId);

        /** Invoked on the EDT when the user double-clicks a table/view. */
        void onOpenTable(TableSelection selection);

        /** Invoked on the EDT when the selected profile changes. */
        void onProfileSelected(String profileId);

        /** Appends a line to the message log. */
        void log(String message);
    }

    private static final String PLACEHOLDER = "\u2026";
    private static final String NOT_CONNECTED = "(not connected)";
    private static final String TABLES = "Tables";
    private static final String VIEWS = "Views";

    private final ConnectionManager manager;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Connections");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private Actions actions;

    /**
     * Creates the navigator.
     *
     * @param manager the connection manager supplying profiles and sessions
     */
    public NavigatorTree(ConnectionManager manager) {
        super(new BorderLayout());
        this.manager = manager;
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        add(new JScrollPane(tree), BorderLayout.CENTER);

        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                loadChildren(event.getPath());
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
                // no-op
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    handleDoubleClick();
                }
            }
        });
        tree.addTreeSelectionListener(e -> {
            Object id = selectedProfileId();
            if (actions != null && id != null) {
                actions.onProfileSelected((String) id);
            }
        });
        refresh();
    }

    /** Sets the hosting panel's callbacks. */
    public void setActions(Actions actions) {
        this.actions = actions;
    }

    /** @return the underlying tree (for tests / advanced wiring). */
    public JTree getTree() {
        return tree;
    }

    /** Rebuilds the profile list from the manager, preserving nothing. */
    public void refresh() {
        root.removeAllChildren();
        for (ConnectionProfile p : manager.getProfiles()) {
            boolean connected = manager.isConnected(p.getId());
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(
                    new ProfileUser(p.getId(), p.getName(), connected));
            if (connected) {
                node.add(new DefaultMutableTreeNode(PLACEHOLDER));
            }
            root.add(node);
        }
        model.reload();
        tree.expandPath(new TreePath(root.getPath()));
    }

    /** @return the id of the selected profile node, or {@code null}. */
    public String selectedProfileId() {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        while (node != null) {
            if (node.getUserObject() instanceof ProfileUser pu) {
                return pu.id();
            }
            node = (node.getParent() instanceof DefaultMutableTreeNode p) ? p : null;
        }
        return null;
    }

    private void handleDoubleClick() {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        if (node == null || actions == null) {
            return;
        }
        if (node.getUserObject() instanceof TableUser tu) {
            actions.onOpenTable(new TableSelection(tu.catalog(), tu.schema(), tu.name(), tu.view()));
        }
    }

    // ------------------------------------------------------------------
    // lazy loading
    // ------------------------------------------------------------------

    private void loadChildren(TreePath path) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object user = node.getUserObject();
        if (!needsLoad(node)) {
            return;
        }
        if (user instanceof ProfileUser pu) {
            loadProfile(pu, node);
        } else if (user instanceof SchemaUser su) {
            loadTablesForSchema(su, node);
        } else if (user instanceof TableGroupUser tg) {
            loadTableGroup(tg, node);
        } else if (user instanceof TableUser tu) {
            loadColumns(tu, node);
        }
    }

    private boolean needsLoad(DefaultMutableTreeNode node) {
        return node.getChildCount() == 1
                && PLACEHOLDER.equals(((DefaultMutableTreeNode) node.getChildAt(0)).getUserObject());
    }

    private void clearPlaceholder(DefaultMutableTreeNode node) {
        node.removeAllChildren();
    }

    private DbSession session(String profileId) {
        return (actions != null) ? actions.sessionFor(profileId) : null;
    }

    private void loadProfile(ProfileUser pu, DefaultMutableTreeNode node) {
        String profileId = pu.id();
        loadAsync(() -> {
            DbSession s = session(profileId);
            if (s == null) {
                return (List<String>) null;
            }
            try {
                return s.getMetadata().schemas(s.getConnection(), null);
            } catch (SQLException e) {
                return List.<String>of();
            }
        }, schemas -> {
            clearPlaceholder(node);
            if (schemas == null) {
                node.add(new DefaultMutableTreeNode(NOT_CONNECTED));
            } else if (schemas.isEmpty()) {
                // No schema concept (e.g. SQLite): tables straight under profile.
                addTableGroups(node, profileId, null, null);
            } else {
                for (String schema : schemas) {
                    DefaultMutableTreeNode sn = new DefaultMutableTreeNode(
                            new SchemaUser(profileId, null, schema));
                    sn.add(new DefaultMutableTreeNode(PLACEHOLDER));
                    node.add(sn);
                }
            }
            model.reload(node);
            expand(node);
        });
    }

    private void loadTablesForSchema(SchemaUser su, DefaultMutableTreeNode node) {
        // Expanding a schema only reveals its Tables / Views groups; the actual
        // table list is fetched lazily when one of those groups is expanded.
        clearPlaceholder(node);
        if (session(su.profileId()) == null) {
            node.add(new DefaultMutableTreeNode(NOT_CONNECTED));
        } else {
            addTableGroups(node, su.profileId(), su.catalog(), su.schema());
        }
        model.reload(node);
        expand(node);
    }

    private void addTableGroups(DefaultMutableTreeNode parent, String profileId,
                                String catalog, String schema) {
        DefaultMutableTreeNode tables = new DefaultMutableTreeNode(
                new TableGroupUser(profileId, catalog, schema, false));
        tables.add(new DefaultMutableTreeNode(PLACEHOLDER));
        DefaultMutableTreeNode views = new DefaultMutableTreeNode(
                new TableGroupUser(profileId, catalog, schema, true));
        views.add(new DefaultMutableTreeNode(PLACEHOLDER));
        parent.add(tables);
        parent.add(views);
    }

    private void loadTableGroup(TableGroupUser tg, DefaultMutableTreeNode node) {
        loadAsync(() -> {
            DbSession s = session(tg.profileId());
            if (s == null) {
                return (List<MetadataReader.TableInfo>) null;
            }
            try {
                List<MetadataReader.TableInfo> all =
                        s.getMetadata().tables(s.getConnection(), tg.catalog(), tg.schema());
                return all.stream().filter(t -> t.isView() == tg.views()).toList();
            } catch (SQLException e) {
                if (actions != null) {
                    actions.log("Could not read tables: " + e.getMessage());
                }
                return List.<MetadataReader.TableInfo>of();
            }
        }, tables -> {
            clearPlaceholder(node);
            if (tables == null) {
                node.add(new DefaultMutableTreeNode(NOT_CONNECTED));
            } else if (tables.isEmpty()) {
                node.add(new DefaultMutableTreeNode("(none)"));
            } else {
                for (MetadataReader.TableInfo t : tables) {
                    DefaultMutableTreeNode tn = new DefaultMutableTreeNode(
                            new TableUser(tg.profileId(), t.catalog(), t.schema(), t.name(), t.isView()));
                    tn.add(new DefaultMutableTreeNode(PLACEHOLDER));
                    node.add(tn);
                }
            }
            model.reload(node);
        });
    }

    private void loadColumns(TableUser tu, DefaultMutableTreeNode node) {
        loadAsync(() -> {
            DbSession s = session(tu.profileId());
            if (s == null) {
                return (List<MetadataReader.ColumnInfo>) null;
            }
            try {
                return s.getMetadata().columns(s.getConnection(), tu.catalog(), tu.schema(), tu.name());
            } catch (SQLException e) {
                if (actions != null) {
                    actions.log("Could not read columns: " + e.getMessage());
                }
                return List.<MetadataReader.ColumnInfo>of();
            }
        }, cols -> {
            clearPlaceholder(node);
            if (cols == null) {
                node.add(new DefaultMutableTreeNode(NOT_CONNECTED));
            } else {
                for (MetadataReader.ColumnInfo c : cols) {
                    String detail = (c.primaryKey() ? "PK " : "") + c.typeLabel()
                            + (c.nullable() ? "" : " NOT NULL");
                    node.add(new DefaultMutableTreeNode(new ColumnUser(c.name(), detail)));
                }
            }
            model.reload(node);
        });
    }

    private <T> void loadAsync(Supplier<T> background, Consumer<T> onEdt) {
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() {
                return background.get();
            }

            @Override
            protected void done() {
                try {
                    onEdt.accept(get());
                } catch (Exception e) {
                    if (actions != null) {
                        actions.log("Navigator error: " + e.getMessage());
                    }
                }
            }
        }.execute();
    }

    private void expand(DefaultMutableTreeNode node) {
        tree.expandPath(new TreePath(node.getPath()));
    }

    // ------------------------------------------------------------------
    // node user objects
    // ------------------------------------------------------------------

    record ProfileUser(String id, String name, boolean connected) {
        @Override
        public String toString() {
            return connected ? name + "  \u25CF" : name;
        }
    }

    record SchemaUser(String profileId, String catalog, String schema) {
        @Override
        public String toString() {
            return schema;
        }
    }

    record TableGroupUser(String profileId, String catalog, String schema, boolean views) {
        @Override
        public String toString() {
            return views ? VIEWS : TABLES;
        }
    }

    record TableUser(String profileId, String catalog, String schema, String name, boolean view) {
        @Override
        public String toString() {
            return name;
        }
    }

    record ColumnUser(String name, String detail) {
        @Override
        public String toString() {
            return name + "  " + detail;
        }
    }
}
