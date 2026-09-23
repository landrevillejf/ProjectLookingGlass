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
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Reads the {@code .lgcfg} application descriptors the 3D start menu is built
 * from, without instantiating any of the beans they describe.
 *
 * <p>The descriptors are {@code java.beans.XMLEncoder} documents; decoding them
 * the way {@code DefaultConfigControl} does would construct the scene-manager
 * classes named in the file (Java 3D included), which is exactly what a machine
 * without 3D cannot do. This reader therefore parses them as plain XML and keeps
 * only the two bean kinds the menu needs - {@code StartMenuGroupConfig} and
 * {@code StartMenuItemConfig} - as inert {@link GroupSpec} / {@link ItemSpec}
 * values. The 2D desktop consequently shows the same applications, in the same
 * groups, in the same order as the 3D start menu.</p>
 *
 * <p>Scanned locations mirror the display server's own discovery: every
 * {@code *.lgcfg} under {@code ${lg.etcdir}/lg3d}, plus the {@code config/demo}
 * and {@code config/incubator} directories on the classpath (where the
 * lg3d-apps and lg3d-incubator jars bundle their descriptors).</p>
 */
public final class Desktop2DMenuConfig {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /** Bean class name suffixes this reader understands. */
    private static final String GROUP_BEAN = "StartMenuGroupConfig";
    private static final String ITEM_BEAN = "StartMenuItemConfig";

    /** Classpath directories holding app descriptors, in scan order. */
    private static final String[] CLASSPATH_CONFIG_DIRS = {
        "config/demo", "config/incubator"
    };

    /** One {@code StartMenuGroupConfig}: a menu folder and its sub-folders. */
    public static final class GroupSpec {
        private final String name;
        private final String desc;
        private final List<String> links;
        private final boolean defaultGroup;

        public GroupSpec(String name, String desc, List<String> links,
                         boolean defaultGroup) {
            this.name = (name == null) ? "" : name;
            this.desc = desc;
            this.links = Collections.unmodifiableList(new ArrayList<>(
                    (links == null) ? Collections.<String>emptyList() : links));
            this.defaultGroup = defaultGroup;
        }

        public String getName() {
            return name;
        }

        public String getDesc() {
            return desc;
        }

        /** Names of the groups this group links to (its sub-menus). */
        public List<String> getLinks() {
            return links;
        }

        /** True if this is the menu's root group. */
        public boolean isDefaultGroup() {
            return defaultGroup;
        }

        @Override
        public String toString() {
            return "GroupSpec[" + name + "]";
        }
    }

    /** One {@code StartMenuItemConfig}: a launchable menu entry. */
    public static final class ItemSpec {
        private final String name;
        private final String command;
        private final String desc;
        private final String menuGroup;
        private final String iconResource;

        public ItemSpec(String name, String command, String desc,
                        String menuGroup, String iconResource) {
            this.name = (name == null) ? "" : name;
            this.command = command;
            this.desc = desc;
            this.menuGroup = menuGroup;
            this.iconResource = iconResource;
        }

        public String getName() {
            return name;
        }

        /** The raw command, e.g. {@code java org.jdesktop...FileManager}. */
        public String getCommand() {
            return command;
        }

        public String getDesc() {
            return desc;
        }

        /** The name of the group this item belongs to. */
        public String getMenuGroup() {
            return menuGroup;
        }

        /**
         * Classpath location of the item's icon with the {@code resource:///}
         * scheme stripped, e.g. {@code resources/images/icon/star.png}, or null.
         */
        public String getIconResource() {
            return iconResource;
        }

        @Override
        public String toString() {
            return "ItemSpec[" + name + " -> " + command + "]";
        }
    }

    /** The groups and items read from every descriptor, in scan order. */
    public static final class MenuModel {
        private final List<GroupSpec> groups;
        private final List<ItemSpec> items;
        private final Map<String, GroupSpec> groupsByName;

