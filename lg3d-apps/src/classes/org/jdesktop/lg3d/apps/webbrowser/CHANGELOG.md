# Changelog

All notable changes to Webbrowser are documented here.

## [1.64.0] — 2026-10-07

### Added

- **Web Browser: a developer extension/plugin API (Java SPI), an in-browser
  manager, built-in reference extensions, browser-completeness upgrades and a
  proper window icon** (`lg3d-apps` `org.jdesktop.lg3d.apps.webbrowser` + a new
  `...webbrowser.ext` SPI package) — the JavaFX/WebKit browser gains a
  first-class extension system. `BrowserExtension` is the SPI (all-default
  hooks: `onNavigate`, `onPopup`, `onPageLoaded`, `onBrowserStarted/Stopping`,
  `toolbarContributions`), published with immutable value types
  (`ExtensionManifest`, a `Permission` enum, `NavigationRequest`/`Decision`,
  `PopupRequest`/`Decision`, `PageContext`, `BrowserContext`,
  `ToolbarContribution`) that hold no AWT/JavaFX reference, so extensions are
  headless-testable. `ExtensionRegistry` discovers built-ins through
  `ServiceLoader` *and* third-party jars dropped into
  `~/.lg3d/webbrowser/extensions` via a scoped child `URLClassLoader`; a
  third-party extension starts **disabled with nothing granted** until the user
  approves it in the new **Extensions** manager dialog (`ExtensionManagerDialog`:
  enable/disable, an explicit permission-grant gate, per-extension permission
  editing, rescan, open-folder), and the enable/grant state persists as
  `extensions.json` through `BrowserStore`. `ExtensionBroker` dispatches every
  hook behind the granted-permission gate and isolates failures (a throwing
  extension is logged and skipped, never fatal); `FxBrowser` consults it for the
  popup handler (a `POPUP` veto returns `null`, suppressing the window),
  `loadInternal` (a `NAVIGATE` BLOCK skips the load, a REDIRECT loads the
  target) and `Worker.State.SUCCEEDED` (the `onPageLoaded` content-script point,
  whose script runner is live only for a `CONTENT_SCRIPT` grant). Three built-in
  reference extensions ship in-tree and exercise the same SPI: **Popup Blocker**
  (blocks non-user-initiated popups), **Tracker Blocker** (vetoes a bundled
  tracker/ad host list + hides common ad containers) and **HTTPS Upgrade**
  (redirects http→https except local hosts). Browser completeness: window-level
  shortcuts (Ctrl+T/W/L/R/F, Ctrl+Tab / Ctrl+Shift+Tab), a per-tab close button
  in the strip, and Settings **Clear history** / **Clear cookies** privacy
  actions. The standalone/child-process `WebBrowserApp` frame now sets its icon
  (`setIconImage` from the assembled `resources/images/icon/webbrowser.png`),
  fixing the bare-`JFrame` OS-default "home folder" glyph; the 2D MDI frame and
  taskbar already resolve the descriptor icon. The security model is documented
  honestly as a **consent/UX boundary, not a JVM sandbox** (in-JVM code is
  trusted). **New headless JUnit 5 tests**: `ExtensionManifestTest`,
  `ExtensionRegistryTest` (classpath + temp-dir jar discovery, the permission
  gate, enable/grant persistence), `ExtensionBrokerTest` (dispatch, exception
  isolation, permission enforcement, block/redirect decisions, content-script
  gating, toolbar de-dup), `BrowserStoreExtensionsTest` (extensions.json
  round-trip + defensive reads) and `PopupBlocker`/`TrackerBlocker`/
  `HttpsUpgradeExtensionTest`. See `docs/webbrowser-extensions.md`.

- **Web Browser: a dedicated release workflow for the extension API jar**
  (`lg3d-apps` build + `.github/workflows/release-webbrowser-api.yml`) — the
  `org.jdesktop.lg3d.apps.webbrowser.ext` SPI can now be published on its own, so
  third-party extension developers compile against a small, stable
  `lg3d-webbrowser-ext-api-<version>.jar` (plus a `-sources.jar`) instead of the
  whole `lg3d-apps`. Two on-demand Gradle tasks (`:lg3d-apps:webBrowserApiJar` /
  `:webBrowserApiSourcesJar`) package exactly the developer contract — the
  `BrowserExtension` interface and its immutable value types — excluding the
  browser's internal runtime (`ExtensionRegistry` / `ExtensionBroker` /
  `ExtensionState`) and the `ext.builtin` reference implementations. A new manual workflow builds both jars and publishes a **dedicated**
  GitHub Release tagged `webbrowser-api-v<version>` with the jar, sources and a
  `SHA256SUMS.txt`; that tag namespace deliberately does not match the desktop
  bundle's `v*` trigger, so the two release lines stay independent, and a
  `-dev`/`-rc` version publishes as a pre-release (never `releases/latest`).
  Verified locally: the produced jar is self-consistent (a third-party
  `BrowserExtension` compiles against it alone) and `-PreleaseVersion` stamps the
  version.

### Fixed

