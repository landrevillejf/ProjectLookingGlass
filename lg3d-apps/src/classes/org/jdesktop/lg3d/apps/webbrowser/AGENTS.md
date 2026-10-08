# Web Browser Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (web browser) |
| 2D entry point | `Desktop2DAppRegistry.PANEL_APPS` → `BrowserPanel` (full interactive browser in an MDI internal frame) |
| 3D entry point | `WebBrowser.main` → `TitledSwingWindow.show(...)` hosting `BrowserPreviewPanel` (static preview) |
| Standalone | `WebBrowserApp.main` → a top-level `JFrame` holding `BrowserPanel` |
| Surface | **Split by design** — 2D: `JFXPanel`-hosted `WebView`; 3D: pure-Swing preview whose button spawns `WebBrowserApp` into a child-process JVM |
| Start-menu name / group | Web Browser / **Internet** |
| Command | `java org.jdesktop.lg3d.apps.webbrowser.WebBrowser` |
| Descriptor | `src/config/webbrowser.lgcfg` → `config/demo` |
| Engine | **JavaFX `WebView` (WebKit)** via OpenJFX `21.0.12`, `linux` classifier (GPLv2 + Classpath Exception) — the first OpenJFX dependency in the repo |
| Extensions | Java SPI (`ServiceLoader`) in `...webbrowser.ext`; built-ins via `META-INF/services`, third-party jars in `~/.lg3d/webbrowser/extensions`; manager = `ExtensionManagerDialog` (see [`docs/webbrowser-extensions.md`](../../../../../../../../docs/webbrowser-extensions.md)) |
| Extension API jar | `:lg3d-apps:webBrowserApiJar` / `:webBrowserApiSourcesJar` package the `...webbrowser.ext` SPI + value types only (no internal `ExtensionRegistry`/`ExtensionBroker`/`ExtensionState`, no `ext.builtin`) into `lg3d-webbrowser-ext-api-<version>.jar`; `.github/workflows/release-webbrowser-api.yml` (manual `workflow_dispatch`) publishes it to a dedicated `webbrowser-api-v<version>` GitHub Release, independent of the desktop `v*` release |
| Persistence | Jackson JSON under `~/.lg3d/webbrowser/` (override with `-Dlg3d.webbrowser.dir`) |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **Model (JavaFX-free, AWT-free, headless-tested)** — `UrlNormalizer` (smart
  address bar: scheme passthrough / `https://` for host-like text / search
  otherwise), `SearchEngine` (DuckDuckGo default, Google, Bing, Startpage, Brave,
  Ecosia, Mojeek, Wikipedia), `Bookmark` + `BookmarkStore`, `HistoryEntry` +
  `HistoryStore` (capped, de-duplicated, transient-scheme-filtered), `TabModel`
  (+ nested `Tab`; `synchronized` mutators/accessors and `list()` returns a
  defensive snapshot, so the EDT can iterate while the FX thread mutates),
  `BrowserSettings` (+ `pageTimeoutSeconds` / `downloadTimeoutSeconds`, clamped
  >0 and carried in `copy()`), `DownloadRecord` (+ `totalBytes`,
  `updateProgress`, `markCancelled`), and `BrowserStore` (the one Jackson
  persistence facade; defensive reads never throw).
- **Decision logic (JavaFX-free, AWT-free, headless-tested)** — every new browser
  behaviour lives in a pure class the FX/Swing layers only call: `LoadFailure` (a
  `Reason` enum + `classify(Throwable, url)` that walks the cause chain and
  WebKit's message text into a retryable, human-readable failure), `ErrorPage` (a
  self-contained, XSS-escaped HTML error document with a conditional "Try again"
  anchor), `Html` (the shared escaper), `ContentDisposition` (RFC 6266/5987
  download filename parsing), `SecurityInfo` (classifies a URL into SECURE /
  NOT_SECURE / LOCAL / INTERNAL with a summary + warning), `SiteStats` (per-host
  trackers/popups-blocked counters), `FindScript` (injection-safe find-in-page JS
  + match-count parsing), and `ReaderArticle` + `ReaderExtractor`
  (readability-style DOM-extraction JS + a clean, escaped, ad-free HTML render).