        MenuModel(List<GroupSpec> groups, List<ItemSpec> items) {
            this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            Map<String, GroupSpec> byName = new LinkedHashMap<>();
            for (GroupSpec g : this.groups) {
                byName.put(g.getName(), g);   // last definition wins
            }
            this.groupsByName = Collections.unmodifiableMap(byName);
        }

        public List<GroupSpec> getGroups() {
            return groups;
        }

        public List<ItemSpec> getItems() {
            return items;
        }

        /** The group named {@code name}, or null if it is not defined. */
        public GroupSpec getGroup(String name) {
            return (name == null) ? null : groupsByName.get(name);
        }

        /**
         * The root group: the one flagged {@code defaultGroup}, else the group
         * named "Main", else the first group defined, else null.
         */
        public GroupSpec getRootGroup() {
            for (GroupSpec g : groups) {
                if (g.isDefaultGroup()) {
                    return g;
                }
            }
            GroupSpec main = groupsByName.get("Main");
            if (main != null) {
                return main;
            }
            return groups.isEmpty() ? null : groups.get(0);
        }

        /** The sub-menu groups of {@code group}, resolved in declared order. */
        public List<GroupSpec> getLinkedGroups(GroupSpec group) {
            if (group == null) {
                return Collections.emptyList();
            }
            List<GroupSpec> resolved = new ArrayList<>(group.getLinks().size());
            for (String link : group.getLinks()) {
                GroupSpec child = groupsByName.get(link);
                if (child != null) {
                    resolved.add(child);
                }
            }
            return resolved;
        }

        /** The items declared in group {@code groupName}, in descriptor order. */
        public List<ItemSpec> getItemsOf(String groupName) {
            List<ItemSpec> result = new ArrayList<>();
            for (ItemSpec item : items) {
                if (groupName != null && groupName.equals(item.getMenuGroup())) {
                    result.add(item);
                }
            }
            return result;
        }

        /**
         * Items whose {@code menuGroup} names no defined group. The 3D menu
         * silently drops these; the 2D menu appends them to the root so a
         * mis-typed group still leaves the application reachable.
         */
        public List<ItemSpec> getOrphanItems() {
            List<ItemSpec> result = new ArrayList<>();
            for (ItemSpec item : items) {
                if (groupsByName.get(item.getMenuGroup()) == null) {
                    result.add(item);
                }
            }
            return result;
        }

        public boolean isEmpty() {
            return groups.isEmpty() && items.isEmpty();
        }
    }

    private Desktop2DMenuConfig() {
        // no instances
    }

    /**
     * Reads the default descriptor locations (see the class javadoc). A missing
     * location is skipped rather than fatal, so a bare 2D desktop still starts.
     */
    public static MenuModel load() {
        return build(defaultConfigUrls());
    }

    /** Reads the given descriptor URLs, merging them in order. */
    public static MenuModel build(List<URL> configUrls) {
        List<GroupSpec> groups = new ArrayList<>();
        List<ItemSpec> items = new ArrayList<>();
        for (URL url : configUrls) {
            try {
                accumulate(url, groups, items);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Could not read menu descriptor " + url, e);
            }
        }
        logger.log(Level.INFO, "2D start menu: {0} group(s), {1} item(s) from {2} descriptor(s)",
                new Object[] { groups.size(), items.size(), configUrls.size() });
        return new MenuModel(groups, items);
    }

    /** Parses one descriptor, appending its groups and items. */
    private static void accumulate(URL url, List<GroupSpec> groups,
                                   List<ItemSpec> items) throws Exception {
        try (InputStream in = url.openStream()) {
            Document doc = newParser().parse(in);
            Element root = doc.getDocumentElement();
            if (root == null) {
                return;
            }
            NodeList objects = root.getElementsByTagName("object");
            for (int i = 0; i < objects.getLength(); i++) {
                Node node = objects.item(i);
                if (!(node instanceof Element)) {
                    continue;
                }
                Element object = (Element) node;
                String beanClass = object.getAttribute("class");
                if (beanClass == null) {
                    continue;
                }
                if (beanClass.endsWith(GROUP_BEAN)) {
                    groups.add(parseGroup(object));
                } else if (beanClass.endsWith(ITEM_BEAN)) {
                    ItemSpec item = parseItem(object);
                    if (item.getCommand() != null && !item.getCommand().isBlank()) {
                        items.add(item);
                    }
                }
            }
        }
    }

