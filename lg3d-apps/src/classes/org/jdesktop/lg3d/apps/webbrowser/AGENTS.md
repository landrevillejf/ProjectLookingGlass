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
| Persistence | Jackson JSON under `~/.lg3d/webbrowser/` (override with `-Dlg3d.webbrowser.dir`) |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **Model (JavaFX-free, AWT-free, headless-tested)** — `UrlNormalizer` (smart
  address bar: scheme passthrough / `https://` for host-like text / search
  otherwise), `SearchEngine` (DuckDuckGo default, Google, Bing, Startpage),
  `Bookmark` + `BookmarkStore`, `HistoryEntry` + `HistoryStore` (capped,
  de-duplicated, transient-scheme-filtered), `TabModel` (+ nested `Tab`),
  `BrowserSettings`, `DownloadRecord`, and `BrowserStore` (the one Jackson
  persistence facade; defensive reads never throw).
- **FxBrowser** — the only class that touches `javafx.scene.web`. Owns one
  `WebView`/`WebEngine` per tab (keyed on `TabModel.Tab` id), wires location /
  title / `LoadWorker` progress + state / `createPopupHandler` listeners, applies
  settings (JavaScript, user agent, cookies via `java.net.CookieManager`), does
  zoom, find-in-page (`window.find`), view-source (renders `outerHTML` as text in
  a new tab) and best-effort downloads. Every mutator marshals through
  `Platform.runLater`; state changes report back through a `Listener` on the FX
  thread.
- **BrowserPanel** — the Swing face (public no-arg constructor, as
  `Desktop2DAppRegistry.createPanel` requires): a tab strip, a navigation toolbar
  (back/forward/reload/stop/home, smart URL field, bookmarks menu, find, zoom,
  view-source, downloads, history, settings), a `JFXPanel` centre hosting the
  `FxBrowser` scene, and a status/progress bar with an HTTPS indicator. Building
  the `JFXPanel` on the EDT boots the JavaFX toolkit; FX work is marshalled with
  `Platform.runLater` and callbacks return to the EDT via
  `SwingUtilities.invokeLater`.
- **WebBrowserApp** — a top-level `JFrame` hosting `BrowserPanel`
  (`DISPOSE_ON_CLOSE`, releasing the FX engines on close). This is the child
  process the 3D preview spawns, and it runs standalone too.
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
  `DownloadRecordTest`, `BrowserStoreTest`) run headless (`java.awt.headless=true`)
  and never build a `WebView`/`JFXPanel`. `Desktop2DAppRegistryTest` asserts the
  command classifies as `PANEL` and maps to `BrowserPanel`. The GUI itself has no
  coverage/mutation gate; verify it with the in-JVM probe + `lgscreen-*.png`
  capture on the host X display (2D MDI browser navigates; 3D preview + child
  process launch). A black capture under Wayland is not a defect.
- **Business Analyst** — A daily-driver utility: browse the modern web from the
  desktop without installing a separate browser. Value = tabbed browsing, search,
  bookmarks, history, find, zoom, view-source, JS + cookies, private browsing and
  downloads, surfaced honestly within what WebKit/`WebView` can really do (a full
  download manager and true DevTools are delegated or omitted, not faked).
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

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the split surface (2D `JFXPanel` browser + 3D preview/child
process), the `FxBrowser` JavaFX seam, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump. Stage only intended paths (never `git add -A`).