- **FxBrowser** — the only class that touches `javafx.scene.web`. Owns one
  `WebView`/`WebEngine` per tab (keyed on `TabModel.Tab` id) in a
  `ConcurrentHashMap`, wires location / title / `LoadWorker` progress + state /
  `createPopupHandler` listeners, applies settings (JavaScript, user agent,
  cookies via `java.net.CookieManager`), does zoom, find-in-page (via
  `FindScript`), view-source and reader mode (both render generated HTML in a new
  tab). A `FAILED` load classifies through `LoadFailure` and renders an
  `ErrorPage` (retry remembers the failed URL per tab); `CANCELLED` resets
  progress; a shared daemon `ScheduledExecutorService` watchdog cancels + times
  out a stalled navigation (timeout from settings). Downloads are robust: a
  `java.net.http` client with connect + per-request timeouts, `ContentDisposition`
  naming, a streaming copy loop with incremental progress + a cancel flag, and IO
  failures classified through the same `LoadFailure` vocabulary. Read-only getters
  (`canGoBack`/`canGoForward`/`getLocation`/`getZoom`) read a `volatile`
  `NavSnapshot` refreshed on the FX thread instead of touching
  `WebHistory`/`WebView` off-thread. Every mutator marshals through
  `Platform.runLater`; state changes report back through a `Listener` on the FX
  thread, which now also carries `onLoadFailed`, `onFindResults`,
  `onNavigationBlocked` and `onPopupBlocked`.
- **BrowserPanel** — the Swing face (public no-arg constructor, as
  `Desktop2DAppRegistry.createPanel` requires): a tab strip (with a per-tab close
  button), a navigation toolbar (back/forward/reload/stop/home, a clickable
  `SecurityInfo` indicator, smart URL field, bookmarks menu, find, reader, zoom,
  view-source, downloads, history, settings, **Extensions**, plus any extension
  toolbar contributions), a `JFXPanel` centre hosting the `FxBrowser` scene above
  an inline find bar (field, "n of m" count, prev/next/close; Ctrl+F opens,
  Enter = next, Shift+Enter = prev, Esc closes), and a status/progress bar with an
  HTTPS echo. The security indicator opens a site-info popup (connection
  security, host, cookie/JS state, per-site trackers/popups blocked, tallied in
  `SiteStats`); the downloads dialog is a scrolling list with per-row Cancel +
  progress; settings gains page/download timeout fields and keeps **Clear
  history** / **Clear cookies**. Session restore is defensive (skips
  blank/`view-source:` URLs, caps at `MAX_RESTORED_TABS`, and one bad URL cannot
  abort startup). Window-level shortcuts: Ctrl+T/W/L/R/F and Ctrl+Tab /
  Ctrl+Shift+Tab. Building the `JFXPanel` on the EDT boots the JavaFX toolkit; FX
  work is marshalled with `Platform.runLater` and callbacks return to the EDT via
  `SwingUtilities.invokeLater`. The panel constructs the `ExtensionRegistry` +
  `ExtensionBroker` and opens `ExtensionManagerDialog` from the Extensions button.
- **WebBrowserApp** — a top-level `JFrame` hosting `BrowserPanel`
  (`DISPOSE_ON_CLOSE`, releasing the FX engines on close). This is the child
  process the 3D preview spawns, and it runs standalone too. It calls
  `setIconImage` from the assembled `resources/images/icon/webbrowser.png` so the
  top-level window shows the browser glyph, not the OS default "home folder"; the
  2D MDI frame + taskbar already resolve the descriptor icon through
  `AppIcons.iconFor` in `lg3d-core`.
- **Extension SPI (`...webbrowser.ext`, AWT/JavaFX-free, headless-tested)** —
  `BrowserExtension` (all-default hooks: `onNavigate`, `onPopup`,
  `onPageLoaded`, `onBrowserStarted/Stopping`, `toolbarContributions`) plus the
  immutable value types (`ExtensionManifest`, the `Permission` enum,
  `NavigationRequest`/`Decision`, `PopupRequest`/`Decision`, `PageContext`,
  `BrowserContext`, `ToolbarContribution`, `ExtensionState`).
  **`ExtensionRegistry`** discovers built-ins via `ServiceLoader`
  (`META-INF/services/org.jdesktop.lg3d.apps.webbrowser.ext.BrowserExtension`,
  bundled by the `META-INF/services/**` include in `lg3d-apps/build.gradle`) and
  third-party jars from `~/.lg3d/webbrowser/extensions` via a scoped child
  `URLClassLoader`; built-ins start enabled + pre-granted, third-party extensions
  start **disabled with nothing granted** (the permission gate). **`ExtensionBroker`**
  dispatches hooks behind the granted-permission gate and isolates every call in
  `try/catch` (a throwing extension is logged + skipped, never fatal).
  `FxBrowser.setExtensionBroker` injects the broker (built by `BrowserPanel`),
  which the popup handler, `loadInternal` and the `SUCCEEDED` page-loaded hook
  consult. Three in-tree reference extensions (`ext.builtin`): `PopupBlocker`,
  `TrackerBlocker`, `HttpsUpgrade`. Enable/grant state persists as
  `extensions.json` via `BrowserStore`. The SPI doubles as a **published API**:
  `:lg3d-apps:webBrowserApiJar` / `:webBrowserApiSourcesJar` package just the
  developer contract (the `ext` SPI + value types, excluding
  `ExtensionRegistry`/`ExtensionBroker`/`ExtensionState` and `ext.builtin`) into
  `lg3d-webbrowser-ext-api-<version>.jar`, which
  `.github/workflows/release-webbrowser-api.yml` publishes to a dedicated
  `webbrowser-api-v<version>` GitHub Release on manual dispatch (independent of
  the desktop `v*` release).
