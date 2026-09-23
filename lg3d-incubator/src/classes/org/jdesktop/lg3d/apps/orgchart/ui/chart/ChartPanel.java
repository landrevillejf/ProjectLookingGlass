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
package org.jdesktop.lg3d.apps.orgchart.ui.chart;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import org.jdesktop.lg3d.apps.orgchart.framework.contact.Contact;
import org.jdesktop.lg3d.apps.orgchart.framework.contact.PreferenceContactService;

/**
 * The 2D/Swing counterpart of the native-3D {@link Chart3D} organisational-chart
 * browser. It reads the same shared {@code /contacts}
 * {@link java.util.prefs.Preferences} store {@code Contact3D} populates (importing
 * the bundled {@code contacts.xml} on first use, exactly as {@code Contact3D} and
 * {@code ContactDirectory} do) and rebuilds the reporting hierarchy from each
 * contact's {@code manager} attribute, rendering it as a Swing {@link JTree}
 * beside a detail card and a name-query box.
 *
 * <p>Registered in {@code Desktop2DAppRegistry.PANEL_APPS} against the
 * {@code Chart3D} main class, so the one shared start-menu descriptor launches
 * this panel as an MDI internal frame in the 2D/Swing desktop. The panel touches
 * no Java 3D class and does not use the 3D {@code Chart3D}/{@code ContactTree}
 * scene-graph layout: it resolves manager/reports links straight from the
 * Preferences-backed {@code Contact} attributes.</p>
 */