- **Web Browser: WebSocket pages no longer spam `UnsatisfiedLinkError` stack
  traces (2D desktop + standalone)** (`lg3d-apps`
  `org.jdesktop.lg3d.apps.webbrowser`) — the Linux WebKit native library shipped
  with JavaFX 21 omits the JNI implementations of
  `com.sun.webkit.network.SocketStreamHandle.twkDidOpen`/`twkDidClose`, so any
  page that opens a **WebSocket** threw `UnsatisfiedLinkError` on the JavaFX
  Application Thread from inside JavaFX's own networking callback — far from any
  `try/catch` the app controls — printing a full stack trace on every socket event
  that read like a crash (upstream **JDK-8346250**; verified the loaded `.so`
  exports neither the symbols nor their strings). The error is benign (the socket
  never opens; the page still renders and stays interactive), and a real fix needs
  JavaFX 24+, which requires JDK 22+ and is out of reach on this JDK-21-pinned
  desktop. A new scoped `WebKitThreadGuard`, installed on the FX Application
  Thread at the top of `BrowserPanel.bootJavaFx` (so it covers both the in-process
  2D host and the spawned standalone child process), recognises exactly that one
  error — an `UnsatisfiedLinkError` whose trace passes through `com.sun.webkit` —
  logs a single concise warning the first time and swallows it, while **every
  other throwable is re-dispatched untouched** so a genuine defect is never
  masked. **New headless JUnit 5 test** `WebKitThreadGuardTest` pins the
  classification (known vs unrelated link errors, other `LinkageError`s, null) and
  the swallow-vs-delegate behaviour without a display, toolkit or network.


## [1.53.1] — 2026-10-06

### Added

- **Web Browser for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.webbrowser`; registration in `lg3d-core`, icon in
  `lg3d-core` resources) — a production full-featured web browser and the
  **first OpenJFX dependency in the repo**: it renders the modern web through a
  JavaFX `WebView` (WebKit) engine, wired via `org.openjfx:javafx-{base,
  graphics,controls,media,web,swing}:21.0.12` with the `linux` classifier
  (GPLv2 + Classpath Exception), mirroring the Jogamp natives pattern and
  resolved onto both the `lg3d-apps` compile path and the hand-assembled
  `:lg3d-core:run` / `releaseBundle` classpath (the `javafxLibs` detached
  configuration). The engine is deliberately split by surface: because
  `SwingNode.captureNow` paints Swing offscreen into a `BufferedImage` while a
  JavaFX surface is a heavyweight native peer that yields a **blank quad**
  offscreen (and initialising JavaFX in the Java 3D JVM risks an OpenGL toolkit
  clash), the full interactive browser lives only where a real heavyweight host
  exists. In 2D `Desktop2DAppRegistry.PANEL_APPS` opens `BrowserPanel` as an MDI
  internal frame — a tab strip, a navigation toolbar (back / forward / reload /
  stop / home, a smart URL field, bookmarks menu, find, zoom, view-source,
  downloads, history, settings) and a status/progress bar with an HTTPS
  indicator — with a `JFXPanel` centre hosting the `FxBrowser` scene. In 3D the
  `WebBrowser` wrapper instead shows a pure-Swing `BrowserPreviewPanel` (product
  art, a painted chrome mock and an "Open Full Browser" button that spawns
  `WebBrowserApp` — a top-level `JFrame` holding `BrowserPanel` — into a detached
  child-process JVM with `DISPLAY` propagated, degrading to an in-JVM thread
  launch if the spawn fails), so JavaFX never touches the Java 3D GL context.
  Every `javafx.scene.web` reference is confined to `FxBrowser`, which owns one
  `WebView`/`WebEngine` per tab (keyed on `TabModel.Tab` id), wires location /
  title / `LoadWorker` progress + state / `createPopupHandler` listeners, applies
  settings (JavaScript, user agent, cookies via `java.net.CookieManager`), and
  does zoom, find-in-page , view-source and best-effort downloads
  (`java.net.http.HttpClient` to the download dir, then `xdg-open`); all mutators
  marshal through `Platform.runLater` and report back on the FX thread. The
  AWT-free model (`UrlNormalizer` smart address bar — scheme passthrough /
  `https://` for host-like text / search otherwise, `SearchEngine`,
  `BookmarkStore`, `HistoryStore` capped + de-duplicated + transient-filtered,
  `TabModel`, `BrowserSettings`, `DownloadRecord`) persists as JSON under
  `~/.lg3d/webbrowser` via `BrowserStore` (Jackson, defensive reads:
  missing/corrupt yields defaults, never a throw), and restores the prior
  session's tabs on launch. Registered in the start menu (**Internet** group) via
  `webbrowser.lgcfg` in `lg3d-apps` `src/config`, with a programmatic
  browser-window-and-globe icon generated by `lg3d-art/tools/GenerateAppIcons`.
  Features WebKit/`WebView` cannot do natively (a full download manager, true
  DevTools) are delegated or surfaced honestly rather than faked. Covered by
  headless JUnit 5 tests plus a `Desktop2DAppRegistryTest`
  mapping assertion; the GUI itself has no coverage/mutation gate and is verified
  via the in-JVM probe + `lgscreen-*.png` capture.
