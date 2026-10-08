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
import java.io.OutputStream;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.concurrent.Worker;
import javafx.scene.layout.StackPane;
import javafx.scene.web.PopupFeatures;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebHistory;
import javafx.scene.web.WebView;
import javafx.util.Callback;
import org.jdesktop.lg3d.apps.webbrowser.ext.ExtensionBroker;
import org.jdesktop.lg3d.apps.webbrowser.ext.NavigationDecision;
import org.jdesktop.lg3d.apps.webbrowser.ext.NavigationRequest;
import org.jdesktop.lg3d.apps.webbrowser.ext.PopupRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The JavaFX half of the browser: one {@link WebView} (WebKit) per tab, all
 * created and driven on the JavaFX Application Thread. It owns the tab list
 * (the {@link TabModel}) and the parallel {@code WebView} map, wires every
 * engine listener (location, title, load progress/state, popups), applies the
 * {@link BrowserSettings} (JavaScript, user agent, cookies, zoom, timeouts) and
 * offers robust, cancellable downloads.
 *
 * <p>All decision logic (failure classification, error-page markup, download
 * naming, find-in-page and reader scripts) lives in JavaFX-free helper classes
 * so it is unit-tested headlessly; this class only marshals threads and drives
 * WebKit. A failed navigation is diagnosed by {@link LoadFailure} and rendered
 * as an honest {@link ErrorPage} instead of a blank tab, and a per-navigation
 * watchdog cancels loads that stall past the configured page timeout.</p>
 *
 * <p>This is the only place in the app that touches {@code javafx.scene.web},
 * and it is never constructed in the 3D desktop JVM: there the pure-Swing
 * {@link BrowserPreviewPanel} is shown instead and the real browser is spawned
 * into its own child-process JVM (see {@link WebBrowser}), so JavaFX never
 * shares a process with the Java&nbsp;3D OpenGL context.</p>
 *
 * <p>Every mutating method marshals its work through {@link Platform#runLater}
 * so the Swing {@link BrowserPanel} can call them freely from the EDT. The
 * read-only navigation getters ({@link #canGoBack()}, {@link #getLocation()},
 * {@link #getZoom()} ...) read a {@code volatile} {@link NavSnapshot} refreshed
 * on the FX thread, so the EDT never reaches into WebKit off-thread. State
 * changes are reported back through the {@link Listener}, which fires on the
 * JavaFX thread; the panel re-marshals to the EDT.</p>
 */
public final class FxBrowser {

    private static final Logger LOG = LoggerFactory.getLogger(FxBrowser.class);

    /** Zoom limits applied to the active {@link WebView}. */
    private static final double MIN_ZOOM = 0.25d;
    private static final double MAX_ZOOM = 4.0d;
    private static final double ZOOM_STEP = 1.25d;

    /** Fixed TCP connect timeout for the download client. */
    private static final int CONNECT_TIMEOUT_SECONDS = 20;
    /** Streaming copy buffer size for downloads. */
    private static final int COPY_BUFFER_BYTES = 8192;
    /** Report download progress at most once per this many bytes. */
    private static final long PROGRESS_REPORT_BYTES = 65536L;

    /** File extensions treated as a direct download rather than a page. */
    private static final String[] DOWNLOAD_EXTENSIONS = {
        ".zip", ".gz", ".tgz", ".bz2", ".xz", ".7z", ".rar", ".tar",
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".odt",
        ".ods", ".odp", ".exe", ".msi", ".dmg", ".deb", ".rpm", ".jar",
        ".iso", ".bin", ".apk", ".mp3", ".mp4", ".avi", ".mkv", ".mov",
        ".wav", ".flac", ".ogg", ".webm", ".csv"
    };

    /**
     * Cheap DOM probe run after a load reports SUCCEEDED. It returns
     * {@code "elementCount:textLength"} without serialising the document: WebKit
     * leaves an empty {@code <html><head></head><body></body></html>} skeleton
     * (three elements, no text) when it discards a body it cannot decode, while
     * any real page -- including a JS-rendered one, whose scripts have already
     * run by SUCCEEDED -- has far more. {@link EncodingFallback} makes the call.
     */
    private static final String BLANK_DOM_PROBE =
            "(function(){var b=document.body;var t=b?(b.textContent||'').trim().length:0;"
            + "return document.getElementsByTagName('*').length+':'+t;})()";

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

        /**
         * A navigation failed; an {@link ErrorPage} has been rendered for it.
         *
         * @param url     the URL that failed (never null, may be empty)
         * @param failure the classified failure (never null)
         */
        default void onLoadFailed(String url, LoadFailure failure) { }

        /**
         * The find-in-page match counts changed.
         *
         * @param active the 1-based index of the focused match, or 0 when none
         * @param total  the total number of matches on the page
         */
        default void onFindResults(int active, int total) { }

        /** An extension blocked a navigation to {@code url}. */
        default void onNavigationBlocked(String url) { }

        /** An extension blocked a popup requested from {@code url}. */
        default void onPopupBlocked(String url) { }
    }

    /**
     * An immutable snapshot of the active tab's navigation state, recomputed on
     * the FX thread so the EDT can read back/forward/location/zoom without
     * touching WebKit off-thread.
     */
    private static final class NavSnapshot {
        static final NavSnapshot EMPTY = new NavSnapshot(false, false, "", 1.0d);

        final boolean canBack;
        final boolean canForward;
        final String location;
        final double zoom;

        NavSnapshot(boolean canBack, boolean canForward, String location, double zoom) {
            this.canBack = canBack;
            this.canForward = canForward;
            this.location = (location == null) ? "" : location;
            this.zoom = zoom;
        }
    }

    private final TabModel model = new TabModel();
    private final Map<Integer, WebView> views = new ConcurrentHashMap<>();
    private final StackPane root = new StackPane();
    private final Listener listener;

    /** Single daemon scheduler that fires per-navigation load timeouts. */
    private final ScheduledExecutorService watchdogScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "webbrowser-watchdog");
                t.setDaemon(true);
                return t;
            });
    private final Map<Integer, ScheduledFuture<?>> watchdogs = new ConcurrentHashMap<>();
    /** Per-tab last failed network URL, so Reload/anchor can retry it. */
    private final Map<Integer, String> failedUrls = new ConcurrentHashMap<>();
    /** Tabs whose next SUCCEEDED is our own error page, not a real navigation. */
    private final Set<Integer> pendingErrorRender = ConcurrentHashMap.newKeySet();
    /** Tabs whose next SUCCEEDED is a blank-page recovery {@code loadContent}. */
    private final Set<Integer> recoveryRender = ConcurrentHashMap.newKeySet();
    /** Tabs that already tried the one-shot blank-page recovery this navigation. */
    private final Set<Integer> blankRecoveryAttempted = ConcurrentHashMap.newKeySet();
    /** In-flight download cancel flags, keyed by URL. */
    private final Map<String, AtomicBoolean> downloadFlags = new ConcurrentHashMap<>();

    private volatile HttpClient httpClient;
    private volatile BrowserSettings settings;
    private volatile Path downloadDir;
    private volatile NavSnapshot nav = NavSnapshot.EMPTY;
    private volatile boolean shuttingDown;
    private volatile ExtensionBroker broker;

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
        this.httpClient = buildHttpClient(this.settings);
        installCookiePolicy(this.settings);
    }

    /**
     * Injects the extension broker that drives the {@code BrowserExtension}
     * hooks. May be null (or set later from the panel); while null, the popup /
     * navigate / page-loaded hooks are simply skipped. Called on the EDT before
     * the first load; the field is volatile so the FX thread sees it.
     *
     * @param broker the broker, or null to disable extension hooks
     */
    public void setExtensionBroker(ExtensionBroker broker) {
        this.broker = broker;
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
            addTabView("New Tab", null);
            loadInternal(targetUrl(url));
        });
    }

    /** Closes the tab at {@code index}; closing the last tab is ignored. */
    public void closeTab(int index) {
        runOnFx(() -> {
            TabModel.Tab tab = model.getTab(index);
            if (tab == null || model.size() <= 1) {
                return;
            }
            int id = tab.getId();
            // Release the tab's async resources before dropping its view.
            disarmWatchdog(id);
            failedUrls.remove(id);
            pendingErrorRender.remove(id);
            recoveryRender.remove(id);
            blankRecoveryAttempted.remove(id);
            WebView view = views.remove(id);
            if (view != null) {
                try {
                    view.getEngine().getLoadWorker().cancel();
                } catch (RuntimeException ignored) {
                    // best effort
                }
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
                addTabView("New Tab", null);
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

    /**
     * Reloads the active tab. When the tab is currently showing an error page,
     * this retries the original failed URL instead of reloading the error card.
     */
    public void reload() {
        runOnFx(() -> {
            int id = activeTabId();
            String failed = (id >= 0) ? failedUrls.remove(id) : null;
            if (failed != null) {
                loadInternal(failed);
                return;
            }
            WebEngine e = activeEngine();
            if (e != null) {
                e.reload();
            }
        });
    }

    /** Stops the active tab's load. */
    public void stop() {
        // WebEngine.stop() is private; cancelling the LoadWorker is the public
        // equivalent and stops the in-flight navigation.
        runOnFx(() -> {
            disarmWatchdog(activeTabId());
            WebEngine e = activeEngine();
            if (e != null) {
                e.getLoadWorker().cancel();
            }
        });
    }

    /** Loads the configured home page. */
    public void home() {
        load(settings.getHomePage());
    }

    /** @return true when the active tab can go back (thread-safe snapshot). */
    public boolean canGoBack() {
        return nav.canBack;
    }

    /** @return true when the active tab can go forward (thread-safe snapshot). */
    public boolean canGoForward() {
        return nav.canForward;
    }

    /** @return the active tab's current location, or "" (thread-safe snapshot). */
    public String getLocation() {
        return nav.location;
    }

    // ------------------------------------------------------------------
    // Page tools (thread-safe)
    // ------------------------------------------------------------------

    /**
     * Highlights every occurrence of {@code text} on the active page and reports
     * the match counts through {@link Listener#onFindResults}. A null/blank
     * query clears the highlights.
     */
    public void find(String text) {
        runOnFx(() -> {
            WebEngine e = activeEngine();
            if (e == null) {
                return;
            }
            exec(e, FindScript.installHelpersScript());
            int[] counts = FindScript.parseCounts(exec(e, FindScript.highlightScript(text)));
            listener.onFindResults(counts[0], counts[1]);
        });
    }

    /** Advances to the next find match (wrapping) and reports the counts. */
    public void findNext() {
        runOnFx(() -> {
            WebEngine e = activeEngine();
            if (e == null) {
                return;
            }
            int[] counts = FindScript.parseCounts(exec(e, FindScript.nextScript()));
            listener.onFindResults(counts[0], counts[1]);
        });
    }

    /** Moves to the previous find match (wrapping) and reports the counts. */
    public void findPrev() {
        runOnFx(() -> {
            WebEngine e = activeEngine();
            if (e == null) {
                return;
            }
            int[] counts = FindScript.parseCounts(exec(e, FindScript.prevScript()));
            listener.onFindResults(counts[0], counts[1]);
        });
    }

    /** Clears all find highlights and resets the reported counts to zero. */
    public void clearFind() {
        runOnFx(() -> {
            WebEngine e = activeEngine();
            if (e == null) {
                return;
            }
            exec(e, FindScript.clearScript());
            listener.onFindResults(0, 0);
        });
    }

    /** Opens the active page's markup as plain text in a new tab. */
    public void viewSource() {
        runOnFx(() -> {
            WebEngine e = activeEngine();
            if (e == null) {
                return;
            }
            String loc = e.getLocation();
            String title = "Source: " + safeTitle(e);
            Object html = exec(e, "document.documentElement.outerHTML");
            String source = (html == null) ? "(source unavailable)" : html.toString();
            WebView view = addTabView(title, loc);
            view.getEngine().loadContent(source, "text/plain");
        });
    }

    /**
     * Extracts the active page's main article and opens it, ad-free, in a new
     * tab (the same pattern as {@link #viewSource()}).
     */
    public void enterReaderMode() {
        runOnFx(() -> {
            WebEngine e = activeEngine();
            if (e == null) {
                return;
            }
            String source = fxLocation();
            Object json = exec(e, ReaderExtractor.EXTRACT_JS);
            ReaderArticle article = ReaderExtractor.parse(json == null ? null : json.toString());
            String t = article.getTitle();
            String label = (t == null || t.isBlank()) ? "Reader" : t;
            WebView view = addTabView(label, source);
            view.getEngine().loadContent(
                    ReaderExtractor.renderHtml(article, source), ReaderExtractor.CONTENT_TYPE);
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
                refreshNav();
            }
        });
    }

    /** @return the active tab's zoom (thread-safe snapshot), or 1.0. */
    public double getZoom() {
        return nav.zoom;
    }

    /**
     * Applies new settings to every open engine (JavaScript, user agent,
     * cookies, home page, download directory, timeouts).
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
            this.httpClient = buildHttpClient(next);
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

    /**
     * Requests cancellation of the in-flight download for {@code url} (a no-op
     * when there is none). The partial file is removed by the download thread.
     */
    public void cancelDownload(String url) {
        if (url == null) {
            return;
        }
        AtomicBoolean flag = downloadFlags.get(url);
        if (flag != null) {
            flag.set(true);
        }
    }

    /** Releases every engine and async resource; call when the host window closes. */
    public void shutdown() {
        shuttingDown = true;
        for (AtomicBoolean flag : downloadFlags.values()) {
            flag.set(true);
        }
        downloadFlags.clear();
        disarmAllWatchdogs();
        watchdogScheduler.shutdownNow();
        runOnFx(() -> {
            for (WebView v : views.values()) {
                try {
                    v.getEngine().getLoadWorker().cancel();
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

    /** Creates a new tab with its view, shows it, and returns the view. */
    private WebView addTabView(String title, String url) {
        int index = model.addTab(title, url);
        TabModel.Tab tab = model.getTab(index);
        WebView view = createView(tab.getId());
        views.put(tab.getId(), view);
        showActive();
        fireTabsChanged();
        return view;
    }

    private WebView createView(int tabId) {
        WebView view = new WebView();
        view.setContextMenuEnabled(true);
        view.setZoom(settings.getZoom());
        WebEngine engine = view.getEngine();
        engine.setJavaScriptEnabled(settings.isJavaScriptEnabled());
        String ua = settings.getUserAgent();
        if (ua != null && !ua.isBlank()) {
            engine.setUserAgent(ua.trim());
        }
        wire(engine, tabId);
        return view;
    }

    private void wire(WebEngine engine, int tabId) {
        engine.locationProperty().addListener(
                (ObservableValue<? extends String> ov, String oldLoc, String loc) -> {
                    if (!shuttingDown) {
                        listener.onLocationChanged(loc == null ? "" : loc);
                        listener.onStatusMessage(loc == null ? "" : loc);
                        refreshNav();
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
                        disarmWatchdog(tabId);
                        listener.onProgress(-1.0d);
                        if (pendingErrorRender.remove(tabId)) {
                            // Our own error page just committed; not a real page.
                            listener.onStatusMessage("");
                        } else if (recoveryRender.remove(tabId)) {
                            // A blank-page recovery loadContent just committed.
                            // loadContent gives the document no location, so keep
                            // the real URL in the address bar, tab and history.
                            String real = failedUrls.get(tabId);
                            String title = engine.getTitle();
                            if (real != null) {
                                updateActiveModel(title, real);
                                listener.onLocationChanged(real);
                                listener.onPageCommitted(real, title);
                            }
                            fireTabsChanged();
                            listener.onStatusMessage("");
                        } else {
                            failedUrls.remove(tabId);
                            String loc = engine.getLocation();
                            String title = engine.getTitle();
                            updateActiveModel(title, loc);
                            listener.onPageCommitted(loc, title);
                            notifyPageLoaded(engine, loc, title);
                            fireTabsChanged();
                            listener.onStatusMessage("");
                            probeBlankRecovery(engine, tabId, loc);
                        }
                    } else if (state == Worker.State.FAILED) {
                        disarmWatchdog(tabId);
                        listener.onProgress(-1.0d);
                        handleLoadFailure(engine, tabId, worker.getException());
                    } else if (state == Worker.State.CANCELLED) {
                        disarmWatchdog(tabId);
                        listener.onProgress(-1.0d);
                        listener.onStatusMessage("");
                    }
                    fireNavigationState();
                });
        engine.setCreatePopupHandler(new Callback<PopupFeatures, WebEngine>() {
            @Override
            public WebEngine call(PopupFeatures config) {
                ExtensionBroker b = broker;
                if (b != null && b.isPopupBlocked(new PopupRequest(fxLocation(), false))) {
                    // A popup-blocking extension vetoed this window; returning
                    // null tells WebKit not to create a popup engine at all.
                    listener.onStatusMessage("Popup blocked by an extension");
                    listener.onPopupBlocked(fxLocation());
                    return null;
                }
                // Open popups / target=_blank links in a real new tab instead of
                // a separate window, so the tabbed model stays authoritative.
                return addTabView("New Tab", null).getEngine();
            }
        });
    }

    /** Classifies a load failure, renders its error page and notifies the panel. */
    private void handleLoadFailure(WebEngine engine, int tabId, Throwable ex) {
        String loc = engine.getLocation();
        LoadFailure failure = LoadFailure.classify(ex, loc);
        if (tabId >= 0 && isNetworkUrl(loc)) {
            failedUrls.put(tabId, loc);
        }
        listener.onStatusMessage(failure.getTitle());
        renderError(engine, tabId, failure);
        listener.onLoadFailed((loc == null) ? "" : loc, failure);
    }

    /** Loads the styled error card into {@code engine} for {@code failure}. */
    private void renderError(WebEngine engine, int tabId, LoadFailure failure) {
        if (engine == null) {
            return;
        }
        if (tabId >= 0) {
            pendingErrorRender.add(tabId);
        }
        try {
            engine.loadContent(ErrorPage.html(failure), ErrorPage.CONTENT_TYPE);
        } catch (RuntimeException e) {
            if (tabId >= 0) {
                pendingErrorRender.remove(tabId);
            }
            LOG.warn("Could not render the error page for {}", failure.getUrl(), e);
        }
    }

    /**
     * Fires the {@code onPageLoaded} content-script hook for the committed page.
     * The broker supplies a live script runner only to extensions granted
     * CONTENT_SCRIPT; a throwing or bad script is contained here so it can never
     * break the page or the load listener.
     */
    private void notifyPageLoaded(WebEngine engine, String loc, String title) {
        ExtensionBroker b = broker;
        if (b == null) {
            return;
        }
        b.notifyPageLoaded(loc, title, js -> {
            try {
                engine.executeScript(js);
            } catch (RuntimeException e) {
                LOG.warn("Content script failed on {}; ignored", loc, e);
            }
        });
    }

    // ------------------------------------------------------------------
    // Blank-page workaround (undecodable Content-Encoding)
    // ------------------------------------------------------------------

    /**
     * Runs after a network page reports SUCCEEDED: when WebKit discarded the
     * body (an empty DOM skeleton, the signature of an undecodable
     * {@code Content-Encoding}), re-fetch the page with the JDK HTTP client and
     * render it directly. One-shot per navigation, and only when JavaScript is
     * on (the probe needs it) and the URL is a network address. FX thread.
     */
    private void probeBlankRecovery(WebEngine engine, int tabId, String url) {
        if (tabId < 0 || !isNetworkUrl(url) || blankRecoveryAttempted.contains(tabId)
                || !settings.isJavaScriptEnabled()) {
            return;
        }
        Object raw = exec(engine, BLANK_DOM_PROBE);
        int[] counts = EncodingFallback.parseProbe(raw == null ? null : raw.toString());
        if (!EncodingFallback.isBlankDom(counts[0], counts[1])) {
            return;
        }
        blankRecoveryAttempted.add(tabId);
        // Remember the real URL so Reload retries it and the recovered document
        // can be committed under it (loadContent has no location of its own).
        failedUrls.put(tabId, url);
        listener.onStatusMessage("This page rendered blank; re-fetching it directly...");
        Thread worker = new Thread(() -> recoverBlankPage(tabId, url), "webbrowser-recover");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Off the FX thread: re-fetches {@code url} with the JDK HTTP client (which
     * does not advertise {@code Accept-Encoding}, so it is normally answered with
     * a plain body, and can decode gzip/deflate itself), then renders it with an
     * injected {@code <base>} tag. Any failure falls back to an honest error page.
     */
    private void recoverBlankPage(int tabId, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, settings.getPageTimeoutSeconds())))
                    .header("Accept",
                            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .GET()
                    .build();
            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status >= 400) {
                renderRecoveryFailure(tabId, url, LoadFailure.fromHttpStatus(status, url));
                return;
            }
            String encoding = response.headers().firstValue("Content-Encoding").orElse(null);
            String contentType = response.headers().firstValue("Content-Type").orElse("text/html");
            byte[] body = EncodingFallback.decode(response.body(), encoding);
            String html = new String(body, EncodingFallback.charsetFor(contentType));
            String based = EncodingFallback.injectBaseTag(html, url);
            runOnFx(() -> {
                if (shuttingDown) {
                    return;
                }
                WebEngine e = engineFor(tabId);
                if (e == null) {
                    return;
                }
                recoveryRender.add(tabId);
                e.loadContent(based, "text/html");
            });
        } catch (IOException e) {
            // An unsupported Content-Encoding (br/zstd) or a transport failure:
            // show an honest card instead of leaving the tab blank.
            renderRecoveryFailure(tabId, url, LoadFailure.of(
                    LoadFailure.Reason.UNDECODABLE_BODY, url, EncodingFallback.describe(e)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            renderRecoveryFailure(tabId, url, LoadFailure.of(
                    LoadFailure.Reason.UNKNOWN, url, LoadFailure.describe(e)));
        }
    }

    /** Renders the fallback error page for a recovery that could not complete. */
    private void renderRecoveryFailure(int tabId, String url, LoadFailure failure) {
        runOnFx(() -> {
            if (shuttingDown) {
                return;
            }
            WebEngine e = engineFor(tabId);
            if (e == null) {
                return;
            }
            listener.onProgress(-1.0d);
            listener.onStatusMessage(failure.getTitle());
            renderError(e, tabId, failure);
            listener.onLoadFailed(url, failure);
            fireNavigationState();
        });
    }

    /** @return the engine of the tab with {@code tabId}, or null if it is gone. */
    private WebEngine engineFor(int tabId) {
        WebView v = views.get(tabId);
        return (v == null) ? null : v.getEngine();
    }

    private void loadInternal(String url) {
        WebEngine engine = activeEngine();
        if (engine == null || url == null || url.isBlank()) {
            return;
        }
        int tabId = activeTabId();
        String target = url;
        ExtensionBroker b = broker;
        if (b != null) {
            NavigationDecision decision =
                    b.onNavigate(new NavigationRequest(url, tabId));
            if (decision.isBlock()) {
                listener.onStatusMessage("Navigation blocked by an extension: " + url);
                listener.onNavigationBlocked(url);
                return;
            }
            if (decision.isRedirect()) {
                String redirected = decision.getTargetUrl();
                if (redirected.isBlank()) {
                    return;
                }
                // Load the redirect target directly (not via loadInternal) so an
                // extension cannot cause a re-consult / redirect loop.
                target = redirected;
            }
        }
        if (maybeDownload(target)) {
            return;
        }
        failedUrls.remove(tabId);
        // A fresh navigation resets the one-shot blank-page recovery state.
        blankRecoveryAttempted.remove(tabId);
        recoveryRender.remove(tabId);
        engine.load(target);
        armWatchdog(tabId, engine, target);
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
        refreshNav();
    }

    private void showActive() {
        TabModel.Tab tab = model.getActiveTab();
        WebView active = (tab == null) ? null : views.get(tab.getId());
        root.getChildren().clear();
        if (active != null) {
            root.getChildren().add(active);
        }
        refreshNav();
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

    /** @return the active tab's id, or -1 when there is none. */
    private int activeTabId() {
        TabModel.Tab tab = model.getActiveTab();
        return (tab == null) ? -1 : tab.getId();
    }

    private WebHistory activeHistory() {
        WebEngine e = activeEngine();
        return (e == null) ? null : e.getHistory();
    }

    /** @return the active engine's live location, or "" (FX thread only). */
    private String fxLocation() {
        WebEngine e = activeEngine();
        return (e == null || e.getLocation() == null) ? "" : e.getLocation();
    }

    private String safeTitle(WebEngine engine) {
        String t = engine.getTitle();
        return (t == null || t.isBlank()) ? engine.getLocation() : t;
    }

    /** Recomputes the {@link #nav} snapshot from live WebKit state (FX thread). */
    private void refreshNav() {
        WebHistory h = activeHistory();
        WebEngine e = activeEngine();
        WebView v = activeView();
        boolean back = h != null && h.getCurrentIndex() > 0;
        boolean fwd = h != null && h.getCurrentIndex() < h.getEntries().size() - 1;
        String loc = (e == null || e.getLocation() == null) ? "" : e.getLocation();
        double zoom = (v == null) ? 1.0d : v.getZoom();
        nav = new NavSnapshot(back, fwd, loc, zoom);
    }

    private void fireTabsChanged() {
        listener.onTabsChanged();
    }

    private void fireLocation() {
        refreshNav();
        listener.onLocationChanged(nav.location);
    }

    private void fireNavigationState() {
        refreshNav();
        listener.onNavigationStateChanged(nav.canBack, nav.canForward);
    }

    // ------------------------------------------------------------------
    // Navigation watchdog
    // ------------------------------------------------------------------

    /**
     * Schedules a timeout for the navigation just started on {@code tabId}. If
     * it is still loading after the configured page timeout, the load is
     * cancelled and a TIMEOUT error page is rendered. Disarmed by any terminal
     * worker state, a new navigation, or {@link #shutdown()}.
     */
    private void armWatchdog(int tabId, WebEngine engine, String url) {
        if (tabId < 0 || engine == null) {
            return;
        }
        disarmWatchdog(tabId);
        final int seconds = Math.max(1, settings.getPageTimeoutSeconds());
        ScheduledFuture<?> future = watchdogScheduler.schedule(() -> {
            watchdogs.remove(tabId);
            runOnFx(() -> {
                if (shuttingDown) {
                    return;
                }
                Worker<Void> worker = engine.getLoadWorker();
                Worker.State state = worker.getState();
                // The page may have finished right at the deadline; don't nuke it.
                if (state == Worker.State.SUCCEEDED || state == Worker.State.FAILED) {
                    return;
                }
                try {
                    worker.cancel();
                } catch (RuntimeException ignored) {
                    // best effort
                }
                LoadFailure failure = LoadFailure.of(LoadFailure.Reason.TIMEOUT, url,
                        "No response within " + seconds + "s");
                if (isNetworkUrl(url)) {
                    failedUrls.put(tabId, url);
                }
                listener.onProgress(-1.0d);
                listener.onStatusMessage(failure.getTitle());
                renderError(engine, tabId, failure);
                listener.onLoadFailed(url, failure);
                fireNavigationState();
            });
        }, seconds, TimeUnit.SECONDS);
        watchdogs.put(tabId, future);
    }

    private void disarmWatchdog(int tabId) {
        ScheduledFuture<?> future = watchdogs.remove(tabId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void disarmAllWatchdogs() {
        for (Integer id : new ArrayList<>(watchdogs.keySet())) {
            disarmWatchdog(id);
        }
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
        AtomicBoolean cancel = new AtomicBoolean(false);
        downloadFlags.put(url, cancel);
        listener.onDownloadChanged(record.copy());
        Thread worker = new Thread(() -> runDownload(url, record, cancel), "webbrowser-download");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Runs off the FX thread: streams the body to disk with a bounded timeout,
     * reporting incremental progress and honouring the cancel flag. The record
     * is mutated here but only ever handed to the listener as a {@code copy()},
     * so the EDT/FX reader never sees a torn state.
     */
    private void runDownload(String url, DownloadRecord record, AtomicBoolean cancel) {
        Path target = null;
        try {
            Files.createDirectories(downloadDir);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(1, settings.getDownloadTimeoutSeconds())))
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 400) {
                LoadFailure failure = LoadFailure.fromHttpStatus(status, url);
                record.markFailed(failure.getTitle() + " (" + failure.getDetail() + ")");
                listener.onDownloadChanged(record.copy());
                return;
            }
            String disposition = response.headers().firstValue("Content-Disposition").orElse(null);
            String name = ContentDisposition.fileName(disposition);
            if (name == null || name.isBlank()) {
                name = fileNameFor(url);
            }
            record.setFileName(name);
            long total = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (total > 0) {
                record.setTotalBytes(total);
            }
            target = uniquePath(downloadDir.resolve(name));

            long written = 0L;
            long lastReport = 0L;
            boolean cancelled = false;
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(target)) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (cancel.get()) {
                        cancelled = true;
                        break;
                    }
                    out.write(buffer, 0, n);
                    written += n;
                    if (written - lastReport >= PROGRESS_REPORT_BYTES) {
                        lastReport = written;
                        record.updateProgress(written, Math.max(total, 0L));
                        listener.onDownloadChanged(record.copy());
                    }
                }
            }
            if (cancelled) {
                record.markCancelled();
                deleteQuietly(target);
                listener.onDownloadChanged(record.copy());
                return;
            }
            record.markComplete(target.toString(), written);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (cancel.get()) {
                record.markCancelled();
            } else {
                record.markFailed(LoadFailure.describe(e));
                LOG.warn("Download failed for {}", url, e);
            }
            deleteQuietly(target);
        } finally {
            downloadFlags.remove(url);
        }
        listener.onDownloadChanged(record.copy());
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

    /**
     * Clears the cookie store the browser controls (best-effort privacy action),
     * then reinstalls the current policy over a fresh store. Callable from any
     * thread; the JDK {@link CookieManager} is process-wide.
     */
    public void clearCookies() {
        CookieHandler handler = CookieHandler.getDefault();
        if (handler instanceof CookieManager) {
            try {
                ((CookieManager) handler).getCookieStore().removeAll();
            } catch (RuntimeException e) {
                LOG.warn("Could not clear the cookie store", e);
            }
        }
        installCookiePolicy(settings);
    }

    // ------------------------------------------------------------------
    // Helpers (callable from any thread unless noted)
    // ------------------------------------------------------------------

    /** Runs a script on the engine, containing any WebKit/runtime failure. */
    private static Object exec(WebEngine engine, String script) {
        try {
            return engine.executeScript(script);
        } catch (RuntimeException e) {
            LOG.debug("Script execution failed; ignored", e);
            return null;
        }
    }

    /** Builds a download client with a bounded TCP connect timeout. */
    private static HttpClient buildHttpClient(BrowserSettings s) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL);
        try {
            builder.connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS));
        } catch (RuntimeException ignored) {
            // A bad timeout value must never make the browser unusable.
        }
        return builder.build();
    }

    private static boolean isNetworkUrl(String url) {
        if (url == null) {
            return false;
        }
        String l = url.toLowerCase(Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://")
                || l.startsWith("file://") || l.startsWith("ftp://");
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }

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