- **WebBrowser** — the 3D entry: installs the hosted look and feel and shows
  `BrowserPreviewPanel` in a `TitledSwingWindow`. Builds **no** JavaFX.
- **BrowserPreviewPanel** — pure Swing, **imports no `javafx.*`**: product art, a
  feature blurb, a painted mock of the browser chrome and an "Open Full Browser"
  button that spawns
  `"<java.home>/bin/java" -cp "<java.class.path>" ...WebBrowserApp` with `DISPLAY`
  propagated (degrading to an in-JVM thread launch if the spawn fails).

## Why the 2D-full / 3D-preview split

`SwingNode.captureNow` paints Swing offscreen into a `BufferedImage`, but a
JavaFX surface is a heavyweight native peer that yields a **blank quad** when
painted offscreen, so a `WebView` cannot ride the 3D capture path — and
initialising JavaFX in the Java 3D JVM risks an OpenGL toolkit clash. Keeping
JavaFX out of the 3D process entirely (static preview + child-process launch) is
the lowest-risk realization of "full 2D, 3D static preview". In the 2D/Swing
desktop the browser is hosted as an MDI internal frame, where the heavyweight
`JFXPanel` composites correctly against an on-screen window.

## Roles

- **Architect** — Confine every `javafx.scene.web` reference to `FxBrowser`; the
  Swing `BrowserPanel` talks to it only through its `Listener` + thread-safe
  methods. Keep the model (`UrlNormalizer`, stores, `TabModel`, `BrowserSettings`,
  `BrowserStore`) free of AWT/JavaFX so it unit-tests headless. OpenJFX is an
  `lg3d-apps` compile dependency **and** must be resolved onto the hand-assembled
  `:lg3d-core:run` / `releaseBundle` classpath (the `javafxLibs` detached
  configuration in `lg3d-core/build.gradle`, `linux` classifier) or the in-JVM 2D
  launch and the 3D child-process launch die with `NoClassDefFoundError:
  javafx/...`. Never construct `WebView`/`JFXPanel` in the 3D desktop JVM.
- **Engineer / Developer** — JavaFX work runs on the FX Application Thread; Swing
  work on the EDT; marshal explicitly in both directions. `BrowserPreviewPanel`
  must stay free of `javafx.*` so the 3D host never loads the toolkit (the
  in-process fallback is the only path that does, and only when the spawn fails).
  Downloads are best-effort and honest: a `FAILED` record keeps its error. Jogamp
  packages only in any 3D code; obey the core UI/UX rulebook.
- **QA** — The model tests (`UrlNormalizerTest`, `SearchEngineTest`,
  `BookmarkStoreTest`, `HistoryStoreTest`, `TabModelTest`, `BrowserSettingsTest`,
  `DownloadRecordTest`, `BrowserStoreTest`), the decision-logic tests
  (`LoadFailureTest`, `HtmlTest`, `ErrorPageTest` incl. XSS-escaping +
  retry-anchor presence, `ContentDispositionTest`, `SecurityInfoTest`,
  `SiteStatsTest`, `FindScriptTest`, `ReaderExtractorTest`) and
  `WebKitThreadGuardTest` (the JDK-8346250 WebSocket swallow-vs-delegate guard)
  run headless (`java.awt.headless=true`) and never build a `WebView`/`JFXPanel`.
  The extension system is covered headlessly too: `ExtensionManifestTest`,
  `ExtensionRegistryTest`
  (classpath + temp-dir jar discovery, the permission gate, enable/grant
  persistence), `ExtensionBrokerTest` (dispatch, exception isolation, permission
  enforcement, block/redirect, content-script gating, toolbar de-dup),
  `BrowserStoreExtensionsTest` and `PopupBlocker`/`TrackerBlocker`/
  `HttpsUpgradeExtensionTest`. `Desktop2DAppRegistryTest` asserts the
  command classifies as `PANEL` and maps to `BrowserPanel`. `FxBrowser` and
  `BrowserPanel` stay without unit tests (they need a live toolkit), consistent
  with the suite. The GUI itself has no coverage/mutation gate; verify it with the
  in-JVM probe + `lgscreen-*.png` capture on the host X display (2D MDI browser
  navigates; error page, find bar, reader, security popup; 3D preview + child
  process launch). A black capture under Wayland is not a defect.