public class ChartPanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 720;
    public static final int HEIGHT_PX = 520;

    private static final String PATH_CONTACTS = PreferenceContactService.DEFAULT_ROOT;
    private static final String CONTACTS_RESOURCE =
            "org/jdesktop/lg3d/apps/orgchart/ui/contact/contacts.xml";

    private static final Logger logger =
            Logger.getLogger(ChartPanel.class.getName());

    private final DefaultMutableTreeNode rootNode =
            new DefaultMutableTreeNode(new Person(null, "Organisation", null, null, null, null));
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final JTree tree = new JTree(treeModel);
    private final JTextField queryField = new JTextField(16);

    private final JLabel detailName = new JLabel(" ");
    private final JLabel detailEmail = new JLabel(" ");
    private final JLabel detailPhone = new JLabel(" ");
    private final JLabel detailLocation = new JLabel(" ");
    private final JLabel detailManager = new JLabel(" ");

    private final List<Person> people = new ArrayList<Person>();
    private final Map<String, DefaultMutableTreeNode> nodesByName =
            new LinkedHashMap<String, DefaultMutableTreeNode>();

    public ChartPanel() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        ensurePopulated();
        loadPeople();
        buildTree();

        add(buildQueryBar(), BorderLayout.NORTH);
        add(new JScrollPane(tree), BorderLayout.CENTER);
        add(buildDetail(), BorderLayout.EAST);

        tree.addTreeSelectionListener(e -> showDetail(selectedPerson()));
        queryField.addActionListener(e -> selectByName(queryField.getText()));
        JButton go = new JButton("Find");
        go.addActionListener(e -> selectByName(queryField.getText()));
        queryBar.add(go);

        tree.setRootVisible(true);
        tree.expandRow(0);
        showDetail(null);
    }

    private JPanel queryBar;

    private JPanel buildQueryBar() {
        queryBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        queryBar.add(new JLabel("Find person:"));
        queryBar.add(queryField);
        return queryBar;
    }

    private JPanel buildDetail() {
        JPanel card = new JPanel(new GridLayout(5, 1, 4, 8));
        card.setPreferredSize(new Dimension(260, HEIGHT_PX));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Details"),
                BorderFactory.createEmptyBorder(8, 10, 10, 10)));
        detailName.setFont(detailName.getFont().deriveFont(Font.BOLD, 16f));
        detailEmail.setFont(wrapFont());
        detailPhone.setFont(wrapFont());
        detailLocation.setFont(wrapFont());
        detailManager.setFont(wrapFont());
        card.add(detailName);
        card.add(detailEmail);
        card.add(detailPhone);
        card.add(detailManager);
        card.add(detailLocation);
        return card;
    }

    private Font wrapFont() {
        return detailEmail.getFont();
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    /** Imports the bundled contacts if the shared node is not there yet. */
    private void ensurePopulated() {
        try {
            if (!Preferences.userRoot().nodeExists(PATH_CONTACTS)) {
                InputStream in = getClass().getClassLoader()
                        .getResourceAsStream(CONTACTS_RESOURCE);
                if (in != null) {
                    Preferences.userRoot().importPreferences(in);
                } else {
                    logger.warning("Bundled contacts.xml not on classpath: "
                            + CONTACTS_RESOURCE);
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error importing shared contacts", e);
        }
    }

    /** Reads every contact node into {@link #people}. */
    private void loadPeople() {
        people.clear();
        try {
            Preferences root = Preferences.userRoot().node(PATH_CONTACTS);
            String[] names = root.childrenNames();
            java.util.Arrays.sort(names);
            for (String name : names) {
                Preferences node = root.node(name);
                String first = node.get(Contact.ATTR_FIRSTNAME, "");
                String last = node.get(Contact.ATTR_LASTNAME, "");
                String display = (first + " " + last).trim();
                if (display.isEmpty()) {
                    display = name;
                }
                people.add(new Person(name, display,
                        node.get(Contact.ATTR_EMAIL, null),
                        node.get(Contact.ATTR_PHONE, null),
                        node.get(Contact.ATTR_LOCATION, null),
                        node.get(Contact.ATTR_MANAGER, null)));
            }
        } catch (BackingStoreException e) {
            logger.log(Level.WARNING, "Error reading shared contacts", e);
        }
    }

    /**
     * Rebuilds the JTree from {@link #people}: a contact whose {@code manager}
     * names another contact becomes that manager's child; everyone else (no
     * manager, or a manager not in the directory) is a top-level root under the
     * invisible "Organisation" node.
     */
    void buildTree() {
        rootNode.removeAllChildren();
        nodesByName.clear();
        Map<String, DefaultMutableTreeNode> byName =
                new LinkedHashMap<String, DefaultMutableTreeNode>();
        for (Person p : people) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(p);
            byName.put(p.name, node);
            nodesByName.put(p.name, node);
        }
        for (Person p : people) {
            DefaultMutableTreeNode node = byName.get(p.name);
            DefaultMutableTreeNode parent =
                    (p.manager == null) ? null : byName.get(p.manager);
            if (parent == null || parent == node) {
                rootNode.add(node);
            } else {
                parent.add(node);
            }
        }
        treeModel.reload();
    }

    /** Selects (and reveals) the first person whose name matches {@code query}. */
    boolean selectByName(String query) {
        if (query == null || query.trim().isEmpty()) {
            return false;
        }
        String q = query.trim().toLowerCase();
        for (Person p : people) {
            if (p.display.toLowerCase().contains(q)) {
                DefaultMutableTreeNode node = nodesByName.get(p.name);
                if (node != null) {
                    TreePath path = new TreePath(node.getPath());
                    tree.setSelectionPath(path);
                    tree.expandPath(path);
                    tree.scrollPathToVisible(path);
                    showDetail(p);
                }
                return true;
            }
        }
        return false;
    }

    private Person selectedPerson() {
        DefaultMutableTreeNode node =
                (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
        if (node == null) {
            return null;
        }
        Object user = node.getUserObject();
        return (user instanceof Person) ? (Person) user : null;
    }

    void showDetail(Person p) {
        if (p == null || p.name == null) {
            detailName.setText(" ");
            detailEmail.setText(" ");
            detailPhone.setText(" ");
            detailLocation.setText(" ");
            detailManager.setText(" ");
            return;
        }
        detailName.setText(p.display);
        detailEmail.setText(field("e-mail", p.email));
        detailPhone.setText(field("phone", p.phone));
        detailManager.setText(field("manager", p.manager));
        detailLocation.setText(field("location", p.location));
    }

    private static String field(String label, String value) {
        return label + ": " + ((value == null || value.isEmpty()) ? "(none)" : value);
    }

    // Test/inspection accessors (package-private; the desktop does not use them).
    List<Person> getPeople() {
        return people;
    }

    String getDetailName() {
        return detailName.getText();
    }

    int getPersonCount() {
        return people.size();
    }

    DefaultMutableTreeNode getRootNode() {
        return rootNode;
    }

    /** One contact's directory attributes, as read from the shared store. */
    static final class Person {
        final String name;      // the /contacts child node name (may be null for the root)
        final String display;   // "givenName sn" (or the node name)
        final String email;
        final String phone;
        final String location;
        final String manager;   // node name of this person's manager (may be null)

        Person(String name, String display, String email, String phone,
                String location, String manager) {
            this.name = name;
            this.display = display;
            this.email = email;
            this.phone = phone;
            this.location = location;
            this.manager = manager;
        }

        public String toString() {
            return display;
        }
    }
}
