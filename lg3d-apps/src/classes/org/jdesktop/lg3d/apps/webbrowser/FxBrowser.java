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

import java.io.IOException;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.concurrent.Worker;
import javafx.scene.layout.StackPane;
import javafx.scene.web.PopupFeatures;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebHistory;
import javafx.scene.web.WebView;
import javafx.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The JavaFX half of the browser: one {@link WebView} (WebKit) per tab, all
 * created and driven on the JavaFX Application Thread. It owns the tab list
 * (the {@link TabModel}) and the parallel {@code WebView} map, wires every
 * engine listener (location, title, load progress/state, popups), applies the
 * {@link BrowserSettings} (JavaScript, user agent, cookies, zoom) and offers
 * best-effort downloads.
 *
 * <p>This is the only place in the app that touches {@code javafx.scene.web},
 * and it is never constructed in the 3D desktop JVM: there the pure-Swing
 * {@link BrowserPreviewPanel} is shown instead and the real browser is spawned
 * into its own child-process JVM (see {@link WebBrowser}), so JavaFX never
 * shares a process with the Java&nbsp;3D OpenGL context.</p>
 *
 * <p>Every mutating method marshals its work through {@link Platform#runLater}
 * so the Swing {@link BrowserPanel} can call them freely from the EDT. State
 * changes are reported back through the {@link Listener}, which fires on the
 * JavaFX thread; the panel re-marshals to the EDT.</p>
 */
public final class FxBrowser {

    private static final Logger LOG = LoggerFactory.getLogger(FxBrowser.class);

    /** Zoom limits applied to the active {@link WebView}. */
    private static final double MIN_ZOOM = 0.25d;
    private static final double MAX_ZOOM = 4.0d;
    private static final double ZOOM_STEP = 1.25d;

    /** File extensions treated as a direct download rather than a page. */
    private static final String[] DOWNLOAD_EXTENSIONS = {
        ".zip", ".gz", ".tgz", ".bz2", ".xz", ".7z", ".rar", ".tar",
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".odt",
        ".ods", ".odp", ".exe", ".msi", ".dmg", ".deb", ".rpm", ".jar",
        ".iso", ".bin", ".apk", ".mp3", ".mp4", ".avi", ".mkv", ".mov",
        ".wav", ".flac", ".ogg", ".webm", ".csv"
    };

    /** Receives state changes, always on the JavaFX Application Thread. */
    public interface Listener {
        /** The tab list changed (added/closed/selected/retitled). */
        default void onTabsChanged() { }

        /** The active tab's committed location changed. */
        default void onLocationChanged(String url) { }

        /** A page finished loading; the URL should be recorded in history. */
        default void onPageCommitted(String url, String title) { }

        /** Load progress in {@code [0,1]}, or {@code -1} when idle. */
        default void onProgress(double progress) { }

        /** Transient status text (hover link, load message). */
        default void onStatusMessage(String message) { }

        /** Back/forward availability changed for the active tab. */
        default void onNavigationStateChanged(boolean canBack, boolean canForward) { }

        /** A download record changed state. */
        default void onDownloadChanged(DownloadRecord record) { }
    }

    private final TabModel model = new TabModel();
    private final Map<Integer, WebView> views = new java.util.HashMap<>();
    private final StackPane root = new StackPane();
    private final Listener listener;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private BrowserSettings settings;
    private Path downloadDir;
    private boolean shuttingDown;

    /**
     * Creates the controller. Call {@link #createRoot()} on the JavaFX thread
     * afterwards to build the scene graph.
     *
     * @param listener the panel to notify (may be null)
     * @param settings the initial settings (null uses defaults)
     */
    public FxBrowser(Listener listener, BrowserSettings settings) {
        this.listener = (listener == null) ? new Listener() { } : listener;
        this.settings = (settings == null) ? new BrowserSettings() : settings;
        this.downloadDir = resolveDownloadDir(this.settings.getDownloadDir());
        installCookiePolicy(this.settings);
    }

    // ------------------------------------------------------------------
    // Scene graph (JavaFX thread)
    // ------------------------------------------------------------------

    /**
     * Builds and returns the scene root. Must be called on the JavaFX
     * Application Thread.
     *
     * @return the pane that displays the active tab
     */
    public StackPane createRoot() {
        requireFxThread();
        return root;
    }

    /** @return the authoritative tab model (read on the FX thread). */
    public TabModel getModel() {
        return model;
    }

    /** @return a snapshot of the tab labels, in strip order. */
    public List<TabModel.Tab> getTabs() {
        return model.list();
    }

    /** @return the active tab index, or -1. */
    public int getActiveIndex() {
        return model.getActiveIndex();
    }

    // ------------------------------------------------------------------
    // Tab management (thread-safe)
    // ------------------------------------------------------------------

    /** Opens a new tab and loads {@code url} (or the home page when null). */
    public void newTab(String url) {
        runOnFx(() -> {
            int index = model.addTab("New Tab", null);
            TabModel.Tab tab = model.getTab(index);
            WebView view = createView();
            views.put(tab.getId(), view);
            showActive();
            loadInternal(targetUrl(url));
            fireTabsChanged();
        });
    }

    /** Closes the tab at {@code index}; closing the last tab is ignored. */
    public void closeTab(int index) {
        runOnFx(() -> {
            TabModel.Tab tab = model.getTab(index);
            if (tab == null || model.size() <= 1) {
                return;
            }
            WebView view = views.remove(tab.getId());
            if (view != null) {
                view.getEngine().loadContent("");
                root.getChildren().remove(view);
            }
            model.closeTab(index);
            showActive();
            fireTabsChanged();
            fireNavigationState();
            fireLocation();
        });
    }

    /** Selects the tab at {@code index}. */
    public void selectTab(int index) {
        runOnFx(() -> {
            if (model.selectTab(index)) {
                showActive();
                fireTabsChanged();
                fireNavigationState();
                fireLocation();
            }
        });
    }

    // ------------------------------------------------------------------
    // Navigation (thread-safe)
    // ------------------------------------------------------------------

    /** Loads {@code url} in the active tab (creating one if none exists). */
    public void load(String url) {
        runOnFx(() -> {
            if (model.isEmpty()) {
                int index = model.addTab("New Tab", null);
                WebView view = createView();
                views.put(model.getTab(index).getId(), view);
                showActive();
            }
            loadInternal(targetUrl(url));
        });
    }

    /** Navigates back one entry, if possible. */
    public void back() {
        runOnFx(() -> { WebHistory h = activeHistory(); if (h != null) h.go(-1); });
    }

    /** Navigates forward one entry, if possible. */
    public void forward() {
        runOnFx(() -> { WebHistory h = activeHistory(); if (h != null) h.go(1); });
    }

    /** Reloads the active tab. */
    public void reload() {
        runOnFx(() -> { WebEngine e = activeEngine(); if (e != null) e.reload(); });
    }

    /** Stops the active tab's load. */
    public void stop() {
        // WebEngine.stop() is private; cancelling the LoadWorker is the public
        // equivalent and stops the in-flight navigation.
        runOnFx(() -> { WebEngine e = activeEngine(); if (e != null) e.getLoadWorker().cancel(); });
    }

    /** Loads the configured home page. */
    public void home() {
        load(settings.getHomePage());
    }

    /** @return true when the active tab can go back. */
    public boolean canGoBack() {
        WebHistory h = activeHistory();
        return h != null && h.getCurrentIndex() > 0;
    }

    /** @return true when the active tab can go forward. */
    public boolean canGoForward() {
        WebHistory h = activeHistory();
        if (h == null) {
            return false;
        }
        return h.getCurrentIndex() < h.getEntries().size() - 1;
    }

    /** @return the active tab's current location, or "" (FX thread). */
    public String getLocation() {
        WebEngine e = activeEngine();
        return (e == null || e.getLocation() == null) ? "" : e.getLocation();
    }

    // ------------------------------------------------------------------
    // Page tools (thread-safe)
    // ------------------------------------------------------------------

    /** Highlights the next occurrence of {@code text} on the active page. */
    public void find(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String escaped = text.replace("\\", "\\\\").replace("'", "\\'");
        runOnFx(() -> {
            WebEngine e = activeEngine();
            if (e != null) {
                e.executeScript("window.find('" + escaped + "', false, false, true)");
            }
        });
    }

    /** Opens the active page's markup as plain text in a new tab. */
    public void viewSource() {
        runOnFx(() -> {
            WebEngine e = activeEngine();
            if (e == null) {
                return;
            }
            Object html;
            try {
                html = e.executeScript("document.documentElement.outerHTML");
            } catch (RuntimeException ex) {
                html = null;
            }
            String source = (html == null) ? "(source unavailable)" : html.toString();
            int index = model.addTab("Source: " + safeTitle(e), e.getLocation());
            WebView view = createView();
            views.put(model.getTab(index).getId(), view);
            showActive();
            view.getEngine().loadContent(source, "text/plain");
            fireTabsChanged();
        });
    }

    /** Zooms the active tab in by one step. */
    public void zoomIn() {
        runOnFx(() -> adjustZoom(ZOOM_STEP));
    }

    /** Zooms the active tab out by one step. */
    public void zoomOut() {
        runOnFx(() -> adjustZoom(1.0d / ZOOM_STEP));
    }

    /** Resets the active tab's zoom to 100%. */
    public void zoomReset() {
        runOnFx(() -> {
            WebView v = activeView();
            if (v != null) {
                v.setZoom(1.0d);
                settings.setZoom(1.0d);
            }
        });
    }

    /** @return the active tab's zoom (FX thread), or 1.0. */
    public double getZoom() {
        WebView v = activeView();
        return (v == null) ? 1.0d : v.getZoom();
    }

    /**
     * Applies new settings to every open engine (JavaScript, user agent,
     * cookies, home page, download directory).
     *
     * @param next the settings to apply (null is ignored)
     */
    public void applySettings(BrowserSettings next) {
        if (next == null) {
            return;
        }
        runOnFx(() -> {
            this.settings = next;
            this.downloadDir = resolveDownloadDir(next.getDownloadDir());
            installCookiePolicy(next);
            for (WebView v : views.values()) {
                WebEngine e = v.getEngine();
                e.setJavaScriptEnabled(next.isJavaScriptEnabled());
                String ua = next.getUserAgent();
                if (ua != null && !ua.isBlank()) {
                    e.setUserAgent(ua.trim());
                }
            }
        });
    }

    /** @return the settings currently applied. */
    public BrowserSettings getSettings() {
        return settings;
    }

    /** Releases every engine; call when the host window closes. */
    public void shutdown() {
        shuttingDown = true;
        runOnFx(() -> {
            for (WebView v : views.values()) {
                try {
                    v.getEngine().loadContent("");
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
            views.clear();
            root.getChildren().clear();
        });
    }

    /** @return the directory downloads are written to. */
    public Path getDownloadDir() {
        return downloadDir;
    }

    // ------------------------------------------------------------------
    // Internals (JavaFX thread unless noted)
    // ------------------------------------------------------------------

    private WebView createView() {
        WebView view = new WebView();
        view.setContextMenuEnabled(true);
        view.setZoom(settings.getZoom());
        WebEngine engine = view.getEngine();
        engine.setJavaScriptEnabled(settings.isJavaScriptEnabled());
        String ua = settings.getUserAgent();
        if (ua != null && !ua.isBlank()) {
            engine.setUserAgent(ua.trim());
        }
        wire(engine);
        return view;
    }

    private void wire(WebEngine engine) {
        engine.locationProperty().addListener(
                (ObservableValue<? extends String> ov, String oldLoc, String loc) -> {
                    if (!shuttingDown) {
                        listener.onLocationChanged(loc == null ? "" : loc);
                        listener.onStatusMessage(loc == null ? "" : loc);
                    }
                });
        engine.titleProperty().addListener(
                (ObservableValue<? extends String> ov, String oldT, String title) -> {
                    if (!shuttingDown) {
                        updateActiveModel(title, engine.getLocation());
                        fireTabsChanged();
                    }
                });
        Worker<Void> worker = engine.getLoadWorker();
        worker.progressProperty().addListener(
                (ObservableValue<? extends Number> ov, Number o, Number n) -> {
                    if (!shuttingDown) {
                        listener.onProgress(n.doubleValue());
                    }
                });
        worker.messageProperty().addListener(
                (ObservableValue<? extends String> ov, String o, String msg) -> {
                    if (!shuttingDown && msg != null && !msg.isBlank()) {
                        listener.onStatusMessage(msg);
                    }
                });
        worker.stateProperty().addListener(
                (ObservableValue<? extends Worker.State> ov, Worker.State o, Worker.State state) -> {
                    if (shuttingDown) {
                        return;
                    }
                    if (state == Worker.State.SUCCEEDED) {
                        listener.onProgress(-1.0d);
                        String loc = engine.getLocation();
                        String title = engine.getTitle();
                        updateActiveModel(title, loc);
                        listener.onPageCommitted(loc, title);
                        fireTabsChanged();
                        listener.onStatusMessage("");
                    } else if (state == Worker.State.FAILED) {
                        listener.onProgress(-1.0d);
                        listener.onStatusMessage("Failed to load " + engine.getLocation());
                    }
                    fireNavigationState();
                });
        engine.setCreatePopupHandler(new Callback<PopupFeatures, WebEngine>() {
            @Override
            public WebEngine call(PopupFeatures config) {
                // Open popups / target=_blank links in a real new tab instead of
                // a separate window, so the tabbed model stays authoritative.
                int index = model.addTab("New Tab", null);
                WebView view = createView();
                views.put(model.getTab(index).getId(), view);
                showActive();
                fireTabsChanged();
                return view.getEngine();
            }
        });
    }

    private void loadInternal(String url) {
        WebEngine engine = activeEngine();
        if (engine == null || url == null || url.isBlank()) {
            return;
        }
        if (maybeDownload(url)) {
            return;
        }
        engine.load(url);
    }

    private String targetUrl(String url) {
        if (url == null || url.isBlank()) {
            return settings.getHomePage();
        }
        return url;
    }

    private void adjustZoom(double factor) {
        WebView v = activeView();
        if (v == null) {
            return;
        }
        double next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, v.getZoom() * factor));
        v.setZoom(next);
        settings.setZoom(next);
    }

    private void showActive() {
        TabModel.Tab tab = model.getActiveTab();
        WebView active = (tab == null) ? null : views.get(tab.getId());
        root.getChildren().clear();
        if (active != null) {
            root.getChildren().add(active);
        }
    }

    private void updateActiveModel(String title, String url) {
        model.updateActive(title, url);
    }

    private WebView activeView() {
        TabModel.Tab tab = model.getActiveTab();
        return (tab == null) ? null : views.get(tab.getId());
    }

    private WebEngine activeEngine() {
        WebView v = activeView();
        return (v == null) ? null : v.getEngine();
    }

    private WebHistory activeHistory() {
        WebEngine e = activeEngine();
        return (e == null) ? null : e.getHistory();
    }

    private String safeTitle(WebEngine engine) {
        String t = engine.getTitle();
        return (t == null || t.isBlank()) ? engine.getLocation() : t;
    }

    private void fireTabsChanged() {
        listener.onTabsChanged();
    }

    private void fireLocation() {
        listener.onLocationChanged(getLocation());
    }

    private void fireNavigationState() {
        listener.onNavigationStateChanged(canGoBack(), canGoForward());
    }

    // ------------------------------------------------------------------
    // Best-effort downloads
    // ------------------------------------------------------------------

    /**
     * Decides whether {@code url} is a file download rather than a page. JavaFX
     * WebView has no download manager, so a navigation whose path ends in a
     * known binary extension is fetched with the JDK HTTP client and written to
     * the download directory; the result is reported through the listener.
     *
     * @return true when the URL was handled as a download (navigation skipped)
     */
    private boolean maybeDownload(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        String path = lower;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        for (String ext : DOWNLOAD_EXTENSIONS) {
            if (path.endsWith(ext)) {
                startDownload(url);
                return true;
            }
        }
        return false;
    }

    private void startDownload(String url) {
        DownloadRecord record = new DownloadRecord(url, fileNameFor(url));
        listener.onDownloadChanged(record);
        Thread worker = new Thread(() -> runDownload(url, record), "webbrowser-download");
        worker.setDaemon(true);
        worker.start();
    }

    /** Runs off the FX thread: streams the body to disk and updates the record. */
    private void runDownload(String url, DownloadRecord record) {
        try {
            Files.createDirectories(downloadDir);
            Path target = uniquePath(downloadDir.resolve(record.getFileName()));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                record.markFailed("HTTP " + response.statusCode());
                listener.onDownloadChanged(record);
                return;
            }
            try (InputStream in = response.body()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            record.markComplete(target.toString(), Files.size(target));
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            record.markFailed(String.valueOf(e.getMessage()));
            LOG.warn("Download failed for {}", url, e);
        }
        listener.onDownloadChanged(record);
        openWithSystemHandler(record);
    }

    /** Best-effort hand-off to the desktop's file handler (xdg-open on Linux). */
    private void openWithSystemHandler(DownloadRecord record) {
        if (record.getStatus() != DownloadRecord.Status.COMPLETE) {
            return;
        }
        try {
            new ProcessBuilder("xdg-open", record.getPath())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException | RuntimeException e) {
            LOG.debug("Could not hand {} to the system handler", record.getPath(), e);
        }
    }

    // ------------------------------------------------------------------
    // Cookies
    // ------------------------------------------------------------------

    private void installCookiePolicy(BrowserSettings s) {
        CookiePolicy policy = (s.isCookiesEnabled() && !s.isPrivateBrowsing())
                ? CookiePolicy.ACCEPT_ALL
                : CookiePolicy.ACCEPT_NONE;
        CookieHandler.setDefault(new CookieManager(null, policy));
    }

    // ------------------------------------------------------------------
    // Helpers (callable from any thread unless noted)
    // ------------------------------------------------------------------

    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) {
            LOG.warn("FxBrowser scene built off the JavaFX thread");
        }
    }

    static Path resolveDownloadDir(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured.trim());
        }
        String home = System.getProperty("user.home");
        Path downloads = Paths.get(home, "Downloads");
        return Files.isDirectory(downloads) ? downloads : Paths.get(home);
    }

    static String fileNameFor(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        int slash = path.lastIndexOf('/');
        String name = (slash >= 0 && slash < path.length() - 1)
                ? path.substring(slash + 1)
                : "";
        if (name.isBlank()) {
            name = "download";
        }
        // Strip anything unsafe for a file name.
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Path uniquePath(Path candidate) {
        Path out = candidate;
        int i = 1;
        String name = candidate.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = (dot > 0) ? name.substring(0, dot) : name;
        String ext = (dot > 0) ? name.substring(dot) : "";
        while (Files.exists(out)) {
            out = candidate.resolveSibling(base + "-" + (i++) + ext);
        }
        return out;
    }

    /** @return the tab labels as a plain list (for diagnostics). */
    public List<String> tabLabels() {
        List<String> out = new ArrayList<>();
        for (TabModel.Tab tab : model.list()) {
            out.add(tab.label());
        }
        return out;
    }
}