    private static DocumentBuilder newParser() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        // These are 2006-era documents; never reach out for a DTD.
        builder.setEntityResolver((publicId, systemId) ->
                new InputSource(new StringReader("")));
        return builder;
    }

    private static GroupSpec parseGroup(Element object) {
        return new GroupSpec(
                stringProperty(object, "name"),
                stringProperty(object, "desc"),
                stringArrayProperty(object, "groupLinks"),
                Boolean.TRUE.equals(booleanProperty(object, "defaultGroup")));
    }

    private static ItemSpec parseItem(Element object) {
        return new ItemSpec(
                stringProperty(object, "name"),
                stringProperty(object, "command"),
                stringProperty(object, "desc"),
                stringProperty(object, "menuGroup"),
                stripResourceScheme(stringProperty(object, "displayResourceUrlName")));
    }

    /**
     * Turns {@code resource:///resources/images/icon/x.png} into the classpath
     * location {@code resources/images/icon/x.png}. Other forms (absolute http
     * URLs, plain paths) are returned unchanged apart from the scheme handling.
     */
    static String stripResourceScheme(String url) {
        if (url == null) {
            return null;
        }
        String value = url.trim();
        if (value.startsWith("resource://")) {
            value = value.substring("resource://".length());
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value.isEmpty() ? null : value;
    }

    // ------------------------------------------------------------------
    // Descriptor discovery
    // ------------------------------------------------------------------

    /**
     * The default descriptor locations: {@code ${lg.etcdir}/lg3d/*.lgcfg} first
     * (the desktop's own start menu and taskbar definitions), then the
     * classpath-bundled {@code config/demo} and {@code config/incubator} trees.
     */
    static List<URL> defaultConfigUrls() {
        List<URL> urls = new ArrayList<>();
        urls.addAll(etcDirConfigUrls());
        for (String dir : CLASSPATH_CONFIG_DIRS) {
            urls.addAll(classpathConfigUrls(dir));
        }
        return urls;
    }

    private static List<URL> etcDirConfigUrls() {
        String etcDir = System.getProperty("lg.etcdir");
        if (etcDir == null || etcDir.isBlank()) {
            return Collections.emptyList();
        }
        Path dir = Paths.get(etcDir, "lg3d");
        if (!Files.isDirectory(dir)) {
            // lg.etcdir may already point at the lg3d directory itself.
            dir = Paths.get(etcDir);
        }
        if (!Files.isDirectory(dir)) {
            logger.log(Level.WARNING, "No config directory at {0}", dir);
            return Collections.emptyList();
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds =
                     Files.newDirectoryStream(dir, "*.lgcfg")) {
            for (Path p : ds) {
                files.add(p);
            }
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not list " + dir, e);
            return Collections.emptyList();
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        List<URL> urls = new ArrayList<>(files.size());
        for (Path p : files) {
            try {
                urls.add(p.toUri().toURL());
            } catch (Exception e) {
                logger.log(Level.WARNING, "Bad descriptor URL for " + p, e);
            }
        }
        return urls;
    }

    /** The {@code *.lgcfg} files under a classpath directory (dir or jar). */
    static List<URL> classpathConfigUrls(String dir) {
        List<URL> urls = new ArrayList<>();
        ClassLoader cl = Desktop2DMenuConfig.class.getClassLoader();
        try {
            Enumeration<URL> found = cl.getResources(dir);
            while (found.hasMoreElements()) {
                URL url = found.nextElement();
                if ("file".equals(url.getProtocol())) {
                    collectFromDirectory(url, urls);
                } else if ("jar".equals(url.getProtocol())) {
                    collectFromJar(url, dir, urls);
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not scan classpath config dir " + dir, e);
        }
        urls.sort(Comparator.comparing(URL::toString));
        return urls;
    }

    private static void collectFromDirectory(URL url, List<URL> out) {
        try {
            File dir = new File(new URI(url.toString()));
            File[] files = dir.listFiles((d, name) -> name.endsWith(".lgcfg"));
            if (files == null) {
                return;
            }
            List<File> sorted = new ArrayList<>(List.of(files));
            sorted.sort(Comparator.comparing(File::getName));
            for (File f : sorted) {
                out.add(f.toURI().toURL());
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not list config directory " + url, e);
        }
    }

    private static void collectFromJar(URL url, String dir, List<URL> out) {
        try {
            JarURLConnection conn = (JarURLConnection) url.openConnection();
            String prefix = dir.endsWith("/") ? dir : dir + "/";
            List<String> names = new ArrayList<>();
            Enumeration<java.util.jar.JarEntry> entries = conn.getJarFile().entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(prefix) && name.endsWith(".lgcfg")
                        && name.indexOf('/', prefix.length()) < 0) {
                    names.add(name);
                }
            }
            Collections.sort(names);
            for (String name : names) {
                // jar:file:/path/to.jar!/config/demo/app.lgcfg
                out.add(new URL("jar:" + conn.getJarFileURL() + "!/" + name));
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not scan config jar " + url, e);
        }
    }

    // ------------------------------------------------------------------
    // XMLEncoder property readers
    // ------------------------------------------------------------------

    private static Element propertyElement(Element object, String property) {
        NodeList children = object.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element) {
                Element child = (Element) node;
                if ("void".equals(child.getTagName())
                        && property.equals(child.getAttribute("property"))) {
                    return child;
                }
            }
        }
        return null;
    }

    private static Element firstChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element
                    && tagName.equals(((Element) node).getTagName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static String stringProperty(Element object, String property) {
        Element voidElement = propertyElement(object, property);
        if (voidElement == null) {
            return null;
        }
        Element string = firstChildElement(voidElement, "string");
        if (string == null) {
            return null;
        }
        String text = string.getTextContent();
        return (text == null) ? null : text.trim();
    }

    private static Boolean booleanProperty(Element object, String property) {
        Element voidElement = propertyElement(object, property);
        if (voidElement == null) {
            return null;
        }
        Element bool = firstChildElement(voidElement, "boolean");
        if (bool == null) {
            return null;
        }
        String text = bool.getTextContent();
        return (text == null) ? null : Boolean.valueOf(text.trim());
    }

    /** The strings of an {@code <array class="java.lang.String">} property. */
    static List<String> stringArrayProperty(Element object, String property) {
        List<String> values = new ArrayList<>();
        Element voidElement = propertyElement(object, property);
        if (voidElement == null) {
            return values;
        }
        Element array = firstChildElement(voidElement, "array");
        if (array == null) {
            return values;
        }
        // <void index="N"><string>...</string></void>, kept in index order.
        List<Element> slots = new ArrayList<>();
        NodeList children = array.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element
                    && "void".equals(((Element) node).getTagName())) {
                slots.add((Element) node);
            }
        }
        slots.sort(Comparator.comparingInt(e -> {
            try {
                return Integer.parseInt(e.getAttribute("index"));
            } catch (NumberFormatException nfe) {
                return Integer.MAX_VALUE;
            }
        }));
        for (Element slot : slots) {
            Element string = firstChildElement(slot, "string");
            if (string != null) {
                String text = string.getTextContent();
                if (text != null) {
                    values.add(text.trim());
                }
            }
        }
        return values;
    }
}
