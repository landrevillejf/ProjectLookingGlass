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
package org.jdesktop.lg3d.apps.webbrowser;

import com.protonmail.landrevillejf.IconManager;
import com.protonmail.landrevillejf.MissingIcon;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.InputMap;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentListener;
import org.jdesktop.lg3d.apps.webbrowser.ext.ExtensionBroker;
import org.jdesktop.lg3d.apps.webbrowser.ext.ExtensionRegistry;
import org.jdesktop.lg3d.apps.webbrowser.ext.ToolbarContribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Swing face of the browser: a tab strip, a navigation toolbar (back /
 * forward / reload / stop / home, a smart address bar, bookmarks, find, zoom,
 * view-source, downloads, history, settings), a {@link JFXPanel} hosting the
 * JavaFX {@link FxBrowser} scene, and a status / progress bar.
 *
 * <p>It has the public no-arg constructor {@code Desktop2DAppRegistry.createPanel}
 * requires, so the 2D/Swing desktop hosts it as an MDI internal frame &mdash;
 * this is where the full interactive browser lives. JavaFX is initialised here
 * (constructing the {@link JFXPanel} boots the toolkit on the EDT) and every
 * {@code javafx.*} operation is marshalled onto the JavaFX Application Thread
 * via {@link Platform#runLater}; state changes come back through the
 * {@link FxBrowser.Listener} and are re-marshalled onto the EDT.</p>
 *
 * <p>In the 3D desktop this class is never loaded &mdash; the desktop shows the
 * pure-Swing {@link BrowserPreviewPanel} and spawns the real browser into a
 * child-process JVM (see {@link WebBrowser}), keeping JavaFX out of the
 * Java&nbsp;3D process.</p>
 */
public class BrowserPanel extends JPanel {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserPanel.class);

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 1024;
    public static final int HEIGHT_PX = 768;

    /** Upper bound on tabs restored from a saved session, to cap startup work. */
    private static final int MAX_RESTORED_TABS = 20;

    /**
     * Square edge (px) the bundled IconManager toolbar glyphs ship at. Only 16
     * and 24 exist; {@code loadIcon} at any other edge silently returns the
     * red-X {@link MissingIcon}, so the toolbar buttons load at 16 (matching
     * {@code RemoteViewerPanel}).
     */
    private static final int ICON_EDGE = 16;

    private final BrowserStore store = new BrowserStore();
    private final BookmarkStore bookmarks = new BookmarkStore(store.loadBookmarks());
    private final HistoryStore history =
            new HistoryStore(store.loadHistory(), store.loadSettings().getHistoryLimit());
    private final List<DownloadRecord> downloads = new ArrayList<>(store.loadDownloads());
    private final BrowserSettings settings = store.loadSettings();

    /** Extension discovery + enable/grant state, and the hook-dispatch broker. */
    private final ExtensionRegistry extensions = new ExtensionRegistry(store);
    private final ExtensionBroker broker = new ExtensionBroker(extensions,
            url -> run(fx -> fx.newTab(url)),
            url -> run(fx -> fx.load(url)),
            this::postStatus);

    private final JFXPanel fxPanel = new JFXPanel();   // boots the FX toolkit on the EDT
    private final JPanel tabStrip = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    private final JButton newTabButton = new JButton("+");
    private final JButton backButton = new JButton("Back");
    private final JButton forwardButton = new JButton("Forward");
    private final JButton reloadButton = new JButton("Reload");
    private final JButton stopButton = new JButton("Stop");
    private final JButton homeButton = new JButton("Home");
    private final JTextField urlField = new JTextField();
    private final JButton goButton = new JButton("Go");
    /** Address-bar security indicator; click opens the site-info popup. */
    private final JButton securityButton = new JButton(" ");
    private final JButton bookmarkButton = new JButton("\u2605");
    private final JButton bookmarksMenuButton = new JButton("Bookmarks");
    private final JButton findButton = new JButton("Find");
    private final JButton readerButton = new JButton("Reader");
    private final JButton zoomOutButton = new JButton("-");
    private final JLabel zoomLabel = new JLabel("100%");
    private final JButton zoomInButton = new JButton("+");
    private final JButton sourceButton = new JButton("Source");
    private final JButton downloadsButton = new JButton("Downloads");
    private final JButton historyButton = new JButton("History");
    private final JButton settingsButton = new JButton("Settings");
    private final JButton extensionsButton = new JButton("Extensions");
    private final JButton closeButton = new JButton("Close");

    private final JLabel securityLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar(0, 100);

    /** Inline find bar (hidden until Ctrl+F / the Find button reveals it). */
    private final JPanel findBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
    private final JTextField findField = new JTextField(18);
    private final JLabel findCountLabel = new JLabel("0 of 0");
    private final JButton findPrevButton = new JButton("\u25B2");
    private final JButton findNextButton = new JButton("\u25BC");
    private final JButton findCloseButton = new JButton("\u00D7");

    /** Per-site blocked-event counters, surfaced in the site-info popup. */
    private final SiteStats siteStats = new SiteStats();
    private volatile String currentUrl = "";

    private Runnable onClose;
    private volatile FxBrowser fx;
    private volatile boolean ready;
    private boolean updatingUrlField;

    /** Builds the browser panel and boots the JavaFX browser asynchronously. */
    public BrowserPanel() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        scanExtensions();
        add(buildTabStripRow(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        progressBar.setPreferredSize(new Dimension(120, 14));
        progressBar.setStringPainted(false);
        updateNavEnabled(false);
        installKeyBindings();
        bootJavaFx();
    }

    /**
     * Discovers extensions (built-ins + {@code ~/.lg3d/webbrowser/extensions}
     * jars), applies persisted enable/grant state and fires the startup hook.
     * Guarded so a bad jar or a throwing extension can never stop the browser
     * from opening.
     */
    private void scanExtensions() {
        try {
            extensions.scan();
        } catch (RuntimeException e) {
            LOG.warn("Extension scan failed; continuing without extensions", e);
        }
    }

    /** Sets the callback invoked when the user presses Close. */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    // ------------------------------------------------------------------
    // Toolbar construction
    // ------------------------------------------------------------------

    /**
     * Applies a bundled IconManager toolbar glyph to {@code button} and clears
     * its text label so the icon stands alone (every toolbar button already
     * carries a tooltip). IconManager is a compile-only dependency the build
     * places on the desktop run classpath; if the jar is absent or the glyph is
     * unknown, {@code loadIcon} throws or returns a {@link MissingIcon} and the
     * button simply keeps its text label, so the toolbar always renders.
     */
    private static void applyIcon(JButton button, IconManager.IconCategory category,
            String glyph) {
        try {
            Icon icon = IconManager.loadIcon(category, glyph, ICON_EDGE, ICON_EDGE);
            if (icon != null && !(icon instanceof MissingIcon)) {
                button.setIcon(icon);
                button.setText("");
            }
        } catch (Throwable t) {
            LOG.debug("Could not load toolbar glyph {}/{}", category, glyph, t);
        }
    }

    private JPanel buildTabStripRow() {
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(buildTabStrip());
        north.add(buildNavBar());
        return north;
    }

    private JScrollPane buildTabStrip() {
        tabStrip.setBackground(new Color(232, 234, 238));
        newTabButton.setToolTipText("New tab");
        applyIcon(newTabButton, IconManager.IconCategory.GENERAL, "Add");
        newTabButton.addActionListener(e -> openNewTab());
        JScrollPane scroll = new JScrollPane(tabStrip,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(new Color(232, 234, 238));
        scroll.setPreferredSize(new Dimension(WIDTH_PX, 34));
        return scroll;
    }

    private JToolBar buildNavBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        backButton.setToolTipText("Back");
        forwardButton.setToolTipText("Forward");
        reloadButton.setToolTipText("Reload");
        stopButton.setToolTipText("Stop loading");
        homeButton.setToolTipText("Home page");
        applyIcon(backButton, IconManager.IconCategory.NAVIGATION, "Back");
        applyIcon(forwardButton, IconManager.IconCategory.NAVIGATION, "Forward");
        applyIcon(reloadButton, IconManager.IconCategory.GENERAL, "Refresh");
        applyIcon(stopButton, IconManager.IconCategory.GENERAL, "Stop");
        applyIcon(homeButton, IconManager.IconCategory.NAVIGATION, "Home");
        backButton.addActionListener(e -> run(fx -> fx.back()));
        forwardButton.addActionListener(e -> run(fx -> fx.forward()));
        reloadButton.addActionListener(e -> run(fx -> fx.reload()));
        stopButton.addActionListener(e -> run(fx -> fx.stop()));
        homeButton.addActionListener(e -> run(fx -> fx.home()));
        bar.add(backButton);
        bar.add(forwardButton);
        bar.add(reloadButton);
        bar.add(stopButton);
        bar.add(homeButton);
        bar.addSeparator();

        securityButton.setToolTipText("Connection security - click for site info");
        securityButton.setFocusable(false);
        securityButton.setMargin(new java.awt.Insets(0, 6, 0, 6));
        securityButton.addActionListener(e -> showSiteInfo());
        urlField.setToolTipText("Enter an address or search terms");
        urlField.addActionListener(e -> navigateTo(urlField.getText()));
        goButton.setToolTipText("Go");
        goButton.addActionListener(e -> navigateTo(urlField.getText()));
        bar.add(securityButton);
        bar.add(urlField);
        bar.add(goButton);
        bar.addSeparator();

        bookmarkButton.setToolTipText("Bookmark this page");
        bookmarkButton.addActionListener(e -> bookmarkCurrent());
        bookmarksMenuButton.setToolTipText("Bookmarks");
        applyIcon(bookmarksMenuButton, IconManager.IconCategory.GENERAL, "Bookmarks");
        bookmarksMenuButton.addActionListener(e -> showBookmarksMenu());
        bar.add(bookmarkButton);
        bar.add(bookmarksMenuButton);
        bar.addSeparator();

        findButton.setToolTipText("Find in page");
        applyIcon(findButton, IconManager.IconCategory.GENERAL, "Find");
        findButton.addActionListener(e -> openFindBar());
        zoomOutButton.setToolTipText("Zoom out");
        zoomInButton.setToolTipText("Zoom in");
        applyIcon(zoomOutButton, IconManager.IconCategory.GENERAL, "ZoomOut");
        applyIcon(zoomInButton, IconManager.IconCategory.GENERAL, "ZoomIn");
        zoomOutButton.addActionListener(e -> run(fx -> { fx.zoomOut(); SwingUtilities.invokeLater(this::refreshZoom); }));
        zoomInButton.addActionListener(e -> run(fx -> { fx.zoomIn(); SwingUtilities.invokeLater(this::refreshZoom); }));
        zoomLabel.setToolTipText("Reset zoom");
        zoomLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                run(fx -> { fx.zoomReset(); SwingUtilities.invokeLater(BrowserPanel.this::refreshZoom); });
            }
        });
        bar.add(findButton);
        bar.add(zoomOutButton);
        bar.add(zoomLabel);
        bar.add(zoomInButton);
        bar.addSeparator();

        readerButton.setToolTipText("Reader mode - clean, ad-free article view");
        readerButton.addActionListener(e -> run(fx -> fx.enterReaderMode()));
        sourceButton.setToolTipText("View page source");
        sourceButton.addActionListener(e -> run(fx -> fx.viewSource()));
        downloadsButton.setToolTipText("Downloads");
        applyIcon(downloadsButton, IconManager.IconCategory.GENERAL, "Save");
        downloadsButton.addActionListener(e -> showDownloads());
        historyButton.setToolTipText("Browsing history");
        applyIcon(historyButton, IconManager.IconCategory.GENERAL, "History");
        historyButton.addActionListener(e -> showHistory());
        settingsButton.setToolTipText("Settings");
        applyIcon(settingsButton, IconManager.IconCategory.GENERAL, "Preferences");
        settingsButton.addActionListener(e -> showSettings());
        extensionsButton.setToolTipText("Manage extensions");
        applyIcon(extensionsButton, IconManager.IconCategory.DEVELOPMENT, "WebComponent");
        extensionsButton.addActionListener(e -> showExtensions());
        bar.add(readerButton);
        bar.add(sourceButton);
        bar.add(downloadsButton);
        bar.add(historyButton);
        bar.add(settingsButton);
        bar.add(extensionsButton);
        bar.addSeparator();

        addToolbarContributions(bar);

        closeButton.setToolTipText("Close the browser");
        closeButton.addActionListener(e -> closeBrowser());
        bar.add(closeButton);
        bar.add(Box.createHorizontalGlue());
        return bar;
    }

    /**
     * Renders one button per {@link ToolbarContribution} from enabled extensions
     * granted TOOLBAR. Contributions run their action on the EDT.
     */
    private void addToolbarContributions(JToolBar bar) {
        List<ToolbarContribution> contributions;
        try {
            contributions = broker.toolbarContributions();
        } catch (RuntimeException e) {
            LOG.warn("Could not collect toolbar contributions", e);
            return;
        }
        if (contributions.isEmpty()) {
            return;
        }
        bar.addSeparator();
        for (ToolbarContribution c : contributions) {
            JButton button = new JButton(c.getLabel());
            button.setToolTipText(c.getTooltip());
            button.addActionListener(e -> {
                Runnable action = c.getOnClick();
                if (action != null) {
                    try {
                        action.run();
                    } catch (RuntimeException ex) {
                        LOG.warn("Toolbar contribution {} failed", c.getId(), ex);
                    }
                }
            });
            bar.add(button);
        }
    }

    private JPanel buildStatusBar() {
        JPanel status = new JPanel(new BorderLayout(6, 0));
        status.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(200, 202, 206)),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        securityLabel.setFont(securityLabel.getFont().deriveFont(Font.BOLD));
        statusLabel.setForeground(new Color(90, 92, 96));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);
        left.add(securityLabel);
        left.add(statusLabel);
        status.add(left, BorderLayout.CENTER);
        status.add(progressBar, BorderLayout.EAST);
        return status;
    }

    /** The WebView host plus the inline find bar docked along its bottom edge. */
    private JPanel buildCenter() {
        JPanel center = new JPanel(new BorderLayout());
        center.add(fxPanel, BorderLayout.CENTER);
        center.add(buildFindBar(), BorderLayout.SOUTH);
        return center;
    }

    private JPanel buildFindBar() {
        findBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(200, 202, 206)),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        findBar.setVisible(false);
        findField.setToolTipText("Find in page (Enter = next, Shift+Enter = previous, Esc = close)");
        findField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                runFind();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                runFind();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                runFind();
            }
        });
        findField.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                int code = e.getKeyCode();
                if (code == java.awt.event.KeyEvent.VK_ENTER) {
                    if (e.isShiftDown()) {
                        run(fx -> fx.findPrev());
                    } else {
                        run(fx -> fx.findNext());
                    }
                    e.consume();
                } else if (code == java.awt.event.KeyEvent.VK_ESCAPE) {
                    closeFindBar();
                    e.consume();
                }
            }
        });
        findCountLabel.setForeground(new Color(90, 92, 96));
        findPrevButton.setToolTipText("Previous match");
        findNextButton.setToolTipText("Next match");
        findCloseButton.setToolTipText("Close find bar");
        applyIcon(findPrevButton, IconManager.IconCategory.NAVIGATION, "Up");
        applyIcon(findNextButton, IconManager.IconCategory.NAVIGATION, "Down");
        findPrevButton.addActionListener(e -> run(fx -> fx.findPrev()));
        findNextButton.addActionListener(e -> run(fx -> fx.findNext()));
        findCloseButton.addActionListener(e -> closeFindBar());
        updateFindCount(0, 0);
        findBar.add(new JLabel("Find:"));
        findBar.add(findField);
        findBar.add(findCountLabel);
        findBar.add(findPrevButton);
        findBar.add(findNextButton);
        findBar.add(findCloseButton);
        return findBar;
    }

    // ------------------------------------------------------------------
    // JavaFX boot + listener
    // ------------------------------------------------------------------

    private void bootJavaFx() {
        List<String> session = settings.isRestoreSession() ? store.loadSession() : new ArrayList<>();
        Platform.runLater(() -> {
            // Tame the JDK-8346250 Linux WebKit WebSocket UnsatisfiedLinkError
            // before any page can trigger it (see WebKitThreadGuard). Installed
            // here, on the JavaFX Application Thread, where the error surfaces.
            WebKitThreadGuard.installOnCurrentThread();
            try {
                FxBrowser browser = new FxBrowser(new PanelListener(), settings);
                browser.setExtensionBroker(broker);
                Scene scene = new Scene(browser.createRoot(), javafx.scene.paint.Color.WHITE);
                fxPanel.setScene(scene);
                fx = browser;
                if (session.isEmpty()) {
                    browser.newTab(settings.getHomePage());
                } else {
                    int restored = 0;
                    for (String url : session) {
                        if (restored >= MAX_RESTORED_TABS) {
                            break;
                        }
                        if (url == null || url.isBlank() || url.startsWith("view-source:")) {
                            continue;
                        }
                        try {
                            browser.newTab(url);
                            restored++;
                        } catch (RuntimeException e) {
                            // One bad saved URL must never abort startup.
                            LOG.warn("Could not restore tab for {}; skipped", url, e);
                        }
                    }
                    if (restored == 0) {
                        browser.newTab(settings.getHomePage());
                    }
                }
                ready = true;
                SwingUtilities.invokeLater(() -> {
                    try {
                        broker.notifyStarted();
                    } catch (RuntimeException e) {
                        LOG.warn("Extension startup hook failed", e);
                    }
                });
            } catch (Throwable t) {
                LOG.error("Could not start the JavaFX browser", t);
                SwingUtilities.invokeLater(() -> statusLabel.setText(
                        "JavaFX unavailable: " + t.getMessage()));
            }
        });
    }

    /** Runs an action against the browser once it is up (FX thread). */
    private void run(java.util.function.Consumer<FxBrowser> action) {
        FxBrowser browser = fx;
        if (!ready || browser == null) {
            return;
        }
        Platform.runLater(() -> {
            try {
                action.accept(browser);
            } catch (RuntimeException e) {
                LOG.warn("Browser action failed", e);
            }
        });
    }

    /** Bridges JavaFX-thread callbacks to the EDT. */
    private final class PanelListener implements FxBrowser.Listener {
        @Override
        public void onTabsChanged() {
            SwingUtilities.invokeLater(() -> { rebuildTabStrip(); persistSession(); });
        }

        @Override
        public void onLocationChanged(String url) {
            SwingUtilities.invokeLater(() -> {
                if (!updatingUrlField) {
                    updatingUrlField = true;
                    urlField.setText(url == null ? "" : url);
                    updatingUrlField = false;
                }
                currentUrl = (url == null) ? "" : url;
                updateSecurity(url);
                // A new document has no find highlights; reset the counter.
                if (findBar.isVisible()) {
                    updateFindCount(0, 0);
                }
            });
        }

        @Override
        public void onPageCommitted(String url, String title) {
            SwingUtilities.invokeLater(() -> {
                history.record(url, title);
                store.saveHistory(history.list(), settings.getHistoryLimit());
                refreshBookmarkStar(url);
            });
        }

        @Override
        public void onProgress(double progress) {
            SwingUtilities.invokeLater(() -> {
                if (progress < 0) {
                    progressBar.setIndeterminate(false);
                    progressBar.setValue(0);
                } else {
                    progressBar.setValue((int) Math.round(progress * 100));
                }
            });
        }

        @Override
        public void onStatusMessage(String message) {
            SwingUtilities.invokeLater(() -> statusLabel.setText(
                    (message == null) ? " " : message));
        }

        @Override
        public void onNavigationStateChanged(boolean canBack, boolean canForward) {
            SwingUtilities.invokeLater(() -> updateNavEnabled(canBack || canForward));
        }

        @Override
        public void onDownloadChanged(DownloadRecord record) {
            SwingUtilities.invokeLater(() -> {
                // Replace any existing row for the same (url, startedAt) so the
                // frequent progress copies update in place instead of piling up.
                int existing = -1;
                for (int i = 0; i < downloads.size(); i++) {
                    DownloadRecord d = downloads.get(i);
                    if (d.getUrl().equals(record.getUrl())
                            && d.getStartedAt() == record.getStartedAt()) {
                        existing = i;
                        break;
                    }
                }
                if (existing >= 0) {
                    downloads.set(existing, record);
                } else {
                    downloads.add(record);
                }
                // Only touch the disk on a terminal state, not on every chunk.
                if (record.getStatus() != DownloadRecord.Status.IN_PROGRESS) {
                    store.saveDownloads(new ArrayList<>(downloads));
                }
                statusLabel.setText(record.getFileName() + " - " + downloadStatusText(record));
            });
        }

        @Override
        public void onLoadFailed(String url, LoadFailure failure) {
            SwingUtilities.invokeLater(() -> {
                currentUrl = (url == null) ? "" : url;
                statusLabel.setText(failure.getTitle());
                updateSecurity(url);
            });
        }

        @Override
        public void onFindResults(int active, int total) {
            SwingUtilities.invokeLater(() -> updateFindCount(active, total));
        }

        @Override
        public void onNavigationBlocked(String url) {
            siteStats.recordNavigationBlocked(url);
            SwingUtilities.invokeLater(() -> statusLabel.setText("Blocked by an extension: " + url));
        }

        @Override
        public void onPopupBlocked(String url) {
            siteStats.recordPopupBlocked(url);
            SwingUtilities.invokeLater(() -> statusLabel.setText("Popup blocked: " + url));
        }
    }

    // ------------------------------------------------------------------
    // Navigation actions (EDT)
    // ------------------------------------------------------------------

    private void navigateTo(String rawText) {
        String url = UrlNormalizer.normalize(rawText, settings.getSearchEngine());
        if (url == null) {
            return;
        }
        run(fx -> fx.load(url));
    }

    private void openNewTab() {
        run(fx -> fx.newTab(settings.getHomePage()));
    }

    private void closeBrowser() {
        persistSession();
        shutdown();
        if (onClose != null) {
            onClose.run();
        }
    }

    // ------------------------------------------------------------------
    // Tab strip (EDT)
    // ------------------------------------------------------------------

    private void rebuildTabStrip() {
        FxBrowser browser = fx;
        tabStrip.removeAll();
        if (browser != null) {
            List<TabModel.Tab> tabs = browser.getTabs();
            int active = browser.getActiveIndex();
            for (int i = 0; i < tabs.size(); i++) {
                final int index = i;
                TabModel.Tab tab = tabs.get(i);
                JButton button = new JButton(truncate(tab.label(), 22));
                button.setToolTipText(tab.getUrl());
                button.setSelected(index == active);
                if (index == active) {
                    button.setFont(button.getFont().deriveFont(Font.BOLD));
                }
                button.addActionListener(e -> run(fx -> fx.selectTab(index)));
                button.addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                        if (SwingUtilities.isMiddleMouseButton(e) && browser.getModel().size() > 1) {
                            run(fx -> fx.closeTab(index));
                        }
                    }
                });
                tabStrip.add(button);
                tabStrip.add(buildTabCloseButton(index, browser.getModel().size() > 1));
            }
        }
        tabStrip.add(newTabButton);
        tabStrip.revalidate();
        tabStrip.repaint();
    }

    /**
     * The little {@code \u00D7} that closes a tab (the tab strip previously only
     * supported middle-click). Disabled on the last remaining tab so the browser
     * always keeps one open, matching {@link FxBrowser#closeTab}.
     */
    private JButton buildTabCloseButton(int index, boolean enabled) {
        JButton close = new JButton("\u00D7");
        close.setToolTipText("Close tab");
        close.setFont(close.getFont().deriveFont(Font.PLAIN, 12f));
        close.setMargin(new java.awt.Insets(0, 4, 0, 4));
        close.setFocusable(false);
        close.setBorderPainted(false);
        close.setContentAreaFilled(false);
        close.setEnabled(enabled);
        close.addActionListener(e -> run(fx -> fx.closeTab(index)));
        return close;
    }

    // ------------------------------------------------------------------
    // Status bar (EDT)
    // ------------------------------------------------------------------

    private void updateNavEnabled(boolean anyHistory) {
        backButton.setEnabled(anyHistory);
        forwardButton.setEnabled(anyHistory);
    }

    private void updateSecurity(String url) {
        SecurityInfo info = SecurityInfo.of(url);
        switch (info.getLevel()) {
            case SECURE:
                setIndicator("\uD83D\uDD12", new Color(0, 128, 0), info.getSummary());
                break;
            case NOT_SECURE:
                setIndicator("\u26A0", new Color(200, 120, 0), info.getSummary());
                break;
            case LOCAL:
            case INTERNAL:
                setIndicator("\u2139", new Color(90, 92, 96), info.getSummary());
                break;
            default:
                setIndicator(" ", Color.DARK_GRAY, "");
                break;
        }
    }

    /** Drives both the address-bar button and the status-bar echo together. */
    private void setIndicator(String glyph, Color color, String summary) {
        String text = (summary == null || summary.isBlank()) ? "Connection security" : summary;
        securityButton.setText(glyph);
        securityButton.setForeground(color);
        securityButton.setToolTipText(text + " - click for site info");
        securityLabel.setText(glyph);
        securityLabel.setForeground(color);
        securityLabel.setToolTipText(text);
    }

    /**
     * The site-info popup: connection security, host/scheme, JavaScript and
     * cookie state, and this site's blocked-navigation / blocked-popup counts.
     */
    private void showSiteInfo() {
        String url = currentUrl;
        SecurityInfo info = SecurityInfo.of(url);
        SiteStats.Counts counts = siteStats.forUrl(url);
        JPopupMenu menu = new JPopupMenu();
        JMenuItemPlaceholder.add(menu, headingFor(info));
        if (!info.getHost().isBlank()) {
            JMenuItemPlaceholder.add(menu, "Host:  " + info.getHost());
        }
        if (!info.getScheme().isBlank()) {
            JMenuItemPlaceholder.add(menu, "Scheme:  " + info.getScheme());
        }
        JMenuItemPlaceholder.add(menu, "JavaScript:  "
                + (settings.isJavaScriptEnabled() ? "enabled" : "disabled"));
        JMenuItemPlaceholder.add(menu, "Cookies:  " + cookieState());
        menu.addSeparator();
        JMenuItemPlaceholder.add(menu, "Navigations blocked:  " + counts.getNavigationsBlocked());
        JMenuItemPlaceholder.add(menu, "Popups blocked:  " + counts.getPopupsBlocked());
        String warning = info.getWarning();
        if (warning != null && !warning.isBlank()) {
            menu.addSeparator();
            JMenuItemPlaceholder.add(menu, warning);
        }
        menu.show(securityButton, 0, securityButton.getHeight());
    }

    private static String headingFor(SecurityInfo info) {
        switch (info.getLevel()) {
            case SECURE:
                return "Secure connection";
            case NOT_SECURE:
                return "Not secure";
            case LOCAL:
                return "Local content";
            case INTERNAL:
                return "Internal page";
            default:
                return "No page loaded";
        }
    }

    private String cookieState() {
        if (settings.isPrivateBrowsing()) {
            return "off (private browsing)";
        }
        return settings.isCookiesEnabled() ? "enabled" : "disabled";
    }

    private void refreshZoom() {
        FxBrowser browser = fx;
        if (browser != null) {
            zoomLabel.setText(Math.round(browser.getZoom() * 100) + "%");
        }
    }

    private void refreshBookmarkStar(String url) {
        boolean marked = url != null && bookmarks.contains(url);
        bookmarkButton.setForeground(marked ? new Color(220, 170, 0) : Color.DARK_GRAY);
    }

    // ------------------------------------------------------------------
    // Bookmarks (EDT)
    // ------------------------------------------------------------------

    private void bookmarkCurrent() {
        String url = urlField.getText();
        if (url == null || url.isBlank()) {
            return;
        }
        if (bookmarks.contains(url)) {
            statusLabel.setText("Already bookmarked");
            return;
        }
        String title = JOptionPane.showInputDialog(this, "Bookmark title:", statusTitle(url));
        if (title == null) {
            return;
        }
        bookmarks.add(title.isBlank() ? statusTitle(url) : title, url);
        store.saveBookmarks(new ArrayList<>(bookmarks.list()));
        refreshBookmarkStar(url);
        statusLabel.setText("Bookmark added");
    }

    private void showBookmarksMenu() {
        JPopupMenu menu = new JPopupMenu();
        List<Bookmark> all = bookmarks.list();
        if (all.isEmpty()) {
            JMenuItemPlaceholder.add(menu, "No bookmarks yet");
        } else {
            for (Bookmark b : all) {
                String folder = b.getFolder();
                String label = (folder == null || folder.isBlank())
                        ? truncate(b.getTitle(), 30)
                        : folder + " / " + truncate(b.getTitle(), 24);
                JMenuItemPlaceholder.addClickable(menu, label, b.getUrl(), this::navigateTo);
            }
        }
        menu.addSeparator();
        JMenuItemPlaceholder.addClickable(menu, "Bookmark this page...", null,
                v -> bookmarkCurrent());
        menu.show(bookmarksMenuButton, 0, bookmarksMenuButton.getHeight());
    }

    // ------------------------------------------------------------------
    // Find / source / history / downloads / settings dialogs (EDT)
    // ------------------------------------------------------------------

    /** Reveals and focuses the inline find bar, re-running any existing query. */
    private void openFindBar() {
        findBar.setVisible(true);
        findBar.revalidate();
        findBar.repaint();
        findField.requestFocusInWindow();
        findField.selectAll();
        String existing = findField.getText();
        if (existing != null && !existing.isBlank()) {
            run(fx -> fx.find(existing));
        }
    }

    /** Hides the find bar and clears any highlights on the page. */
    private void closeFindBar() {
        run(fx -> fx.clearFind());
        findBar.setVisible(false);
        findBar.revalidate();
        findBar.repaint();
        updateFindCount(0, 0);
    }

    /** Re-highlights for the current field text (find-as-you-type). */
    private void runFind() {
        String text = findField.getText();
        if (text == null || text.isBlank()) {
            run(fx -> fx.clearFind());
            updateFindCount(0, 0);
            return;
        }
        run(fx -> fx.find(text));
    }

    private void updateFindCount(int active, int total) {
        findCountLabel.setText(total == 0 ? "0 of 0" : active + " of " + total);
        findNextButton.setEnabled(total > 0);
        findPrevButton.setEnabled(total > 0);
    }

    private void showHistory() {
        List<HistoryEntry> entries = history.list();
        Object[] options = entries.stream()
                .map(h -> h.getTitle().isBlank() ? h.getUrl()
                        : truncate(h.getTitle(), 40) + "  -  " + h.getUrl())
                .toArray();
        if (options.length == 0) {
            JOptionPane.showMessageDialog(this, "No history yet.", "History",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Object chosen = JOptionPane.showInputDialog(this, "Open a page:", "History",
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (chosen != null) {
            int idx = java.util.Arrays.asList(options).indexOf(chosen);
            if (idx >= 0 && idx < entries.size()) {
                navigateTo(entries.get(idx).getUrl());
            }
        }
    }

    private void showDownloads() {
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        if (downloads.isEmpty()) {
            list.add(new JLabel("No downloads."));
        } else {
            // Newest first.
            for (int i = downloads.size() - 1; i >= 0; i--) {
                list.add(buildDownloadRow(downloads.get(i)));
            }
        }
        JLabel dir = new JLabel("Saved to: " + fxDownloadDir());
        dir.setBorder(BorderFactory.createEmptyBorder(8, 4, 0, 4));
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(440, 240));
        scroll.getVerticalScrollBar().setUnitIncrement(12);
        JPanel content = new JPanel(new BorderLayout());
        content.add(scroll, BorderLayout.CENTER);
        content.add(dir, BorderLayout.SOUTH);
        JOptionPane.showMessageDialog(this, content, "Downloads",
                JOptionPane.PLAIN_MESSAGE);
    }

    private JPanel buildDownloadRow(DownloadRecord d) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        JLabel name = new JLabel(truncate(d.getFileName(), 26));
        name.setToolTipText(d.getUrl());
        row.add(name);
        row.add(new JLabel(downloadStatusText(d)));
        if (d.getStatus() == DownloadRecord.Status.IN_PROGRESS) {
            JButton cancel = new JButton("Cancel");
            cancel.addActionListener(e -> run(fx -> fx.cancelDownload(d.getUrl())));
            row.add(cancel);
        }
        return row;
    }

    private String downloadStatusText(DownloadRecord d) {
        switch (d.getStatus()) {
            case COMPLETE:
                return "complete (" + humanBytes(d.getBytes()) + ")";
            case FAILED:
                return "failed: " + (d.getError().isBlank() ? "error" : d.getError());
            case CANCELLED:
                return "cancelled";
            case IN_PROGRESS:
            default:
                long total = d.getTotalBytes();
                if (total > 0) {
                    long pct = Math.min(100L, d.getBytes() * 100L / total);
                    return pct + "% of " + humanBytes(total);
                }
                return humanBytes(d.getBytes());
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return (bytes / 1024L) + " KB";
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String fxDownloadDir() {
        FxBrowser browser = fx;
        return (browser == null) ? FxBrowser.resolveDownloadDir(settings.getDownloadDir())
                .toString() : browser.getDownloadDir().toString();
    }

    private void showSettings() {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        JTextField home = new JTextField(settings.getHomePage(), 28);
        JComboBox<SearchEngine> engine = new JComboBox<>(SearchEngine.values());
        engine.setSelectedItem(settings.getSearchEngine());
        JTextField ua = new JTextField(settings.getUserAgent(), 28);
        JTextField dir = new JTextField(settings.getDownloadDir(), 28);
        JTextField pageTimeout =
                new JTextField(String.valueOf(settings.getPageTimeoutSeconds()), 6);
        JTextField downloadTimeout =
                new JTextField(String.valueOf(settings.getDownloadTimeoutSeconds()), 6);
        JCheckBox js = new JCheckBox("Enable JavaScript", settings.isJavaScriptEnabled());
        JCheckBox cookies = new JCheckBox("Enable cookies", settings.isCookiesEnabled());
        JCheckBox priv = new JCheckBox("Private browsing", settings.isPrivateBrowsing());
        JCheckBox restore = new JCheckBox("Restore session on start", settings.isRestoreSession());

        addRow(form, "Home page:", home);
        addRow(form, "Search engine:", engine);
        addRow(form, "User agent:", ua);
        addRow(form, "Download dir:", dir);
        addRow(form, "Page load timeout (s):", pageTimeout);
        addRow(form, "Download timeout (s):", downloadTimeout);
        form.add(js);
        form.add(cookies);
        form.add(priv);
        form.add(restore);
        form.add(buildPrivacyRow());

        int result = JOptionPane.showConfirmDialog(this, form, "Settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        settings.setHomePage(home.getText());
        settings.setSearchEngine((SearchEngine) engine.getSelectedItem());
        settings.setUserAgent(ua.getText());
        settings.setDownloadDir(dir.getText());
        settings.setPageTimeoutSeconds(
                parseIntOr(pageTimeout.getText(), settings.getPageTimeoutSeconds()));
        settings.setDownloadTimeoutSeconds(
                parseIntOr(downloadTimeout.getText(), settings.getDownloadTimeoutSeconds()));
        settings.setJavaScriptEnabled(js.isSelected());
        settings.setCookiesEnabled(cookies.isSelected());
        settings.setPrivateBrowsing(priv.isSelected());
        settings.setRestoreSession(restore.isSelected());
        store.saveSettings(settings);
        history.setCapacity(settings.getHistoryLimit());
        run(fx -> fx.applySettings(settings));
        statusLabel.setText("Settings saved");
    }

    private void addRow(JPanel form, String label, java.awt.Component field) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.add(new JLabel(label));
        row.add(field);
        form.add(row);
    }

    private static int parseIntOr(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Privacy actions row: clear the stored history and the cookie jar. */
    private JPanel buildPrivacyRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton clearHistory = new JButton("Clear history");
        clearHistory.addActionListener(e -> clearHistory());
        JButton clearCookies = new JButton("Clear cookies");
        clearCookies.addActionListener(e -> clearCookies());
        row.add(clearHistory);
        row.add(clearCookies);
        return row;
    }

    private void clearHistory() {
        int result = JOptionPane.showConfirmDialog(this,
                "Clear all browsing history?", "Clear history",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        history.clear();
        store.saveHistory(history.list(), settings.getHistoryLimit());
        statusLabel.setText("History cleared");
    }

    private void clearCookies() {
        int result = JOptionPane.showConfirmDialog(this,
                "Clear all cookies?", "Clear cookies",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) {
            return;
        }
        run(fx -> fx.clearCookies());
        statusLabel.setText("Cookies cleared");
    }

    /** Opens the modal extension manager (enable/disable, permissions, rescan). */
    private void showExtensions() {
        try {
            new ExtensionManagerDialog(this, extensions).setVisible(true);
        } catch (RuntimeException e) {
            LOG.warn("Could not open the extension manager", e);
            JOptionPane.showMessageDialog(this,
                    "Could not open the extension manager: " + e.getMessage(),
                    "Extensions", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------------------------------------------------
    // Keyboard shortcuts (EDT)
    // ------------------------------------------------------------------

    /**
     * Binds the browser-completeness shortcuts at the window level so they work
     * regardless of which child has focus: Ctrl+T new tab, Ctrl+W close tab,
     * Ctrl+L focus address, Ctrl+R reload, Ctrl+F find, Ctrl+Tab / Ctrl+Shift+Tab
     * cycle tabs.
     */
    private void installKeyBindings() {
        InputMap im = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getActionMap();
        bind(im, am, "ctrl T", "newTab", e -> openNewTab());
        bind(im, am, "ctrl W", "closeTab", e -> closeActiveTab());
        bind(im, am, "ctrl L", "focusAddress", e -> {
            urlField.requestFocusInWindow();
            urlField.selectAll();
        });
        bind(im, am, "ctrl R", "reload", e -> run(fx -> fx.reload()));
        bind(im, am, "ctrl F", "find", e -> openFindBar());
        bind(im, am, "ESCAPE", "closeFind", e -> {
            if (findBar.isVisible()) {
                closeFindBar();
            }
        });
        bind(im, am, "ctrl TAB", "nextTab", e -> cycleTab(1));
        bind(im, am, "ctrl shift TAB", "prevTab", e -> cycleTab(-1));
    }

    private void bind(InputMap im, ActionMap am, String stroke, String key,
                      ActionListener action) {
        im.put(KeyStroke.getKeyStroke(stroke), key);
        am.put(key, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.actionPerformed(e);
            }
        });
    }

    private void closeActiveTab() {
        FxBrowser browser = fx;
        if (browser == null) {
            return;
        }
        int index = browser.getActiveIndex();
        if (index >= 0 && browser.getModel().size() > 1) {
            run(fx -> fx.closeTab(index));
        }
    }

    private void cycleTab(int delta) {
        run(fx -> {
            int n = fx.getModel().size();
            if (n <= 1) {
                return;
            }
            int next = ((fx.getActiveIndex() + delta) % n + n) % n;
            fx.selectTab(next);
        });
    }

    // ------------------------------------------------------------------
    // Persistence (EDT)
    // ------------------------------------------------------------------

    private void persistSession() {
        FxBrowser browser = fx;
        if (browser == null) {
            return;
        }
        List<String> urls = new ArrayList<>();
        for (TabModel.Tab tab : browser.getTabs()) {
            String url = tab.getUrl();
            if (url != null && !url.isBlank() && !url.startsWith("view-source:")) {
                urls.add(url);
            }
        }
        store.saveSession(urls);
    }

    /** Releases the JavaFX engines; call when the host window closes. */
    public void shutdown() {
        persistSession();
        store.saveHistory(history.list(), settings.getHistoryLimit());
        store.saveBookmarks(new ArrayList<>(bookmarks.list()));
        try {
            broker.notifyStopping();
        } catch (RuntimeException e) {
            LOG.warn("Extension shutdown hook failed", e);
        }
        FxBrowser browser = fx;
        if (browser != null) {
            browser.shutdown();
        }
        ready = false;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    /** Posts a status-line message from any thread onto the EDT. */
    private void postStatus(String msg) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(msg));
    }

    private String statusTitle(String url) {
        FxBrowser browser = fx;
        if (browser != null) {
            TabModel.Tab tab = browser.getModel().getActiveTab();
            if (tab != null && !tab.getTitle().isBlank()) {
                return tab.getTitle();
            }
        }
        String host = UrlNormalizer.hostOf(url);
        return host.isBlank() ? url : host;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return (text.length() <= max) ? text : text.substring(0, max - 1) + "\u2026";
    }

    /**
     * Minimal helper for building {@link javax.swing.JMenuItem} entries without
     * a field per item; kept tiny so the menu code above stays readable.
     */
    private static final class JMenuItemPlaceholder {
        static void add(JPopupMenu menu, String text) {
            javax.swing.JMenuItem item = new javax.swing.JMenuItem(text);
            item.setEnabled(false);
            menu.add(item);
        }

        static void addClickable(JPopupMenu menu, String text, String url,
                java.util.function.Consumer<String> onClick) {
            javax.swing.JMenuItem item = new javax.swing.JMenuItem(text);
            ActionListener listener = e -> onClick.accept(url);
            item.addActionListener(listener);
            menu.add(item);
        }
    }
}