- **Business Analyst** — A daily-driver utility: browse the modern web from the
  desktop without installing a separate browser. Value = tabbed browsing, search
  (eight engines), bookmarks, history, an inline find bar with match counts,
  reader mode, zoom, view-source, an address-bar security indicator + site-info,
  JS + cookies, private browsing, resilient downloads (progress, cancel,
  timeouts) and honest, retryable error pages — surfaced within what
  WebKit/`WebView` can really do (a full download manager and true DevTools are
  delegated or omitted, not faked).
- **Functional Analyst** — Spec the *browser contract*: resolve address-bar text
  (URL vs search), navigate (back/forward/reload/stop/home), manage tabs, record
  de-duplicated capped history, bookmark by URL idempotently, persist settings and
  session as JSON, and download non-renderable content. The WebKit engine is an
  implementation detail behind `FxBrowser`.
- **Project Manager** — Commit scope `lg3d-apps`; the registration also touches
  `lg3d-core` (`Desktop2DAppRegistry` panel mapping + the run/releaseBundle
  classpath + the icon resource), `lg3d-art/tools/GenerateAppIcons`, the version
  catalog (`gradle/libs.versions.toml`) and the root `AGENTS.md` External
  Dependencies line — call those out. Done = build + headless tests +
  `./run-lg3d.sh` capture/log evidence. Branch → PR against `main`; never commit
  to `main`.
- **UI/UX (3D & 2D)** — 2D: the full interactive browser in an MDI internal frame
  (conventional browser chrome: tab strip + toolbar + status bar). 3D: a glassy
  `TitledSwingWindow` frame showing the branded static preview and the "Open Full
  Browser" affordance; never a click-cycling 3D idiom, and never a blank
  offscreen-captured `WebView`.

## Known caveats

- `JFXPanel` is heavyweight, so overlapping MDI frames in the 2D desktop can
  z-order above it; acceptable per the chosen design.
- OpenJFX adds ~60-100 MB of Linux natives to the runtime footprint (the cost of a
  real WebKit engine). The browser degrades gracefully (the preview shows
  guidance) if JavaFX is absent at runtime.
- Linux only: just the `linux` classifier is wired, mirroring the Jogamp natives.
- **Error pages, view-source and reader mode are generated documents** rendered
  with `WebEngine.loadContent(html, "text/html")`, not real navigations, so they
  carry no history URL and every interpolated value is HTML-escaped (`Html`) to
  stay XSS-safe. The navigation watchdog is a shared daemon
  `ScheduledExecutorService`; `shutdown()` cancels all watchdog tasks + in-flight
  downloads and stops the scheduler, and closing a tab disarms its watchdog,
  cancels its download and drops its cached failure/security state.
- **The extension permission gate is a consent/UX boundary, not a JVM sandbox.**
  Extension code runs in-process with the user's full privileges; the JVM has no
  capability sandbox for in-process bytecode. The gate makes an extension declare
  its permissions, makes the user approve them in `ExtensionManagerDialog`, and
  enforces them on every hook (no unapproved navigate / popup-block /
  content-script / toolbar). It cannot stop a malicious jar once loaded — install
  only trusted extensions. Failure isolation (every hook `try/catch`) is always on.
- **WebSockets are unavailable on Linux (upstream JDK-8346250).** JavaFX 21's
  `libjfxwebkit.so` omits the `com.sun.webkit.network.SocketStreamHandle`
  `twkDidOpen`/`twkDidClose` natives, so a page that opens a WebSocket throws
  `UnsatisfiedLinkError` on the FX Application Thread. `WebKitThreadGuard`
  (installed on that thread at the top of `BrowserPanel.bootJavaFx`, covering both
  the in-process 2D host and the spawned standalone child) recognises that one
  error, logs it once and swallows it so the page keeps rendering instead of
  spamming stack traces; every other throwable is re-dispatched untouched. A real
  fix needs JavaFX 24+, which requires JDK 22+ — out of reach on this
  JDK-21-pinned desktop.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the split surface (2D `JFXPanel` browser + 3D preview/child
process), the `FxBrowser` JavaFX seam, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump. Stage only intended paths (never `git add -A`).
