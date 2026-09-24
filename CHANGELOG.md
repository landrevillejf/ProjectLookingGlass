# Changelog

All notable changes to this **modernization port** of Project Looking Glass are
documented here. The format follows [Keep a Changelog](https://keepachangelog.com/),
grouped by Added / Changed / Removed / Fixed.

The original 2006 Sun codebase is the baseline; everything below describes the
work to make it build and run on a current toolchain.

## [Unreleased] — 1.10.0-dev — Gradle / JDK 21 modernization

### Added
- **About** (`lg3d-apps`, `org.jdesktop.lg3d.apps.about`) — a new *Utilities*
  start-menu application showing the product identity, the resolved build
  version, the host runtime facts (Java 3D provider, Java version/vendor,
  platform) and the attribution / licence text. Following the
  `Calculator`/`HelpCenter` pattern it splits into a headless model
  (`AboutInfo`), a plain Swing panel (`AboutPanel`) and a 3D wrapper (`About`,
  via `TitledSwingWindow`), so the same panel is hosted on a `SwingNode` in the
  3D desktop and as an MDI internal frame in the 2D/Swing desktop (registered in
  `Desktop2DAppRegistry.PANEL_APPS`). The version is not hardcoded: it resolves
  from a new `lg.version` system property (set by `:lg3d-core:run` to the
  canonical `project.version`), falling back to the lg3d-apps jar manifest
  `Implementation-Version` (stamped from `project.version`) and then `unknown`.
  Covered by headless JUnit 5 tests (`AboutInfoTest`, `AboutPanelTest`, plus a
  new `Desktop2DAppRegistryTest` case).

- **Window snapping for the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — dragging an application window
  to a desktop edge snaps it on release: the left/right edges tile the window to
  that half and the top edge maximises it, with a translucent preview overlay
  showing the target rectangle while the pointer is in the edge zone (48px by
  default, configurable). The behaviour is added by replacing the desktop pane's
  single-icon `DesktopManager` with a `SnappingDesktopManager` that extends
  `DefaultDesktopManager`, so minimise-still-hides-the-desktop-icon is preserved.
  The logic splits into a pure, headless-testable `WindowSnap` (edge-zone
  detection + snap rectangles), a `SnapPreview` (translucent highlight painted on
  the pane's palette layer) and the `SnappingDesktopManager` glue; because
  `DefaultDesktopManager.dragFrame` renders through `Graphics.copyArea` /
  `setXORMode` and needs a realized pane, the manager exposes the decision steps
  as `updateSnap` / `takePendingZone` / `applyPendingSnap` so the tests drive the
  snap logic headless without invoking super's rendering. Covered by headless
  JUnit 5 tests (`WindowSnapTest`, 12 tests; `SnapPreviewTest`, 6 tests;
  `SnappingDesktopManagerTest`, 11 tests).
- **Window switcher for the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — a keyboard window cycler that
  raises a translucent overlay listing the open application windows in
  most-recently-used order and steps a highlight through them. The trigger is
  **Alt+` (Alt+grave)** forward and **Alt+Shift+`** backward — deliberately
  *not* Alt+Tab: the 2D desktop is an ordinary window under the host window
  manager (GNOME/Mutter, Xwayland), which grabs Alt+Tab, Super+Tab and the
  Ctrl+Alt+arrow workspace keys before they reach the JVM, so those never
  arrive. Alt+grave is the conventional in-application window-cycle shortcut,
  is not reserved by the common host WMs and clashes with no Swing/MDI default.
  Because modifier-release detection is unreliable across platforms, the
  selection commits itself on a short idle timer once the user stops pressing
  (no separate confirm key, and no global binding is placed on Escape/Enter).
  The logic splits into a pure, headless-testable `WindowCycler` (MRU tracking +
  cycle state machine) and a `WindowCyclerOverlay` (key bindings + painting on
  the desktop pane's popup layer) driven by a fakeable `WindowSource` seam;
  `Desktop2D` installs it, feeds it MRU touch/forget events from `track()`, and
  gains a non-toggling `focusWindow()` so committing always raises the window
  rather than minimising it. Covered by headless JUnit 5 tests
  (`WindowCyclerTest`, 15 tests; `WindowCyclerOverlayTest`, 11 tests).
- **Session restore for the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — the desktop now remembers which
  application windows were open when it last exited and reopens them, at their
  saved size and position and in their saved minimised/maximised state, on the
  next start. The session is captured on every window open/close and once more on
  exit (so the final placement is what is stored), and is persisted through the
  same user `java.util.prefs` store the desktop configuration and widget layer
  use, so it survives a restart. Restoring relaunches each saved app quietly
  (a failure is logged, never shown in a modal, so an app that has since become
  unavailable cannot greet the user with a dialog at startup) and clamps every
  restored window back on-screen, so a session saved on a larger or differently
  arranged monitor never strands a window off-screen; windows are relaunched
  back-to-front so the original stacking order is preserved. Only windows with a
  start-menu descriptor command (the panel apps) are saved, so an ad hoc window
  that cannot be relaunched is skipped rather than persisted unrestorable. The
  logic splits into pure, headless-testable pieces — `WindowRecord` (one window's
  identity, bounds and state, plus the on-screen clamping), `SessionSnapshot`
  (the ordered set and its tolerant single-string encode/decode, where a corrupt
  or partial value degrades to empty instead of throwing) and `SessionManager`
  (capture plus save/load) behind a fakeable `SessionStore` seam — with
  `PrefsSessionStore` the thin preferences-backed implementation and `Desktop2D`
  the relaunch glue. Covered by headless JUnit 5 tests (`WindowRecordTest`,
  12 tests; `SessionSnapshotTest`, 9 tests; `SessionManagerTest`, 10 tests).
- **Notification area & toasts for the 2D/Swing desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — a taskbar notification tray and
  transient toast popups, the 2D desktop's counterpart of a system notification
  area. A new taskbar button ("Notifications", carrying a live unread badge)
  opens a popup listing the notifications raised so far — newest first, each
  tinted by severity, click to dismiss one, "Clear all" to empty the log — and
  opening it marks everything read. Raising a notification also pops it as a
  toast card stacked up from the bottom-right of the desktop, which fades on its
  own after a few seconds or dismisses on a click. The toast overlay spans the
  desktop pane's popup layer but overrides `contains(x, y)` to claim only the
  pixels a card covers, so a lingering toast never steals clicks from the windows
  below. As the first real producers, launching a `SWING_FRAME` or `EXTERNAL` app
  — which opens *beside* the desktop with no taskbar button of its own — now
  raises an info notification so the user sees it started. Following the
  codebase's headless-testable split, the logic lives in pure, clock-injectable
  classes (`Notification` value + severity `Kind`, the capped observable
  `NotificationModel` log, the expiring `ToastQueue`, the `NotificationColors`
  palette) apart from the Swing glue (`ToastLayer` overlay, `NotificationTray`
  button/popup); `Desktop2D` owns the model and overlay, installs the overlay on
  the popup layer, exposes `raiseNotification()` (plus a static
  `postNotification()` that no-ops when the 2D desktop is not running, mirroring
  `applyDesktopConfig()`) and hands the model to the taskbar. Covered by headless
  JUnit 5 tests (`NotificationTest`, 6; `NotificationModelTest`, 12;
  `ToastQueueTest`, 8; `NotificationColorsTest`, 3; `ToastLayerTest`, 17 incl.
  geometry/wrap/ellipsize and a `BufferedImage` paint smoke; `NotificationTrayTest`,
  5 — 51 tests total).
- **Automated release train** (`ci`, `.github/workflows`, `scripts/release`) — a
  turnkey, event-driven release lifecycle so shipping a version needs no manual
  git/tag/notes work beyond a single approval click. A new `release-train.yml`
  orchestrator keeps exactly one open GitHub **milestone** titled with the next
  version (derived from the Conventional Commits merged since the last stable tag:
  `feat`→minor, `fix`→patch, `BREAKING`→major), attaches every PR merged to `main`
  to it, and — the moment that milestone is complete (no open items) — auto-cuts a
  **release candidate**: it opens a `release/X.Y.Z` PR that dates the CHANGELOG and
  drops the `-dev` suffix from the four canonical version refs, then pushes
  `vX.Y.Z-rc.N`, which `release.yml` builds and publishes as a GitHub
  **pre-release** (never `releases/latest`, so the update-manager is untouched by
  an RC); further pushes to the branch re-cut `rc.N+1`. The one manual step is
  merging the release PR, which promotes it: the train tags the final `vX.Y.Z`
  (published as the latest release + `version.json`), closes the milestone and
  auto-opens the follow-up `chore: bump to <next>-dev` PR that re-opens the dev
  cycle. Release bodies are generated (`release-notes.sh`) from the curated
  CHANGELOG section plus an attributed appendix of the merged PRs and closed
  milestone issues, degrading gracefully when `gh` is unavailable. All decision
  logic lives in a unit-tested shell library (`scripts/release/lib.sh` +
  `next-version.sh` / `prepare-release.sh` / `reopen-dev.sh`) covered by 63
  self-tests (`scripts/release/tests/run.sh`) that run in a throwaway fixture repo,
  wired into CI via a new `release-ci` job (YAML-parse + `bash -n` + ShellCheck +
  self-tests) in `build.yml`; every stage also has a `workflow_dispatch` override,
  and idempotency + a daily schedule make triggering race-free. Tag/branch pushes
  use a `RELEASE_PAT` secret so the hand-off to `release.yml` fires (GitHub's
  anti-recursion guard ignores `GITHUB_TOKEN`-pushed tags). Documented in
  `docs/release-process.md`.

### Changed
- **Copyright attribution** — corrected the source-file headers across the tree
  so the modernization work is credited to its actual author instead of the
  inherited upstream notice. Authorship is taken from git history: every file
  added after the initial upstream import (`26e7ee1`) now carries
  `Copyright (c) 2026, Jean-Francois Landreville` — the from-scratch
  `lg3d-widgets` and `db-manager` modules plus the 70 post-import application
  files in `lg3d-apps` (calculator, controlcenter, filemanager, taskmanager,
  mediawriter, paint, dbmanager, update, swingide, `TitledSwingWindow`, most of
  help). Files that arrived in the cloned 2006 Sun base keep Sun's original
  notice (as the GPL requires) with an added
  `Portions Copyright (c) 2026, Jean-Francois Landreville` line for the
  Gradle/JDK 21 modernization and improvements. `lg3d-docs/**` is left
  untouched (historical, do-not-update). Comment-only; no functional change.

## [1.9.0] — 2026-09-24 — Gradle / JDK 21 modernization

### Added
- **IDE** (`lg3d-apps`, `org.jdesktop.lg3d.apps.swingide`) — the external
  **swing-ide** project (a full-featured modular Java Swing IDE: multi-language
  code editor, Maven/Gradle/Ant build tools, Git, debugger, database explorer,
  plugin system) integrated as a *Developers* start-menu app. Unlike every other
  desktop app, the IDE is **not** loaded into the desktop JVM: it ships as a
  self-contained fat jar (`libs/swing-ide.jar`, built by swing-ide's own Gradle
  9.7.1 build/CI and fetched on demand via a new `:fetchSwingIdeJar` task, since
  its ~138 MB exceeds GitHub's 100 MB per-file limit) and the new `SwingIde`
  launcher forks it as a **separate child process** (`java -jar`) on the lg3d
  display. The isolation is load-bearing: the IDE calls `System.exit` when its
  main window closes (which would otherwise tear down the desktop) and its fat
  jar bundles unrelocated third-party libraries (slf4j, logback, Jackson,
  JFreeChart) that could clash with the desktop classpath — neither can reach
  the desktop JVM because the jar is never on it. The child's own `JFrame`
  therefore appears as an ordinary top-level window: composited over the 3D
  scene and beside the 2D/Swing desktop. `swingide.lgcfg` registers the menu
  item with the `java <class>` verb and a scaled `swing-ide.png` icon; the jar
  path is resolved from a new `swingide.jar` system property (set by
  `:lg3d-core:run` and the release `lg3d.sh`), then `<lg.appcodebase>/libs`, then
  the working directory, degrading to a readable "unavailable" message when
  absent. `Desktop2DAppRegistry` classifies the launcher as a `SWING_FRAME` app
  so the 2D desktop runs its (child-forking) main beside the desktop. Covered by
  a new headless JUnit 5 suite (`SwingIdeTest`, 12 tests pinning the jar-path
  precedence, child-command shape and display selection without ever spawning a
  process) and a `Desktop2DAppRegistryTest` classification case.
- **Database Manager** (`db-manager`, `org.jdesktop.lg3d.apps.dbmanager`) — a new
  standalone, **driver-agnostic JDBC database client** styled after DBeaver, added
  as a *Developers* start-menu app. The `db-manager` module is a plain Java 21 /
  Swing library (Maven layout, **no `lg3d-core` dependency**, mirroring
  `update-manager` / `lpm-console`) layered `model` → `jdbc` → `session` → `ui`:
  connection-profile CRUD with a **test** action and JSON persistence under
  `~/.lg3d/dbmanager/` (Jackson; passwords **not** saved by default, and only
  *obfuscated* — not encrypted — on explicit opt-in, with a warning); a metadata
  navigator that lazily reads catalogs/schemas/tables/views/columns/PK/FK through
  the standard `DatabaseMetaData`; a multi-tab SQL editor with syntax highlighting
  and multi-statement splitting; **asynchronous** execution off the EDT with
  `Statement.cancel()` stop, `fetchSize` paging and a row cap; a read-only results
  grid (paging + sort) with CSV export; a CREATE TABLE DDL viewer; and transaction
  control (auto-commit toggle, commit, rollback). It bundles the SQLite + H2
  (embedded, offline) and PostgreSQL + MariaDB JDBC drivers, and supports a
  user-added custom driver/JAR (`URLClassLoader` + shim) for any other engine.
  Inline grid cell-editing is intentionally deferred — `DmlBuilder` is implemented
  and tested to back it, but the grid is read-only and edits go through the SQL
  editor. On the desktop, `DbManagerPanel` (a plain `JPanel` with a non-throwing
  constructor that degrades to an "unavailable" pane on `LinkageError`) embeds the
  module's `DbManagerMainPanel` and is hosted on a `SwingNode` inside a `Frame3D`
  via `TitledSwingWindow` in 3D and, through a new `Desktop2DAppRegistry` panel
  mapping, as an MDI internal frame in the 2D/Swing desktop; `dbmanager.lgcfg`
  registers the menu item and a generated `dbmanager.png` icon. Because the
  `:lg3d-core:run` and `:lg3d-core:releaseBundle` classpaths are hand-assembled,
  both add the `db-manager` jar plus a detached configuration carrying its runtime
  deps (jackson-databind, slf4j-api/nop and the four bundled JDBC drivers). SQLite,
  H2, PostgreSQL and the MariaDB client were added to the version catalog.
  Covered by a new headless JUnit 5 suite in `db-manager` (132 tests running **real
  JDBC** against in-memory H2 / temp-file SQLite across the model, jdbc, session
  and ui layers), a `Desktop2DAppRegistryTest` classification case and a new
  headless `DbManagerPanelTest`; a conservative `db-manager` coverage floor is
  pinned in the root build and report-only PIT is wired. New `db-manager/AGENTS.md`
  and `dbmanager` per-app `AGENTS.md` document the module and host shim.
- **Desktop background context menu** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d`) — right-clicking the 2D/Swing
  desktop wallpaper (anywhere not covered by an app window or widget) now opens a
  conventional desktop context menu. A new Java 3D-free static builder,
  `Desktop2DContextMenu`, assembles the `JPopupMenu` driven entirely by an
  `Actions` callback (the same testable pattern as `Desktop2DStartMenu` /
  `Desktop2DFolderMenu`), so the menu structure and wiring are unit-tested without
  a live desktop. It offers launchers (**Open Terminal** — shown only when a
  terminal executable is installed — and **Open File Manager**), personalisation
  (**Change Wallpaper**, a submenu enumerating the bundled `resources/images/background`
  backdrops with a hard-coded fallback when the directory cannot be listed, e.g.
  running from a jar, plus **Desktop Settings...** opening the Control Center),
  window arrangement (**Cascade** / **Tile** / **Minimize All** / **Restore All**,
  gated off when no windows are open) over the MDI `JDesktopPane`, and session
  (**Refresh**, **Exit...**). `Desktop2D` registers the popup trigger on both
  press and release (which one fires is platform-specific) and rebuilds the menu
  each time so the arrangement entries reflect the windows currently open.
  Covered by a headless JUnit 5 test (`Desktop2DContextMenuTest`, 10 tests)
  asserting the entry order/separators, the conditional Terminal entry, the
  wallpaper submenu contents and placeholder, the arrangement gating, the
  callback wiring and the `isImage`/`displayName` helpers.
- **Office apps in the 2D/Swing desktop** (`lg3d-incubator`, `lg3d-core`) — the four
  pure-3D *Office* start-menu apps (Mail 3D, Agenda 3D, Contact 3D, Chart 3D) now
  launch in the 2D/Swing desktop instead of appearing disabled with a "Requires the
  3D desktop" tooltip. Each gains a plain-Swing `JPanel` counterpart in
  `lg3d-incubator` — `MailPanel`, `AgendaPanel`, `ContactCardsPanel` and `ChartPanel`
  — registered in `Desktop2DAppRegistry.PANEL_APPS` on the app's existing 3D main
  class, so the *same shared `.lgcfg` descriptors* now flip from `UNAVAILABLE` to
  `PANEL` in the 2D menu with no descriptor, icon or run-classpath change (the
  incubator jar is already on the `:lg3d-core:run` classpath, so the reflective
  lookup resolves — the same precedent as `WidgetGalleryPanel` and `LPMConsolePanel`).
  The panels are idiomatic, keyboard-editable Swing (folder combo + `JList` + compose
  card; custom-painted week grid with title/day/hour/duration/attendee editing;
  contact list + detail card; `JTree` org hierarchy + name query) that reuse each
  app's AWT-free model and the **same shared user `Preferences` stores**
  (`/mail/messages`, `/agenda/appointments`, `/contacts`), so state written in one
  desktop is visible in the other. None loads a Java 3D class, so a 3D-less JVM still
  runs them; the week-grid constants are duplicated in `AgendaPanel` rather than
  referenced from the `Component3D` `AgendaGrid`. A new `lg3d-incubator` JUnit 5 test
  source set exercises all four panels headless (25 tests) against the ephemeral CI
  `Preferences` root, `Desktop2DAppRegistryTest` asserts the four commands classify as
  `PANEL` and map to their panel FQNs, and a conservative `lg3d-incubator` coverage
  floor is pinned in the root build. The `mail` and `orgchart` per-app `AGENTS.md`
  UI/UX rows now document both surfaces.
- **Games in the 2D/Swing desktop** (`lg3d-incubator`, `lg3d-core`) — the four
  pure-3D *Games* start-menu apps (Tic-Tac-Toe 3D, Sudoku 3D, Chess 3D, Solitaire 3D)
  now launch in the 2D/Swing desktop instead of appearing disabled with a "Requires
  the 3D desktop" tooltip. Each gains a plain-Swing `JPanel` counterpart in
  `lg3d-incubator` — `TicTacToePanel`, `SudokuPanel`, `ChessPanel` and
  `SolitairePanel` — registered in `Desktop2DAppRegistry.PANEL_APPS` on the game's
  existing 3D main class, so the *same shared `.lgcfg` descriptors* now flip from
  `UNAVAILABLE` to `PANEL` in the 2D menu with no descriptor, icon or run-classpath
  change (the incubator jar is already on the `:lg3d-core:run` classpath, so the
  reflective lookup resolves — the same precedent as `WidgetGalleryPanel` and
  `LPMConsolePanel`). The panels are idiomatic Swing (a 3x3 button grid; a 9x9 grid of
  keyboard-editable fields with a difficulty combo and conflict highlighting; a
  custom-painted 8x8 click-to-move board with Unicode piece glyphs; a custom-painted
  Klondike table with click-to-select / click-to-move and double-click-to-foundation)
  that reuse each game's **AWT-free engine** (`TicTacToeModel`, `SudokuModel`,
  `ChessModel`, `SolitaireModel`), so rules and play are identical across both
  desktops. None loads a Java 3D class, so a 3D-less JVM still runs them. A new
  `lg3d-incubator` JUnit 5 test source set exercises all four panels headless
  (42 tests), `Desktop2DAppRegistryTest` asserts the four commands classify as
  `PANEL` and map to their panel FQNs, a conservative `lg3d-incubator` coverage floor
  is pinned in the root build, report-only PIT is wired, and the `games` per-app
  `AGENTS.md` Surface / UI-UX rows now document both surfaces.
- **Per-module role guides** (`AGENTS.md`) — every built module now ships a
  role-aware `AGENTS.md` following one shared template so all roles read each
  other's guidance coherently: *Module at a glance*, *How the roles work
  together*, and dedicated **Architect / Engineer-Developer / QA / Business
  Analyst / Functional Analyst / Project Manager** sections, plus a **UI/UX
  (3D & 2D)** section for every module with a user interface (marked *not
  applicable* for the `lg3d-escher` protocol library), a *Communication &
  coherence* rule and the module-scoped *Commit / PR* flow. New files for
  `lg3d-escher`, `lg3d-apps`, `lg3d-incubator`, `lg3d-widgets` and
  `lpm-console`; `lg3d-core` and `update-manager` gain the same role sections
  while keeping their existing content (`lg3d-core` remains the canonical UI/UX
  rulebook every module defers to). The root `AGENTS.md` replaces its "consider
  creating" note with a *Module AGENTS.md index & shared role model* table.
  Documentation only — no code, build or version change.
- **Per-application role guides** (`AGENTS.md`) — extending the per-module effort,
  *every* application package in the two app modules now ships its own condensed
  role-aware `AGENTS.md` beside its sources: 18 in `lg3d-apps` and 35 in
  `lg3d-incubator` (native-3D showcases, ported apps, dormant prototypes,
  framework/library trees, and the **excluded** apps). Each uses the shared role
  template with an *App at a glance* table whose first row is a **Status**
  classifier (**Production** / **Production-grade** / **Sample-Tutorial** /
  **Dormant prototype** / **Excluded from build**), plus entry point, window
  surface, start-menu descriptor location and runtime blockers. Multi-app package
  trees are covered by one guide at the package root (`games/`, `orgchart/`). The
  existing 13 `lg3d-apps` per-app files were converted to the template with
  their original in-depth reference preserved underneath. The root `AGENTS.md` and
  both module files gained a *Per-app guides* index note, and `lg3d-apps` was
  reframed to make clear the module name is legacy while its apps are
  production-grade. Documentation only — no code, build or version change.
- **Software Update** (`update-manager`, `org.jdesktop.lg3d.apps.update`) — a
  self-contained Swing update pipeline adapted from an external module and
  integrated as a *Utilities* start-menu app. The module checks a release
  endpoint for a newer version, downloads and verifies it (SHA-256 plus optional
  OpenPGP detached signature via Bouncycastle), backs up the current install,
  installs, and offers rollback / downgrade / scheduling, a channel model
  (stable / beta / nightly), a system-tray notifier, a changelog viewer and a
  settings form. The adaptation is deliberately minimal: the original
  `com.protonmail.landrevillejf.swingide.update` package, Lombok and slf4j are
  kept, and only swing-ide-specific identifiers and user-facing strings were
  re-pointed at Project Looking Glass (config dir `~/.lg3d/`, `LG3D_UPDATE_TOKEN`,
  `lg3d.version`, the release `version.json` URL, "Project Looking Glass"
  wording). The removed `:ide-core` dependency is replaced by a local synchronous
  exact-type `EventBus`; Jackson, Bouncycastle, Lombok, Mockito and AssertJ were
  added to the version catalog, and a build-time `generateVersionFile` task feeds
  `ApplicationVersion`. On the desktop, `UpdateManagerPanel` (a plain `JPanel`)
  embeds the module's settings form behind an `UpdatePresenter` and is hosted on a
  `SwingNode` inside a `Frame3D` via `TitledSwingWindow` in 3D and, through a new
  `Desktop2DAppRegistry` panel mapping, as an MDI internal frame in the 2D/Swing
  desktop; `updatemanager.lgcfg` registers the menu item, and the
  `update-manager` jar plus its runtime deps are added to the hand-assembled
  `:lg3d-core:run` classpath. Covered by the module's headless JUnit 5 suite, a
  `Desktop2DAppRegistryTest` classification case and a new headless
  `UpdateManagerPanelTest`. A new `.github/workflows/release.yml` publishes the
  `version.json` manifest (and the signed `lg3d-<version>.zip` bundle) to the
  GitHub Releases `latest` redirect that `update.url` targets, so a live check
  now resolves instead of degrading to `UpdateServerUnavailableException`.
- **Release workflow** (`.github/workflows/release.yml`) +
  **`:lg3d-core:releaseBundle`** task — publishes the update metadata the Software
  Update app checks for. On a pushed `v*` version tag (the normal way to cut a
  release), a published Release, or `workflow_dispatch` for a tag, it builds and
  tests the tree, assembles `lg3d-<version>.zip` (the module
  jars, their third-party runtime dependencies, the assembled `resources/` tree,
  the `etc/` config tree, `ext/app` and a `lg3d.sh` launcher), computes its size +
  SHA-256, optionally signs it with an armored detached PGP signature (`.zip.asc`,
  the format `UpdateSignatureVerifier` reads) when the `RELEASE_SIGNING_KEY` /
  `RELEASE_SIGNING_PASSPHRASE` secrets are set (publishing the public key as
  `public-key.asc`), generates the `version.json` manifest
  (`UpdateRepository.parseUpdateInfo` schema) and uploads `version.json`,
  `changelog.md` and the bundle to the Release. The `push: tags: v*` trigger means
  CI now cuts the release end-to-end — the publish step `gh release create`s the
  GitHub Release when the tag does not yet have one, so a downstream consumer
  (e.g. an LFS host, or the Software Update app) fetching
  `.../releases/latest/download/version.json` resolves as soon as the tag is
  pushed, without anyone first clicking "Publish release" in the UI. Signing is
  secret-driven and never
  committed; without the secrets the release is published unsigned and the
  client's default checksum-only verification applies.
- **LPM Console** (`lpm-console`, `org.lpmconsole`) — a graphical package-manager
  front-end for LPM (the BLFS package manager), delivered as a standalone Java 21
  Swing module and wired into the lg3d desktop start menu under the *System*
  group. It is a real GUI (category rail over a sortable, searchable package
  table with a details pane, action toolbar, collapsible log and status bar),
  not a terminal: package listings are read read-only from LPM's database
  (`/var/lib/lpm`), while every mutation shells out to `/usr/bin/lpm` with
  `--no-color`, previews with `--dry-run`, and confirms destructive actions in
  an in-panel overlay (modal dialogs escape the SwingNode capture). It honours
  the LPM Control Application Contract (`lpm-lg3d-app-contract.md`): no
  re-implementation of dependency resolution, checksums, locking or rollback.
  The panel is registered with `Desktop2DAppRegistry` so it is hosted as an MDI
  internal frame in the 2D/Swing desktop, and its `swingapp` descriptor lets
  `SwingNodeWindowCapture` texture its window into the 3D desktop; its jar is
  added to the `:lg3d-core:run` classpath and a drawn package-box start-menu
  icon (`lpm-console.png`) is generated.
- **Help Center** (`lg3d-apps`, `org.jdesktop.lg3d.apps.help`) — a complete,
  navigable desktop user guide built on the latest **JavaHelp**
  (`javax.help:javahelp:2.0.05`), replacing the old static-image *Simple Sample
  Help* as the desktop's real documentation (that sample is left in place as a
  scene-graph demo). A `JHelp` viewer (Contents / Index / full-text Search) is
  embedded in a plain-Swing `HelpCenterPanel`, hosted on a `SwingNode` inside a
  `Frame3D` via `TitledSwingWindow` in the 3D desktop and, through a new
  `Desktop2DAppRegistry` panel mapping, as an MDI internal frame in the 2D/Swing
  desktop. The HelpSet ships fourteen authored HTML topics (overview, getting
  started, desktop tour, windows, start menu, taskbar, widgets, gestures, the 2D
  desktop, built-in apps, customizing, package management, troubleshooting and
  about) with a shared stylesheet, a target map, a hierarchical TOC and a keyword
  index; the Search navigator's database is generated at build time by JavaHelp's
  own indexer (new `:lg3d-apps:generateHelpSearchIndex` task) and bundled
  beside the HelpSet. JavaHelp is added to the version catalog, to
  `lg3d-apps`, and (as a detached configuration) to the hand-assembled
  `:lg3d-core:run` classpath so the in-JVM launch resolves `javax.help.*`. A new
  `helpcenter.lgcfg` registers it under the *Utilities* start-menu group. Covered
  by a headless JUnit 5 test (`HelpContentTest`, new `lg3d-apps/src/test`
  source set) asserting the HelpSet parses with the expected title and all three
  navigators, every map target resolves to a topic URL, and `JHelp` constructs
  under JDK 21; the 2D classification is covered in `Desktop2DAppRegistryTest`.
- **Gradle build** (wrapper 8.14) replacing the 2006-era Ant `source 1.5` build,
  with a JDK 21 toolchain. Modules: `lg3d-escher`, `lg3d-core`, `lg3d-apps`,
  `lg3d-incubator`; jars are emitted to `<module>/build-gradle/libs/` so the
  legacy per-module `build`/`clean` scripts are left untouched.
- **`run-lg3d.sh`** launcher at the repository root — auto-detects/pins the JDK 21
  toolchain, defaults `DISPLAY`, and starts the desktop. Options: `-2`
  (conventional Swing 2D desktop, MDI internal frames), `-w` / `--swing` (Swing
  desktop, MDI internal frames + Metal look and feel), `-b` (3D `pinguin.j3f`
  background), `-x` (X11 compositor/WM mode), `-c` (clean first), `-r`
  (reassemble runtime resources), `-h` (help), and `--` pass-through to Gradle.
- **`lg3d-core:run`** task (`JavaExec`) launching `org.jdesktop.lg3d.displayserver.Main`
  in development mode (`lg.fws.mode=dev`) with the AWT foundation window system,
  pinned to the JDK 21 launcher. Accepts `-Pbackground3d` to opt into the 3D model
  background and `-Pcompositor` to run lg3d as its own X11 window
  manager/compositor.
- **Non-3D (2D) fallback desktop** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d` + `DesktopMode`) — a machine with
  no Java 3D, or with the jars present but no working GL context, no longer dies
  at boot with `SevereRuntimeError`. `Main` now resolves the desktop mode through
  a pure, unit-tested `DesktopMode`: `lg.fws.mode=2d` forces the Swing desktop,
  `lg.fws.mode=3d` keeps the historical fail-loudly 3D boot, and any other value
  (including the default `dev`) **probes** the machine — Java 3D on the classpath
  and a 3D-capable `GraphicsConfiguration`, both reflectively — falling back to
  2D only after a modal confirmation dialog ("3D unavailable: … Start in 2D
  mode?"; **Exit** preserves today's error). A `Throwable` escaping
  `new ServerHandler()` (classes present, GL init fails) re-runs the same
  resolver, and `lg.2d.simulateNo3D=true` forces the fallback branch so the
  dialog is exercisable on 3D-capable hardware. The 2D shell itself is pure JDK
  Swing and touches no Java 3D, so it also runs where the Java 3D jars are
  missing entirely: one undecorated, maximised `JFrame` holding an MDI
  `JDesktopPane` over the usual wallpaper, with a Swing taskbar (Start button,
  per-window buttons, Documents/Downloads folder menus reusing the 3D-free
  `FolderStackModel`, clock, Exit). Its start menu is built from the *same*
  `.lgcfg` descriptors as the 3D menu — read as plain XML (`Desktop2DMenuConfig`)
  rather than decoded into 3D beans — and classifies each entry
  (`Desktop2DAppRegistry`): panel apps (**File Manager**, **Task Manager**,
  **Control Center**, **Calculator**, **Media Writer**) are hosted in internal
  frames via reflection (so lg3d-core never depends on lg3d-apps nor loads a
  3D wrapper); conventional Swing apps (**Paint**, **Swing Test**) launch in-JVM
  without the 3D window capture; external commands run as child processes as in
  3D; and pure-3D apps appear **disabled** with a "Requires the 3D desktop"
  tooltip. Two latent 3D couplings that would crash apps in 2D were also fixed:
  the Control Center omits its Appearance/Desktop panels when `lg.desktop2d` is
  set, and `PaintApp` tolerates a missing hosted look-and-feel. Selected with
  `run-lg3d.sh -2` / `-Pdesktop2d`. Covered by JUnit 5 tests in a new
  `lg3d-core/src/test/java` source set (mode resolver, descriptor reader, app
  registry, folder-menu listing); the 3D boot path is otherwise unchanged.
- **Swing desktop flavour (`--swing`)** (`lg3d-core`,
  `org.jdesktop.lg3d.displayserver.desktop2d.DesktopSwing`) — the same MDI
  desktop as `--2d`, wearing the **Metal** look and feel: one maximised window
  whose `JDesktopPane` hosts each application in a `JInternalFrame`, so windows
  stay integrated with the desktop and minimise into it rather than escaping to
  the host taskbar. (`--2d` keeps the host system look and feel.) A real
  top-level `JFrame` cannot be a child of a `JDesktopPane` and iconifies into the
  host window manager, outside the desktop, so `JInternalFrame` — stock Swing's
  MDI window — is the correct component here. `DesktopSwing extends Desktop2D`
  and adds only the Metal look and feel and its own window title: the shell,
  wallpaper `JDesktopPane`, internal-frame hosting and de-duplication, start
  menu, Documents/Downloads folder menus, taskbar, external-command and
  conventional-Swing-app launches and exit handling are all inherited unchanged,
  so nothing is duplicated. Selected with `lg.fws.mode=swing` (`run-lg3d.sh -w` /
  `--swing`, equivalently `-PdesktopSwing`); `DesktopMode` resolves it as an
  explicit, never-confirmed choice and the automatic no-3D fallback still lands
  on the MDI 2D desktop, so `--2d` is unchanged. Covered by `DesktopModeTest`;
  the internal-frame hosting under Metal was verified at runtime (panel apps open
  as `JInternalFrame`s inside the desktop pane and minimise into it, Metal is
  active).
- **`lg3d-core:runtimeResources`** task — assembles the legacy top-level
  `resources/` classpath tree from `lg3d-art` (wallpapers, splash, models, GDM
  theme), `lg3d-core` (icons, buttons, default wallpapers) and the incubator
  background manager (`BgConfig.xml`, per-background directories, taskbar icons),
  and wires it onto the `run` classpath so the desktop comes up fully populated.
- **`lg3d-core:generateBuildInfo`** task — reproduces the legacy `LgBuildInfo.java`
  `@TOKEN@` substitution into `build-gradle/generated-src`, keeping the checked-in
  tree clean.
- **In-tree contrib replacements** under `lg3d-core/src/contrib/java` for the
  dropped `j3d-contrib-utils` classes: `Math3D`, the `TreeScan` /
  `NodeChangeProcessor` / `ProcessNodeInterface` traverser, and
  `TransparencyOrderedGroup` / `TransparencyOrderController`.
- **`J3fLoader`** (`org.jdesktop.j3d.loaders.wrappers.J3fLoader`) reimplemented
  against the Jogamp scene-graph IO API, so the `pinguin.j3f` background model can
  be read again (it is loaded reflectively via `Class.forName` in `ModelBackground`).
- **Legacy-name compatibility shims** so pre-existing `.j3f` files (which bake in
  the old class names) deserialise under Jogamp: `javax.media.j3d.AmbientLight`
  and `com.sun.j3d.utils.scenegraph.io.state.javax.media.j3d.AmbientLightState`.
- **Jogamp native runtime dependencies** — GlueGen / JOGL / JOAL 2.6.0
  platform-classifier jars, selected from `os.name`/`os.arch`.
- **X11 compositing integration** (`lg3d-core/.../displayserver/nativewindow/x11/`)
  — lg3d can run as its own X11 **window manager + compositor**, displaying real
  X11 client apps as textured `NativeWindow3D` quads in the 3D scene. Built on new
  pure-Java Escher extension bindings (`X11CompositeExt`, `X11DamageExt`,
  `X11ShmExt`) plus `X11Compositor` (WM takeover + `CompositeRedirectSubwindows`
  + event loop), `CompositeWindowImageLoader` (Damage-triggered pixmap → texture),
  and `X11InputForwarder` (3D pick → XTest pointer/keyboard injection). Opt-in via
  `-Pcompositor` / `run-lg3d.sh -x` (`lgconfig_1p_x_composite.xml`); no JNI, no
  JNA, no patched JDK. Requires a bare Xorg with no other WM already holding
  `SubstructureRedirect`. The legacy native `fws/x11` path stays excluded.
- **Desktop shell: widget framework (`lg3d-widgets`)** — a new in-tree module
  providing a public, pluggable widget API (`org.jdesktop.lg3d.widgets.api`:
  `Widget`, `AbstractWidget`, `WidgetContext`, `WidgetDescriptor`, the
  `WidgetProvider` SPI and `WidgetRegistry`), a desktop widget layer/host
  (`...widgets.host`) that renders draggable widgets whose layout persists to
  `~/.config/lg3d/widgets.properties`, and built-in clock, temperature, CPU and
  memory widgets (`...widgets.builtin`). Third parties add widgets by dropping a
  jar carrying a `META-INF/services/...WidgetProvider` entry; placed widgets are
  managed through the **Widget Gallery** app.
- **Desktop shell: Weather widget** (`lg3d-widgets`, `WeatherWidget`) — a new
  built-in desktop widget showing current conditions from the free
  **Open-Meteo** forecast API (no API key): temperature, a sky glyph drawn from
  the WMO weather code, condition text, location, today's high/low, feels-like,
  humidity and wind. The **mouse wheel** cycles a preset list of major cities
  (persisted) and a **click** toggles &deg;C/&deg;F (defaulted from the system
  locale); a custom `lat`/`lon`/`label` can be pinned in
  `~/.config/lg3d/widgets.properties`. Fetches run on the shared widget
  scheduler thread every 15 minutes (and immediately after a city change) via
  the JDK `java.net.http` client with a small dependency-free JSON reader, so
  neither the EDT nor the 3D event loop is ever blocked; a failed refresh keeps
  the last reading and flags it "stale". Listed in the Widget Gallery under
  **Web**.
- **Desktop widgets on the 2D / Swing desktop** (`lg3d-widgets` +
  `lg3d-core`) — the widgets are no longer 3D-only and their start-menu entry no
  longer says *“Requires the 3D desktop”*. The visual/logic core of each
  built-in was extracted into pure-Swing `WidgetCard`s
  (`...widgets.builtin`: model + tick + `Graphics2D` paint + click handling,
  catalogued by `BuiltinWidgetCards`/`WidgetCardSpec`); the five 3D `*Widget`
  classes are now thin delegates that host the *same* card on a `SwingNode`
  texture, so nothing is duplicated between desktops. A new `SwingWidgetLayer`
  (`...widgets.swing`) renders the cards as draggable Swing components on the
  2D desktop's `JDesktopPane` (above the wallpaper, below application windows),
  shares the 3D host's scheduler and persists to the *same*
  `~/.config/lg3d/widgets.properties`, so a layout placed on one desktop is
  restored on the other. `WidgetGalleryPanel` is the pure-Swing Widget Gallery;
  `Desktop2DAppRegistry` maps the gallery's `.lgcfg` command to it as a panel
  app (hosted in an internal frame), and `Desktop2D` installs/uninstalls the
  layer reflectively — one hook that covers both `--2d` and `--swing`
  (`DesktopSwing extends Desktop2D`), with lg3d-core still carrying no
  dependency on lg3d-widgets and degrading gracefully when the module is absent.
  The 3D widget path is unchanged. Covered by JUnit 5 tests in a new
  `lg3d-widgets/src/test/java` source set (catalog, card behaviour, layer
  persistence/relayout, gallery panel, offscreen render) plus registry tests in
  `lg3d-core`.
- **Desktop shell: dock folder stacks** — Documents and Downloads stacks on the
  taskbar's right side
  (`[Documents] [Downloads] [Background] [Exit]`), each raising **the same
  glassy vertical list the start menu uses for its application groups**
  (`org.jdesktop.lg3d.scenemanager.utils.taskbar.stack`, registered from
  `glassy.lgcfg`): hovering the dock icon raises the list anchored above the
  icon and opening leftward (the stacks sit at the right screen edge), and
  leaving the icon or the list hides it again — including in front of a
  maximized full-width window, through the same eye-distance lift as the app
  list. Once lowered the list leaves nothing above the bar: its row column is
  detached while hidden (a dock stack has no taskbar button for the shrunken
  column to sink into, unlike the start menu's) and re-attached on the next
  raise. Rows are the folder's most-recently-modified entries (newest first,
  capped at 12) drawn with the desktop's own MIME icons (painted once into
  `~/.cache/lg3d/stack-icons/` PNGs, since 3D icon textures load from URLs),
  plus a trailing **Show in File Manager** row; the mouse wheel cycles the rows
  exactly as in the app list. Files open with `xdg-open`; folders and the
  trailing row open the folder in the file manager.
- **Desktop shell: system apps** (`lg3d-apps`) — **File Manager**
  (tree + list browsing with copy / move / rename / delete-to-trash / new-folder,
  multi-select, drag-and-drop, keyboard shortcuts), **Task Manager** (live
  process table from procfs with End Task / Force Quit / Change Priority), and
  **Control Center** (Display via `xrandr` with a timed auto-revert, Users via
  `pkexec`, live System info, and an Appearance wallpaper chooser). All three sit
  under a new **System** start-menu group.
- **Pure-Java Linux system backends** (`org.jdesktop.lg3d.utils.system` in
  `lg3d-core`) — `ProcessRunner` / `PrivilegedRunner` (`pkexec`), `Opener`
  (`xdg-open`, freedesktop trash), `Proc` (procfs readers), `ProcessService`,
  `ThermalService`, `DisplayService` (`xrandr`), `UserService` and
  `SystemInfoService`. No JNI/JNA; every service degrades gracefully (read-only
  or "n/a") when a tool or file is absent.
- **Standard 3D window decoration for all `Frame3D` apps** — a reusable
  `Frame3DWindowDecoration` (`org.jdesktop.lg3d.scenemanager.utils.decoration`)
  is auto-attached by `StandardAppContainer.addFrame3D`, giving every pure-3D
  window (File Manager, Task Manager, Control Center, Widget Gallery and the
  demos) native-style **minimize / maximize / close** buttons plus
  3D rotation: **right-click** on the window's green border flips it over to a
  `StickyNote` back side and **middle-drag** free-spins it. Previously this chrome
  existed only for native X11 windows (`GlassyNativeWindowLookAndFeel`), an excluded
  code path, so
  dev-mode apps had no window buttons and could not be rotated. Frames that build
  their own chrome (e.g. `Lg3dHelp`) opt out via the
  `lg3d.frame3d.decoration.optOut` property.
- **Image Studio** (`lg3d-incubator`, `org.jdesktop.lg3d.apps.imagestudio`) — a
  full-featured image editor with an **lg3d-native 3D UI** (no Swing editing
  surface), built on the **bundled Java Advanced Imaging API** (`javax.media.jai`)
  in `lg3d-incubator/ext`. A `Frame3D` window (with the standard decoration)
  lays out a textured-quad image canvas (mouse-wheel zoom, reset view), a left
  toolbar of categorised operations — **Geometry** (scale, rotate, flip, crop,
  border, pixelate), **Color** (brightness, contrast, gamma, grayscale, sepia,
  invert, posterize, threshold), **Filter** (blur, sharpen, emboss, edge) and
  **Math** (add/subtract/multiply constant, absolute, and/or/xor, noise) — driven
  by a live 3D parameter slider, a log-scaled 256-bin RGB histogram, and a bottom
  filmstrip of `~/Pictures` thumbnails. Bounded undo/redo/reset; images open from
  the filmstrip or a native `JFileChooser`, and save back to the current path or
  export to `~/Pictures/lg3d-imagestudio/` (PNG/JPEG via `ImageIO`, TIFF/BMP via
  the JAI codec). Registered in the start menu (Utilities) by
  `lg3d-apps/src/config/imagestudio.lgcfg`. The `lg3d-core:run` task now
  puts the two genuine JAI jars (`jai_core.jar`, `jai_codec.jar`) on the desktop
  classpath and exports `java.desktop/sun.awt.image` so JAI's `RasterAccessor`
  fast path works under JDK 21.
- **Three more `lg3d-incubator` apps ported, registered and verified launching** —
  apps whose sources merely predated the current core API snapshot were brought up
  to date, built, and confirmed to start on the desktop: **Luncher**
  (`luncher.Luncher1`, a 3D glassy-cube card launcher), **Natural Language
  Control** (`nlc.Main`, a command-driven 3D mascot) and the **org chart** apps
  (`orgchart.ui.chart.Chart3D`, `orgchart.ui.contact.Contact3D`). Each is
  registered in the desktop start menu by a new descriptor under
  `lg3d-apps/src/config` (`luncher`, `nlc`, `orgchart-chart`,
  `orgchart-contact`), following the Image Studio precedent. The compile-time
  drift fixed was small and self-contained: vecmath's dropped
  `Color3f/Color4f(java.awt.Color)` constructors,
  `AppLaunchAction(String,ClassLoader)` /
  `Pseudo3DShortcut(URL,String,ClassLoader)` signatures,
  `SimpleAppearance.setTexture(URL)`, and `FuzzyEdgePanel.setSize(float,float,float,float)`.
  Making them actually *run* also required fixing legacy resource/API drift that
  only surfaces at launch: luncher's `MenuConfigFileReader` falls back to the
  `MenuConfigFile.xml` bundled beside the class and its menu icon now resolves
  from the bundled `GlassyCardIcon.png` (the legacy `etc/lg3d/` and
  `resources/images/icon/` install paths are not populated by this port);
  `Luncher1.setShortcuts` no longer calls `Container3D.setLayout` on an
  already-populated container (the modern API rejects that — `MenuConfigFileReader`
  sets the layout while the container is still empty); nlc's `StanfordFactory`
  loads its `englishPCFG.ser.gz` grammar model from the jar (copying it to a temp
  file) and `Main`/`knowledge.xml` point at the bundled `conf/` resources rather
  than the absent `/etc/lg3d` paths; and `Chart3D.upLevel` guards on
  `numChildren > 1` before reading `getChild(1)` (child 0 is the Up button), so a
  stray keypress at the top level no longer throws. Runtime dependencies are
  supplied by putting the *specific* bundled `ext` jars these apps need on the
  `lg3d-core:run` classpath — `nanoxml-lite` + `javanlp` (nlc), `prefuse`
  (orgchart) and the two JAI jars (Image Studio) — deliberately **not** the whole
  `ext` tree, because `ext/axis/xercesImpl.jar` registers itself as the JAXP
  `DocumentBuilderFactory` and references `org.w3c.dom.ls.DocumentLS` (long
  removed from the JDK), which breaks `java.util.prefs` and hence `DesktopConfig`
  and the background manager on every desktop. `wilkoaim3d` remains excluded: it
  needs a whole removed 2004-era utility vocabulary (`Frame3DToFrontEvent`,
  `ComponentMover`, `ResilientRotateAction`, `NaturalMotion*`,
  `ColorAlphaChangeAction`) and its AOL AIM backend was discontinued in 2017, so
  it could never run — its exclusion rationale was corrected (the `com.wilko`
  `jaimlib.jar` is in fact present).
- **Agenda 3D** (`lg3d-incubator`, `org.jdesktop.lg3d.apps.orgchart.ui.agenda`) —
  a new native-3D week-agenda app that interacts **one-way** with the ported
  **Contact 3D**. Both run in the same JVM and share the user `Preferences` root,
  so `Agenda3D` reads the very same `/contacts` node `Contact3D` imports (falling
  back to the bundled `contacts.xml` if Contact 3D has not run yet) and offers
  those contacts as meeting attendees, drawing each invitee's live free/busy
  presence as a coloured chip on the appointment block — **Contact 3D itself is
  unchanged**. A `Frame3D` (with the standard decoration) lays out an `AgendaGrid`
  week view — seven day columns by ten one-hour rows (08:00–18:00) rasterised into
  a single live texture with the `Histogram3D` `ImageComponent2D.set` recipe — over
  a strip of runtime-drawn `AgendaButton` controls (New / Del / Title / Today /
  Att- / Att+ and Day / Hr / Dur nudges). Clicking a cell selects an appointment
  or moves the creation cursor; appointments are **user-created only** (the agenda
  starts empty) and persist under `/agenda/appointments`, mirroring how Contact 3D
  stores contacts. Registered in the start menu (Office) by
  `lg3d-apps/src/config/agenda3d.lgcfg`.
- **Agenda 3D week grid now marks business days, holidays and weekends** — the
  `AgendaGrid` columns are anchored to real `LocalDate`s (Monday of the current
  week + offset) instead of bare indices, and each day is classified with the
  bundled **`jbusinessday`** library (`libs/jbusinessday-0.9.1-SNAPSHOT.jar`):
  `JBusinessDay.isWeekend` / `isBusinessDay` against a per-year cached federal
  holiday list (`AmericanHolidayUtil.getFederalHolidays` by default, or
  `CanadianHolidayUtil.getCanadianFederalHolidays` when started with
  `-Dlg.agenda.holidayRegion=CA`). A title band across the top of the grid shows
  the displayed week's month range **and year** (e.g. `September 14–20, 2026`, or
  `Dec 28, 2026 – Jan 3, 2027` when the week straddles a year), the day header is
  two lines — the day name over a short month + day-of-month (e.g. `Mon` /
  `Sep 14`) — and weekend and holiday columns get distinct header tints plus a
  faint full-height body wash, with an accent bar over today. A new navigation
  row (`Yr-`/`Mo-`/`Wk-` and their `+` counterparts) cycles the displayed week
  back and forth through the calendar, and `Today` snaps back to the current
  week; the today-highlight only appears when the displayed week is the current
  one. `jbusinessday` logs through slf4j and touches
  `org.slf4j.LoggerFactory` in a static initializer, so `slf4j-api` + the silent
  `slf4j-nop` provider (2.0.16) are added to the incubator runtime classpath and
  resolved onto the `lg3d-core:run` classpath alongside the jar.
- **Mail 3D** (`lg3d-incubator`, `org.jdesktop.lg3d.apps.mail`) — a new native-3D
  e-mail client. A `Frame3D` (standard decoration) lays out a single live-texture
  `MailView` — a message list beside a reading / compose pane under a slim folder
  header, rasterised with the same `ImageComponent2D.set` recipe as `AgendaGrid` —
  over a strip of the agenda's runtime-drawn `AgendaButton` controls. The mailbox
  is **local-only** (no SMTP/IMAP): "sending" files a message into Sent, which
  keeps the compose / reply / send loop fully exercisable offline, and the store
  persists under `/mail/messages` in the user `Preferences` tree, seeded with a
  few sample messages on first run. Interaction is button-driven (dev mode has no
  keyboard focus routing): click a row to open and mark it read; Inbox / Sent
  switch folders and Next walks the selection; New / Reply open a draft whose
  To / Subj / Body cycle presets (recipients drawn from the same shared
  `/contacts` directory Contact 3D populates, via `ContactDirectory`); Send files
  it and jumps to Sent, Back discards it. A 48x48 `mail3d.png` icon (INDIGO tile +
  `SendMail` glyph) is generated by `lg3d-art/tools/GenerateAppIcons.java`, and the
  app is registered in the start menu (Office) by
  `lg3d-apps/src/config/mail3d.lgcfg`.
- **App icons via the bundled `IconManager` library** (`libs/IconManager-1.6.0.jar`)
  — the six start-menu apps that previously fell back to the generic
  `defaultapp.png` (**Image Studio**, **Luncher**, **Natural Language Control**,
  **Chart 3D**, **Contact 3D**, **Agenda 3D**) now each get a distinct 48x48
  icon: a per-app coloured gradient/glass tile with the most fitting
  `toolbarButtonGraphics` glyph overlaid, generated by
  `lg3d-art/tools/GenerateAppIcons.java` (IconManager `createGradientIcon` +
  `createCompositeIcon(OVERLAY)` + `exportIcon`) into
  `lg3d-core/src/resources/images/icon/` and referenced from each app's `.lgcfg`.
- **`jmf23D` (Algea3D) ported but intentionally not menu-registered** — the
  JMF-backed 3D media player now compiles, and its `main` guards against a null
  `Player` so it degrades gracefully instead of throwing an NPE, but it is a
  *media player*: its default clip (`GoMonkeyDemo.ogg`) is not shipped in the
  repository and its Ogg demuxer needs the `fobs4jmf` **native** library, which
  has no modern x86-64 build. With no playable media and no available codec it
  cannot render anything from a start-menu click, so no `.lgcfg` descriptor is
  installed; it stays usable from the command line via `java ...Algea3D -m <url>`
  wherever a suitable JMF codec exists. Its `jmf`/`fobs4jmf`/`jl1.0`/`commons-cli`
  jars are still on the run classpath and its transport-button models are merged
  into `runtimeResources` for that command-line path.
- **UI/UX developer documentation** — a new top-level `docs/` tree (distinct from
  the historical `lg3d-docs/`): `docs/lg3d-native-apps.md` (building native 3D
  apps — `Frame3D`/`Component3D`, layout, the glassy widget vocabulary, event
  adapters + actions, transparency ordering, the live-graph texture-upload rule,
  and Start-Menu `.lgcfg` registration) and `docs/swingnode.md` (embedding Swing
  via `SwingNode` — offscreen texture pipeline, input forwarding, custom
  renderers, lifecycle/`dispose()`). A nested `lg3d-core/AGENTS.md` captures the
  UI/UX rules for agents, and the root `AGENTS.md` now links both.
- **Live taskbar miniatures for Swing windows** — `TitledSwingWindow` now sets a
  content thumbnail (the `Lg3dHelp` `HelpThumbnail` pattern: glass plate, drop
  shadow and a `FuzzyEdgePanel` textured with the window's own image) instead of
  falling back to the blank coloured `DefaultThumbnail` plate. To make this
  possible `SwingNode` gained a public `TextureListener` API
  (`addTextureListener` / `removeTextureListener`): observers are notified on
  the EDT whenever the rendered `Texture2D` is recreated (first capture and
  resizes), so the miniature binds — and re-binds — to the same live texture
  the window renders from, tracking panel repaints in real time.
- **Desktop configuration (Control Center → Desktop)** — a user-friendly,
  persisted settings surface for the desktop shell: **taskbar thickness**,
  **docking position** (bottom/top; left/right reserved for a later phase),
  **icon size**, the **Swing application UI font** (family + size), and a
  taskbar **auto-hide** toggle. Settings are held in a new lg3d-core
  `DesktopConfig` singleton backed by `java.util.prefs`
  (`LgPreferencesHelper`), edited from a new `DesktopPanel` in the Control
  Center, and applied **live**: the panel saves the prefs and posts a
  `DesktopConfigChangeEvent`, which the active `AdvancedGlassyTaskbar` consumes
  to re-lay-out (thickness/position/icon scale) on the fly, while
  `TitledSwingWindow` re-applies the configured font to the Swing
  `UIManager` defaults. Taskbar docking now publishes a top *or* bottom
  reserved strip (`Taskbar.get/setReservedTopHeight`), and window maximize
  clearance fills the usable band between them. Auto-hide slides the bar mostly
  off its docking edge on mouse-exit and back on edge hover (self-contained in
  the taskbar, as the legacy dormant `HideEvent` path has no poster).
- **Four native-3D games** (`lg3d-incubator`, `org.jdesktop.lg3d.apps.games`) —
  **Tic-Tac-Toe 3D**, **Sudoku 3D**, **Chess 3D** and **Solitaire 3D**, each a
  self-contained pure-3D app on the Agenda 3D pattern: a plain-Java game **model**
  (no AWT, unit-tested headless), a live-texture `Component3D` **view** that
  rasterises the board into one `ImageComponent2D` with the `Histogram3D` `.set`
  recipe, and a `Frame3D` **host** (with the standard decoration) laying the view
  over a strip of runtime-drawn `AgendaButton` controls. Interaction is entirely
  **click-driven** — dev mode routes no keyboard focus to a `Frame3D` — picking the
  textured quad and mapping the hit back to a board cell or card. **Tic-Tac-Toe**
  plays an unbeatable full-width **minimax** opponent (perfect from either side).
  **Sudoku** generates a puzzle from a solved grid across three difficulty levels
  (Easy / Medium / Hard = 44 / 34 / 27 givens) with live row/column/box **conflict
  highlighting**, hints, solve and reset-to-puzzle. **Chess** implements the full
  ruleset — castling, en passant, promotion, check / checkmate / stalemate and
  insufficient-material draws — against a **negamax + alpha-beta** engine with
  quiescence search and piece-square evaluation (perft-verified to depth 4:
  20 / 400 / 8902 / 197281 nodes), plus legal-move highlighting, undo and board
  flip. **Solitaire** is Klondike with a recycling stock, four foundations, seven
  tableau piles, run dragging, auto-finish, undo and hints; its cards are drawn
  with **vector suit shapes** (`Path2D` / `Ellipse2D`) so it needs no extended
  font. Each game is registered in a new **Games** start-menu group by a descriptor
  under `lg3d-apps/src/config` (`tictactoe`, `sudoku`, `chess`, `solitaire`)
  and gets a distinct 48x48 `IconManager` icon from `GenerateAppIcons.java`.
- **Calculator** (`lg3d-apps`, `org.jdesktop.lg3d.apps.calculator`) — an
  advanced scientific calculator whose Swing `JPanel` is hosted on a `SwingNode`
  inside a `Frame3D` via `TitledSwingWindow` (title bar, min/max/close, live
  taskbar thumbnail). A headless recursive-descent **expression engine**
  (`CalculatorEngine`: parentheses, `^` powers, factorial, `%`, `mod`, DEG/RAD
  trigonometry and inverses, `ln`/`log`/`sqrt`/`abs`, `pi`/`e`/`Ans`, memory
  register MC/MR/M+/M-/MS) drives an editable expression field with a **live
  result preview**, a six-column key pad and a clickable **history** list;
  keyboard input reaches the field through the `KeyEvent3D` forwarding. It is
  registered in the **Utilities** start-menu group (`calculator.lgcfg`) and gets
  a 48x48 icon whose keypad glyph is drawn inside `GenerateAppIcons.java`, the
  bundled glyph set carrying nothing calculator shaped.
- **Media Writer** (`lg3d-apps`, `org.jdesktop.lg3d.apps.mediawriter`) — a
  full-featured disc and USB imaging tool whose Swing `JPanel` is hosted on a
  `SwingNode` inside a `Frame3D` via `TitledSwingWindow`. A headless engine
  (`MediaWriterEngine`) drives the **real** Linux media tools — `growisofs` /
  `wodim` / `xorriso` for optical burns, `dd` for USB imaging and cloning,
  `wipefs` / `parted` / `mkfs.*` for formatting — across five modes: **burn an
  ISO to CD/DVD** (speed selection, `-dvd-compat`), **write a raw image or ISO
  to a USB key** (with `isohybrid` master-boot-record fix-up and optional
  bootable-partition handling), **clone a disc/device**, **format a removable
  key** (vfat/exfat/ntfs/ext4/ext2, optional msdos partition table, volume
  label) and **build a data disc** from a folder (`xorriso -as mkisofs`,
  Rock Ridge + Joliet, optionally burned straight to a drive). Devices are
  enumerated by parsing `lsblk -b -P` (optical / USB / internal-disk
  classification, mount points, media state from `/proc/sys/dev/cdrom/info`);
  images are probed for ISO-9660 and hybrid-magic before writing. Safety: an
  internal disk is never a writable target, mounted filesystems are unmounted
  first, destructive tools run under `pkexec` when not root, every write is
  gated by an explicit inline confirmation, and an optional **SHA-256 verify**
  re-reads the written media. The panel adds cancellation, live progress
  (parsed from `dd`/`growisofs` output) and a scrolling command log. Because
  `SwingNode` captures only its own panel, file picking and confirmation use
  **in-panel overlays** instead of modal dialogs. Registered in the
  **Utilities** start-menu group (`mediawriter.lgcfg`); its 48x48 icon gets a
  disc glyph drawn inside `GenerateAppIcons.java`, the bundled glyph set
  carrying nothing disc shaped.
- **Top-level Swing window capture in `SwingNode`** (`lg3d-core`,
  `org.jdesktop.lg3d.wg.internal.swingnode`) — a conventional, *unmodified* Swing
  application now integrates into the 3D desktop. A single global
  `AWTEventListener` (`SwingNodeWindowCapture`) watches every top-level `Window`
  the JVM opens and, instead of letting it pop onto the host desktop where the
  offscreen texture capture cannot reach it: presents a captured `JFrame` as a
  real desktop window (`CapturedFrameHost` — a `Frame3D` + `SwingNode` with the
  standard title bar / spine titles / min-max-close `Frame3DWindowDecoration`, a
  live taskbar thumbnail, and resize / title / close sync between the real frame
  and its 3D window), and paints a captured `JDialog` / `JWindow` (`JOptionPane`,
  `JFileChooser`, popups) as a centred in-scene **overlay** inside the owning
  node's texture, routing forwarded mouse and key input to it with modal
  semantics preserved (a modal dialog's own secondary EDT loop processes the
  events the node dispatches). `SwingNode.captureNow` composites the captured
  overlays on top of the hosted panel, its `RepaintManager` marks the owning node
  dirty when a captured dialog repaints, and `SwingNodeRenderer` resolves each
  input event's target through the capture registry (topmost modal dialog first,
  else the overlay under the pointer, else the node's own hidden frame). This
  removes the technical reason for the "in-panel overlays instead of modal
  dialogs" workaround noted for Media Writer above — that app is intentionally
  left as-is, but real modal dialogs now render in-scene for any app that opens
  them.
- **`SwingAppLauncher` + `--swing-app` launcher** (`lg3d-core`,
  `org.jdesktop.lg3d.utils`) — runs a conventional Swing application's `main`
  inside the desktop JVM on a dedicated thread so every window it creates is
  captured. Wired through `DisplayServerControl` (after "Start-up configuration
  completed", reading `-Dlg.swingapp`), the `:lg3d-core:run` task
  (`-PswingApp="<fqcn> [args...]"` and `-PswingAppCp=<path[:path...]>` for the
  app's classes/jar) and `run-lg3d.sh` (`--swing-app <fqcn> [args...]`,
  `--swing-app-cp <paths>`).
- **Paint drawing app** (`lg3d-apps`, `org.jdesktop.lg3d.apps.paint`) — a
  conventional Swing `JFrame` raster editor (brush/pencil/shape/fill/eyedropper
  tools, layers, selections, image ops, undo/redo) registered in the Start menu
  under *Utilities* via `paint.lgcfg`. Its descriptor uses the new `swingapp`
  command verb, which opts the app into 3D window capture and launches it in-JVM,
  so its frame is captured and presented as an integrated 3D desktop window.
- **Test coverage reporting (JaCoCo)** — the `jacoco` plugin now applies to every
  module and each `test` task finalizes `jacocoTestReport`, emitting HTML + XML
  under `<module>/build-gradle/reports/jacoco/test/`. It is wired **report-only**
  (no failing threshold): the first measured baseline is lg3d-core ≈ 1.4% and
  lg3d-widgets ≈ 40% line coverage (74 passing tests), far below the 100% goal in
  `AGENTS.md`, so a gate would red-line every build until coverage improves.
- **Build-quality & supply-chain tooling (report-only)** — a second audit-
  remediation pass wires the tooling that makes the `AGENTS.md` 100%-coverage /
  0-mutant goal and the supply-chain gaps *measurable* without enforcing a hard
  gate that would red-line the ~200k-line legacy port:
  - a **JaCoCo coverage ratchet** — `jacocoTestCoverageVerification` joins
    `check` for the two modules with test suites, each with a floor set just
    below current coverage (lg3d-core 1.0% line / 2.0% branch; lg3d-widgets
    48% line / 40% branch), so a regression fails but ordinary churn stays green;
  - **Checkstyle** static analysis from a single shared high-signal config
    (`config/checkstyle/checkstyle.xml`, bug-prone patterns only) with
    `ignoreFailures=true`, publishing XML+HTML reports;
  - **PIT** mutation testing (`info.solidsoft.pitest`) on `lg3d-core` and
    `lg3d-widgets`, on-demand via `./gradlew :lg3d-core:pitest`, with
    `avoidCallsTo` excluding `java.awt`/`javax.swing`/`org.jogamp.*`;
  - a **CycloneDX SBOM** per module (`./gradlew cyclonedxBom`);
  - **OWASP Dependency-Check** (`./gradlew dependencyCheckAggregate`) across every
    module's resolved dependencies, report-only (`failBuildOnCVSS=11`) with an
    optional `-PnvdApiKey`.
  Plugin/tool versions are declared in `gradle/libs.versions.toml`.
- **`lpm-console` unit tests + coverage ratchet** — a headless JUnit 5 suite
  (`lpm-console/src/test/java`, wired into the Gradle `test` task with
  `java.awt.headless=true`) now covers the module's Java 3D-free, GUI-free logic:
  the `LpmPackage` / `OperationResult` / `LPMCommand` / `LPMExecutionException`
  value objects (100% line and branch), the read-only `LpmDatabase` parser
  (pointed at a `@TempDir` through the documented `-Dlpm.dbdir` override) and the
  `LPMExecutor` / `PrivilegeEscalator` command builders (exercised only on the
  `/usr/bin/lpm`-absent path so no process is spawned and CI needs no LPM
  install). The Swing frame and 853-line panel need a peer and stay probe-verified
  per `lg3d-core/AGENTS.md`, so `lpm-console` joins the JaCoCo ratchet with a
  floor just below its measured 26.7% line / 36.9% branch (the panel is the bulk
  of the uncovered lines), and PIT is wired on-demand via
  `./gradlew :lpm-console:pitest`.
- **`lg3d-widgets` widget-API unit tests** — a headless JUnit 5 suite
  (`lg3d-widgets/src/test/java`, `java.awt.headless=true`) now covers the
  module's Java 3D-free, peer-free logic: the immutable `WidgetDescriptor`
  value object (field defaults, argument guards, `create()` factory,
  `toString`), the `ServiceLoader`-backed `WidgetRegistry` singleton
  (discovery, unmodifiable view, id lookup and both `create()` paths — a
  test-only `StubWidgetProvider` registered through
  `src/test/resources/META-INF/services` lets the success path run without
  building a Java 3D `Component3D`), `BuiltinWidgetProvider.descriptors()`
  (mirrors the shared card catalogue), and the two logic pieces of
  `WeatherCard` — the WMO weather-code → text map and its compact
  dependency-free JSON reader (objects, arrays, string escapes, exponents,
  literals, whitespace tolerance and malformed-input rejection, driven
  reflectively). Together with the existing card/layer/gallery tests this
  lifts lg3d-widgets from ~40% to **50.1% line / 41.6% branch** (52 passing
  tests), and the JaCoCo ratchet floor is raised to match. Under PIT the
  targeted pure-logic units are fully killed (`WidgetDescriptor` 19/19,
  `BuiltinWidgetProvider` 2/2, `WeatherCard.condition` 16/16); the only
  survivors are behaviourally-equivalent mutants (redundant whitespace skips,
  early-return pointer tweaks, log-call removals). The Swing card painting and
  the `WeatherCard` network fetch stay probe-verified per
  `lg3d-core/AGENTS.md`.
- **Gradle version catalog** (`gradle/libs.versions.toml`) — centralizes the
  Java 3D (1.7.2), Jogamp-natives (2.6.0), JUnit (5.11.4) and SLF4J (2.0.16)
  versions. `lg3d-core`, `lg3d-widgets` and `lg3d-incubator` now reference
  `libs.*` aliases instead of hardcoding `group:name:version` strings, so a
  dependency bump is a one-line change.
- **`CONTRIBUTING.md`** — a contributor guide covering prerequisites (JDK 21, and
  why Gradle 8.14 cannot run on Java 25+), build/run/test commands, the
  single-repo module layout, the branch → PR flow, the Conventional Commit
  convention and scopes, coding rules (Jogamp packages, generated-file and
  exclusion cautions) and the versioning policy.
- **`SECURITY.md`** — a security policy with a private vulnerability-disclosure
  process (GitHub Security Advisories), supported-version scope, security design
  notes (`ProcessBuilder` / `pkexec` / no telemetry / X11 compositor), and an
  explicit known-risk section for the ~26 end-of-life 2006-era jars bundled under
  `lg3d-incubator/ext/`.

### Changed
- **2D/Swing app names no longer carry the "3D" marker** (`lg3d-core`) — the
  start-menu descriptors are shared with the 3D desktop, so apps that run in the
  2D/Swing desktop as plain Swing panels were still labelled "Mail 3D", "Chess
  3D", "Agenda 3D", "Chart 3D", "Contact 3D", "Sudoku 3D", "Solitaire 3D" and
  "Tic-Tac-Toe 3D" in both the menu entry and the window/taskbar title — even
  though the 2D desktop never renders a 3D window. `Desktop2DMenuConfig` now
  drops the "3D" marker from the display name of any item the desktop can
  actually run (a `PANEL`, `SWING_FRAME` or `EXTERNAL` command), so they read
  "Mail", "Chess", etc. Pure-3D apps it cannot run keep their name unchanged
  (they appear disabled behind the "Requires the 3D desktop" tooltip, where the
  "3D" is the point). Only leading/trailing/word-attached markers are stripped;
  interior brand tokens such as "Lg3d Homepage" are preserved. The underlying
  command and descriptor are untouched, so the 3D desktop is unaffected. Covered
  by new `Desktop2DMenuConfigTest` cases and verified with an in-JVM probe over
  the real descriptors.
- **Module rename** — `lg3d-demo-apps` → `lg3d-apps`. The module ships the
  production-grade desktop applications that come with LG3D (plus a few
  tutorial/sample apps); "demo" was a misleading legacy label. The Gradle
  project, directory, jar (`archiveBaseName = 'lg3d-apps'`), the
  `settings.gradle` include, `lg3d-core`'s project references and run-classpath
  variable (`demoAppsJar` → `appsJar`), the CI artifact path, and every doc /
  source reference were updated. The `config/demo` runtime resource path and the
  `Demos` start-menu group are **legacy names intentionally left unchanged** so
  lg3d-core's descriptor discovery keeps resolving them; historical
  `lg3d-docs/**` was not touched.
- **CI workflow** (`.github/workflows/build.yml`) — added a dedicated, visible
  `./gradlew test --continue` step (the tests already ran implicitly inside
  `build` via `check`) and a `lg3d-test-reports` artifact publishing the JUnit +
  JaCoCo reports on every run, including failures. Removed the inaccurate "the
  lg3d modules live in git submodules / check out recursively" claim and its
  `submodules: recursive` checkout: this is a single repository with no
  submodules, so a plain checkout fetches every module's sources. The build job
  now also uploads the Checkstyle reports alongside the coverage reports, and a
  new `security` job generates the CycloneDX SBOM and runs OWASP Dependency-Check
  (continue-on-error) on `main`/`master` pushes, a weekly schedule and manual
  dispatch — skipped on pull requests to avoid the heavy first NVD download. CI
  stays **ubuntu-latest only**: lg3d is a Linux X11 desktop, so there is no
  macOS/Windows host to validate.
- **Documentation accuracy** — `README.md` and `AGENTS.md` now state plainly that
  this is a single repository (no git submodules), list `lpm-console` among the
  built modules, and correct stale `1.0.1-dev` jar-name examples to the current
  `1.9.0-dev`; `AGENTS.md`'s test-coverage and CI status notes were synced with
  the new JaCoCo + explicit-test-step reality, and `AUDIT.md` gained a
  *Remediation Status* section recording what this pass addressed and deferred.
- **Java 3D** migrated from the Sun `javax.media.j3d` / `javax.vecmath` stack to
  the Jogamp-maintained **1.7.2** fork (`org.jogamp.java3d` / `org.jogamp.vecmath`)
  — the only readily available release preserving the 1.5-era API the sources rely
  on (`ShaderError`, `Node.ALLOW_PARENT_READ`,
  `VirtualUniverse.addGraphStructureChangeListener`, …). Every source file was
  migrated to the renamed packages (`javax.media.jai` left alone).
- **Modern-JDK API drift** fixed across the tree, e.g.
  `Behavior.processStimulus(Enumeration)` → `Iterator<WakeupCriterion>`, and
  `Group.getAllChildren()` / `getAllScopes()` now return `Iterator` instead of
  `Enumeration`.
- **`SatinGestureModule`** rewritten as a geometric stroke classifier, replacing
  the SATIN/Rubine stack from the dropped `satin-v2.3.jar`.
- **`lg3d-incubator`** now compiles the whole `src/classes` tree as one source set
  against `lg3d-core` + the bundled `ext` jars, mirroring the legacy per-app
  `failonerror="false"` behaviour by excluding apps that cannot build.
- **Documentation** — the root and per-module `README.md` files rewritten to
  describe the port, build/run instructions, module map, and exclusions.
- **`lg3d-art` raster assets modernised for high-resolution displays** — the
  wallpapers shipped as 512x512 JPEGs are stretched full-screen by
  `SimpleImageBackground`, so they looked soft and blocky on modern 1080p/4K
  panels. A new reproducible tool, `lg3d-art/tools/modernize_assets.py`
  (Pillow + NumPy + SciPy), cleans and enlarges them in place while keeping the
  *same content*: JPEGs get a mild variance-gated (Wiener-like) denoise at
  native resolution to dissolve compression blockiness, a Lanczos upscale to a
  2048px longest side (capped at 4x so small icons are not over-inflated), and a
  high-quality progressive re-encode; PNGs (icons, splash art) are lossless
  already, so only the Lanczos upscale + optimised re-encode is applied, with
  alpha preserved. Per the agreed policy there is **no sharpening and no
  contrast/colour retouch** — the look is preserved, only cleaned and enlarged.
  Filenames, formats and aspect ratios are unchanged, so every runtime reference
  (BgConfig, start-menu icons, splash, GDM theme) keeps working, and the tool is
  idempotent (images already >= the target are never re-enlarged). The website
  thumbnails under `www/` and the fixed-size GDM chrome buttons are excluded.
  93 assets processed; the art payload grows ~6.8 MB -> ~37.8 MB.
- **Window flip-to-sticky gesture now requires CTRL + right-click** — the
  `Frame3DWindowDecoration` flip (and the matching flip-back on the sticky note)
  was bound to a plain BUTTON3, which never reached the frame for Swing-to-Node or
  native LG3D apps: those reserve a bare right-click for their own context menus
  and their content is non-propagatable, so `PickEngine` stopped the event before
  the frame-level listener ever saw it. Rebinding both listeners to CTRL + BUTTON3
  disambiguates the desktop gesture from app context menus; it still arrives
  through a propagatable handle (the title bar / window chrome).
- **Taskbar right-hand group reordered and inset from the screen edge** — the
  dock group now reads `[Documents] [Downloads] [Background] [Exit]` (Documents
  `-4`, Downloads `-3`, background `-2`, Exit `-1`) instead of background-first,
  and the right-aligned group is inset by a quarter bar height so the rightmost
  (Exit) icon sits on the tapered tip of the tilted glass shelf rather than
  hanging off the end of the bar.

### Removed
- **Bundled `j3d-contrib-utils.jar` and `satin-v2.3.jar`** — compiled against the
  legacy `javax.media.j3d` packages and binary-incompatible with the Jogamp
  rename; superseded by the in-tree replacements above.
- **`lg3d-awt`** excluded from the build — the optional custom AWT Toolkit/peer
  implementation depends on the `java.awt.peer.*` SPI (changed after JDK 5) and
  unexported `sun.awt.*` internals. Not needed: it is opt-in via
  `lg.use3dtoolkit=true` (default false) and loaded dynamically, so `lg3d-core`
  has no compile-time dependency on it. Sources kept in-tree.
- **Native X11 integration** excluded from `lg3d-core`
  (`displayserver/fws/x11`, `apps/x11integration`, `sun.awt.X11.*` shims), and the
  **`lg3d-x11`** native X server module left out of the build — dev mode uses the
  AWT foundation window system instead.
- **Unused RMI scene-graph transport** (`sg/internal/rmi`, `wg/internal/rmi`).
- **ODE physics nodes** (`wg/.../j3dnodes/Ode*`) — superseded by an in-tree
  spring-damper system; the bundled `odejava` jar is not used.
- **Incubator apps that cannot build** (silently skipped by the legacy build too):
  `nu/koidelab` (Cosmo), `archviz3d`, `intel3d`, `browser`, `browser3d`,
  `wilkoaim3d`. (`luncher`, `orgchart`, `nlc` and `jmf23D` were previously in
  this list and have since been ported — see Added.)
- **`Demos` start-menu entry** (`lg3d-core`) — the root `Main` group in
  `startmenu.lgcfg` no longer links to the `Demos` folder, so it (and its
  `Tests` / `Early Prototypes` sub-folders — the tutorial/sample/prototype
  descriptors) is dropped from both the 2D/Swing and the 3D start menu. No
  production application lives under `Demos`; the group *definitions* are kept
  in the descriptor so their items stay grouped (and unreachable) rather than
  becoming orphan items re-appended to the menu root.

### Fixed
- **`UpdateScheduler` could leave a phantom pending update** (`update-manager`) —
  `scheduleUpdate` handed the task to the `ScheduledExecutorService` *before*
  publishing it into `scheduledTasks`. An install time inside the current second
  makes `ChronoUnit.SECONDS.between(now, installTime)` truncate to a **zero**
  delay, so the worker thread could run `executeScheduledUpdate` and call
  `scheduledTasks.remove(taskId)` before the caller's `put` executed; the remove
  was then a no-op, the entry was added afterwards and lingered forever, so
  `getPendingCount()` never returned to zero. This surfaced as an intermittent
  `UpdateSchedulerTest.testListenerReceivesLifecycleEvents` failure (the
  `awaitPendingCount(0)` assertion) on loaded CI runners, where the worker wins
  the race more often than on a fast dev machine. The task is now published to
  `scheduledTasks` before it is scheduled: `executor.schedule(...)` establishes a
  happens-before edge, so the worker's `remove` always sees the entry. Verified
  with a contention harness (buggy ordering leaked 8/4000 phantom entries, fixed
  ordering 0/4000) and 15 `UpdateSchedulerTest` runs under saturated CPUs (0
  failures).
- **2D/Swing desktop widgets could not be dragged** — in the conventional Swing
  desktop (`SwingWidgetLayer`, the `--2d`/`--swing` widget host) grabbing a
  widget card and moving it made the card fly off the cursor instead of
  tracking it, so widgets could not be repositioned. `mouseDragged` called
  `SwingUtilities.convertPointToScreen(press, card)` on every event, but that
  method mutates its `Point` argument in place and `press` was captured once on
  mouse-press in card-local coordinates: after the first drag it already held
  screen coordinates, so each later event re-converted it and compounded the
  card's own movement into a runaway delta. The handler now recomputes the
  pointer in the desktop pane's space from the raw local point each event
  (`SwingUtilities.convertPoint(card, e.getPoint(), desktop)`) and never mutates
  the stored press point, so the card follows the pointer exactly and its new
  fractional position still persists on release. Covered by a new headless
  `SwingWidgetLayerTest` drag case (synthetic press/drag/release asserts the
  card lands on the pointer rather than flying off).
- **2D/Swing desktop showed minimised windows on a second row** — under the
  Synth (GTK) look-and-feel the `JDesktopPane` installed its own MDI taskbar
  strip that re-listed minimised windows above the shell's taskbar, so a
  minimised application icon appeared twice and the bar read as two rows. The
  desktop pane now pins `BasicDesktopPaneUI` (no built-in strip) and a desktop
  manager hides the classic MDI desktop icon, leaving the shell taskbar button
  as the single representation; clicking it still restores the window.
- **2D/Swing taskbar looked like two rows when made thicker** — the bar imposed
  its own thickness from the 3D `barScale` setting, so above the default the
  button row sat in a taller slab with an empty band that read as a second row.
  The 2D bar now carries no thickness of its own: it packs to the height of the
  buttons it holds (only auto-hide still overrides the height), and each button
  row is centred vertically.
- **Control Center showed fewer categories in the 2D/Swing desktop** — the
  control center listed only Display, Users and System under `--2d`/`--swing`
  but all five on the 3D desktop: `ControlPanelRegistry` deliberately dropped
  the Appearance and Desktop panels when `lg.desktop2d` was set, because both
  drove the scene graph through `LgEventConnector`
  (`BackgroundChangeRequestEvent`, `DesktopConfigChangeEvent`) with nothing
  listening on the conventional desktop. All five panels now register in every
  mode, and each drives whichever desktop is running: on the 2D desktop
  `AppearancePanel` calls `Desktop2D.setWallpaper(url)` (the backdrop image is
  now swappable at runtime) and `DesktopPanel` calls
  `Desktop2D.applyDesktopConfig()`, which re-applies the persisted
  `DesktopConfig` live — Swing UI font defaults, taskbar docking edge
  (top/bottom), thickness (`barScale`), chrome-icon scale (`iconScale`) and
  auto-hide (a pointer-proximity poll collapses the bar to a sliver). The 2D
  shell also honours the saved config at startup, and the Java 3D event path is
  guarded behind the mode property so it is never touched on a 3D-less JVM. The
  3D desktop path is unchanged. Verified with in-JVM probes: the panel list
  reports `count=5 [Display, Users, System, Appearance, Desktop]` under
  `lg.desktop2d=true`, and driving a live `Desktop2D` re-docked the taskbar to
  `North` at `preferredHeight=54` (barScale 1.6) and swapped the wallpaper
  image; the scale/height math is covered by `Desktop2DTaskbarConfigTest`.
- **Screen Snapshot would not launch in the 2D/Swing desktop** — the
  conventional Swing `ScreenCaptureConfigFrame` (a plain `JFrame` with a `main`,
  like Paint and Swing Test) was missing from `Desktop2DAppRegistry`'s
  `SWING_FRAME_APPS`, so `classify` fell through to `UNAVAILABLE` and its
  Start-menu entry (Utilities) was greyed out with "Requires the 3D desktop". It
  is now registered as a Swing-frame app, so it launches in-JVM beside the
  desktop under both `--2d` and `--swing`. Two 3D couplings that would misbehave
  in the shared desktop JVM were fixed at the same time: its `EXIT_ON_CLOSE`
  (which would tear the desktop down on close) is now `DISPOSE_ON_CLOSE`, and
  "Take Snapshot" no longer posts a `ScreenCaptureEvent` in 2D — that path makes
  `AppConnectorPrivate` boot Java 3D, which cannot work with no 3D — so it paints
  the visible desktop window(s) straight into `lgscreen-<i>-<n>.png` in the
  chosen folder (the same naming as the 3D `ScreenCaptureBehavior`), avoiding
  `java.awt.Robot`, which cannot grab a rootless/Wayland display. The 3D capture
  path is unchanged. Covered by `Desktop2DAppRegistryTest`.
- **Maximizing a hosted Swing window magnified its text and left it narrow** —
  `Frame3DWindowDecoration.toggleMaximized` maximized every window by uniformly
  scaling the `Frame3D` to fit the usable screen area
  (`min(screenW/w, usableH/h)`). For a `SwingNode`-hosted window (Task Manager,
  and any `TitledSwingWindow`) that scaled a fixed-resolution offscreen texture,
  so the content — text included — was blown up and blurry, and the
  aspect-preserving `min` letterboxed a narrow window instead of filling the
  width. A real `JFrame` re-lays-out its content at native size on maximize.
  Hosted windows now do the same: `HostedWindowResizer` (a
  `WeakHashMap<Frame3D,Resizer>` registry in `lg3d-core`) lets
  `TitledSwingWindow` register a per-frame resizer; `toggleMaximized` detects a
  registered frame and resizes the Swing panel to the usable area in native
  pixels (`SwingNode.setHostedSize`) instead of scaling, then re-lays-out the
  title bar, spines and decoration. The panel repaints at its new native size
  (crisp text) and the quad grows to full width. The decoration tracks the new
  size **in place** — `GlassyPanel`/`RectShadow` are resized through their
  `setSize` (both carry `ALLOW_COORDINATE_WRITE`), never removed and rebuilt,
  since detaching non-`BranchGroup` children from a live graph throws
  `RestrictedAccessException` and re-inserting a detached backdrop trips
  `MultipleParentException`. Un-maximizing restores the original pixel size;
  pure-3D windows keep the uniform scale-to-fit maximize. Verified with an
  in-JVM probe: a 680x480 hosted panel maximized to 1920x852 (full width, native
  pixels) with no scene-graph exceptions.
- **A maximized window masked the start-menu application list** — the full-screen
  hosted maximize above exposed a latent draw-order bug in the start menu. The
  menu is a child of the taskbar, which docks at `z = -0.04`
  (`GlassyTaskbar.barZ`), while `ZLayeredLayout` places the front-most app window
  at `z ~= -0.004`. `StartMenuModel.changeVisible` only raised the menu to a local
  `z = 0.02` (world `~= -0.02`), i.e. *behind* every window; that went unnoticed
  because ordinary windows never overlap the menu's bottom-corner popup region,
  but a full-screen maximized window does, so the app list disappeared behind it.
  Raising the menu in depth alone is not enough, and neither is a small Z lift:
  transparent shapes are sorted back-to-front by the distance from the eye to
  each shape's bounding-sphere centre, *not* by Z. A maximized window quad is
  centred on-axis (distance ~= eye.z), while the menu is docked in a screen
  corner, so its off-axis centre stays *farther* from the eye even at a nearer Z
  and it keeps sorting behind the window. The raise now lifts the menu to a
  fraction (`FRONT_WORLD_Z_FRACTION = 0.4`) of the eye distance, which pulls its
  bounding-sphere centre well inside the window's so every menu shape sorts - and
  is picked - in front; the lift is perspective-compensated (world X/Y and node
  scale multiplied by `r = (eyeZ - zNew)/(eyeZ - zRef)`) so the list's on-screen
  position and size are unchanged. Verified in a framebuffer capture: the hovered
  application list pops up on top of a maximized full-width window while the rest
  of the desktop stays put.
- **Min/max/close buttons sat above the title text** — `Frame3DWindowDecoration`
  pinned the window buttons to the top corner of the frame
  (`y = frameHeight/2 - inset`), while `TitledSwingWindow` centres its title text
  in the reserved title strip (`y = frameHeight/2 - titleBarHeight/2`), so on a
  hosted window the three buttons floated visibly higher than the title. The
  decoration now reads a new `TITLE_BAR_HEIGHT_PROPERTY` frame property; when a
  frame publishes its title-strip height (as `TitledSwingWindow` does before
  `changeEnabled`) the buttons are centred on that strip, aligned with the title
  text, and pure-3D frames without a strip keep the corner placement. Verified in
  framebuffer captures in both the normal and the maximized state.
- **Maximized window pushed its title bar off the top edge** - filling exactly to
  the screen top left the title strip (and the minimize/maximize/close buttons)
  flush against / past the top edge where they cannot be clicked. Maximize now
  reserves a small top headroom band (`0.012`) in addition to the taskbar
  reserves, so the whole title bar and its buttons stay comfortably inside the
  visible area while the window still fills the usable width and height.
- **Maximized hosted window kept its Swing content at the old size** — follow-up
  to the hosted maximize above. `SwingNode.setHostedSize` resized the panel then
  called `revalidate()` + `doLayout()`, but `doLayout()` only lays out the panel's
  *immediate* children, so a nested `JScrollPane` -> viewport -> `JTable` subtree
  kept its pre-resize interior bounds: the grown window showed the old small
  content stranded in a top corner with the rest of the area blank ("the frame
  content doesn't adapt"), which also left the title bar / window buttons looking
  detached from a mis-rendered body. `setHostedSize` now runs a full recursive
  `invalidate()` + `validate()` (`validateTree`) over the Swing hierarchy so every
  nested layout manager reflows at the new native size. Verified with an in-JVM
  probe plus an lg3d framebuffer screenshot: a hosted table window maximized to
  1920x852 with its columns stretched full-width and all rows visible, and the
  taskbar stayed clear below the maximized window.
- **Closing an in-JVM Swing app could tear down the whole desktop** — conventional
  apps run inside the desktop JVM (the `java` / `swingapp` command verbs), and they
  routinely default to `EXIT_ON_CLOSE`, so clicking their close button fired
  `System.exit` and killed lg3d along with the app (e.g. Screen Capture).
  `SwingNodeWindowCapture.onWindowOpened` now rewrites `EXIT_ON_CLOSE` to
  `DISPOSE_ON_CLOSE` on every non-host `JFrame`, so closing an app window only
  disposes that frame. (An app that calls `System.exit` directly from a menu
  handler is still out of scope.) Verified with an in-JVM probe: a plain
  `JFrame` opened as `EXIT_ON_CLOSE` (op 3) was rewritten to `DISPOSE_ON_CLOSE`
  (op 2) by the hook while the desktop kept running.
- **Window capture hijacked every conventional Swing app** — the global
  `SwingNodeWindowCapture` hook captured *all* top-level `JFrame`s unconditionally,
  so apps that used to run as normal host windows with native input (Screen
  Capture, Image Studio, Calculator, …) were hidden and re-presented as 3D windows
  driven by synthetic in-scene input, leaving their buttons unresponsive. Capture
  is now **opt-in per app**: `SwingNodeWindowCapture.registerCapturePackage` records
  the launching app's package and the hook only captures a `JFrame` whose class is
  in a registered package. The `swingapp <mainClass>` command verb (and
  `-Dlg.swingapp` / `--swing-app`) register that package; the plain `java <class>`
  verb used by every other Start-menu app does not, so those apps keep their
  native windows and working input. Only Paint opts in today.
- **Captured conventional Swing `JFrame` opened two windows on Wayland** — the
  capture layer hid the real frame only by relocating it to `(-32000,-32000)`,
  but a compositor-managed window manager (GNOME/Mutter under Wayland/XWayland)
  ignores that, so the host `JFrame` stayed mapped beside the 3D window, stole
  native input, and closing it exited the app. `SwingNodeWindowCapture` now
  unmaps the frame (`setVisible(false)`) and `SwingNode.captureNow` paints its
  **root pane** (a `JComponent`) instead of the hidden `Window`, which paints
  blank offscreen; `CapturedFrameHost` sizes the quad to the content area. The
  app now shows as a single integrated desktop window.
- **Could not type into a flipped sticky note (or any `SwingNode` text field)** —
  `SwingNodeRenderer` forwarded `KeyEvent3D`s with `target.dispatchEvent(...)`, but
  the offscreen `SwingNodeJFrame` is displayable yet never *shown*, so AWT never
  installs a focus owner: `hiddenFrame.getFocusOwner()` stayed `null`, the keys fell
  back to the content pane, and a direct dispatch of a `KeyEvent` to a component in
  an unfocused window is dropped before it ever reaches the `JTextArea`'s
  `WHEN_FOCUSED` input map. The old build relied on the excluded `lg3d-awt` peer
  toolkit (`Lg3dComponentPeer.setGlobalFocusOwner`) for this, which is unavailable
  on JDK 21 (strong encapsulation). The renderer now emulates click-to-focus —
  it remembers the deepest Swing component under a mouse press — and delivers
  keystrokes through `KeyboardFocusManager.redispatchEvent(target, evt)`, which
  hands the event straight to that component so editable widgets receive typed
  characters. Verified against a hidden-frame probe on JDK 21 with no reflection
  and no `--add-opens`.
- **Sticky-note typing came out reversed and kept losing focus** — typing "salut"
  produced "tulas" and the caret had to be re-clicked constantly. The
  `SwingNodeRenderer` input listeners run on the lg3d event thread while the
  `SwingNode` capture timer repaints the hosted panel on the EDT; mutating Swing
  state (caret / document / focus) off the EDT races with that repaint and pins
  the caret at 0, so every character inserts at position 0 (reversed text) and
  keystrokes/focus are intermittently dropped. All Swing dispatch in
  `SwingNodeRenderer` (mouse, enter/exit focus and key forwarding) is now
  marshalled onto the EDT with `SwingUtilities.invokeLater`, which serialises it
  with the capture repaint. Reproduced and verified with an off-EDT + capture-timer
  probe: off-EDT gave "tulas"/caret 0, EDT-marshalled gave "salut"/advancing caret.
- **Right-click flip to the sticky note did nothing on any app** — two compounding
  faults. (1) `Frame3DWindowDecoration.createStickyNote()` called
  `StickyNote.initialize(...)` *before* `setEnabled(true)`, but `initialize()`
  dereferences the Swing panel / title field / text area that only `enable()`
  (run from `setEnabled(true)`) creates, so every flip threw a
  `NullPointerException` that the event loop swallowed and the window never
  turned — on native 3D apps and Swing-to-Node windows alike. The native
  look-and-feel has always enabled first and initialised second; the decoration
  now does the same. (2) The flip is a frame-level `BUTTON3` listener, so it only
  fires where the pick propagates to the frame, and a decorated `Frame3D` had no
  propagatable surface to right-click: app content is deliberately
  non-propagatable (it keeps its own context menus) and the decoration backdrop
  was `setPickable(false)`. The backdrop border is now pickable and mouse-event
  propagatable — it sits *behind* the content so it never occludes or intercepts
  app clicks, yet a plain right-click on the exposed green border (or on
  `TitledSwingWindow`'s title bar) reaches the frame's flip listener. The flip is
  bound to a plain `BUTTON3` again, matching the 2006 / native X11 idiom.
- **Dock stack fan crashed on repeated hover and showed stale content** — the
  Documents/Downloads fan is shown and hidden with `Frame3D.changeEnabled`, and
  every re-enable re-ran `StandardAppContainer.addFrame3D`, which re-created the
  window animation. Replacing the animation destroys the previous
  `NaturalMotionWithSwayAnimation` *after* its target `Component3D` reference is
  cleared, so `removeListenerFromComponent3D` dereferenced a null `WeakReference`
  and threw a `NullPointerException` in the `EventProcessor`; the aborted
  `show()` also left the previously-open fan (e.g. Documents) on screen when
  hovering the other stack (Downloads). `addFrame3D` now performs its one-time
  setup (translucency listener, animation, window decoration) only once per
  frame, `Component3DAnimationTarget` tolerates an already-cleared target when
  adding/removing listeners, and opening one stack fan dismisses the other.
- **Window flip showed a plain green back instead of the sticky note** — the
  right-click flip of `Frame3DWindowDecoration` worked on both Swing
  (`TitledSwingWindow`) and pure-3D app windows, but the `StickyNote` was
  placed at `z = -1.1 x BODY_DEPTH`, inside the opaque decoration backdrop
  slab (which spans `[-2 x BODY_DEPTH, -BODY_DEPTH]` because `GlassyPanel`
  grows backwards from its local z=0). After the PI flip the slab's opaque
  back face is closest to the viewer and completely hid the note. The note
  now sits just outside the back face (`z = -2 x BODY_DEPTH - 0.0002`), so
  the flipped window shows the editable yellow note; flipping back also
  disposes the note's offscreen Swing resources, which previously leaked a
  hidden `SwingNodeJFrame` per flip cycle.
- **`TitledSwingWindow` windows could be parked but never rotated** — every
  rotation gesture of the desktop lives in frame-level listeners
  (`ZLayeredMovableLayout`'s CTRL spinner and `Frame3DWindowDecoration`'s
  middle-button spinner), and `PickEngine` only walks picked events up the
  ancestor chain while each source it meets is mouse-event propagatable; the
  Swing quad must stay non-propagatable (or Swing loses its own gestures) and
  the title bar was too, so no spin gesture ever reached the frame. The title
  bar is now propagatable, which turns it into the window's full gesture
  handle: left-drag moves, middle-drag or CTRL+left-drag rotates and
  right-click flips to the sticky note — the same idioms as pure-3D windows,
  with no duplicate listeners (the native window look-and-feel uses the same
  trick for its title panel).
- **`TitledSwingWindow` windows could be parked but not left-clicked back** —
  `StandardAppContainer` unparks a window when the *frame* receives a BUTTON1
  click (`Frame3D` click -> `Component3DToFrontEvent` -> migrate back to the
  main container), and a native window body is covered by a propagatable move
  region so a click anywhere on it reaches the frame. The Swing quad is
  deliberately non-propagatable and the only propagatable strip (the title bar)
  is edge-on once `BookshelfLayout` turns the parked frame +/-90deg, so a left
  click on the visible content died at the quad and never unparked. The quad is
  now made propagatable for the parked state only (via a
  `Component3DParkedEventAdapter`), so a click on a parked window travels up to
  the frame and restores it exactly like a native window, while live Swing
  input keeps the quad non-propagatable and untouched.
- **Vertical spine titles floating beside the green window edge** — the rotated
  edge titles built by `TitledSwingWindow` sit on the pale green side face of
  the decoration backdrop: each pre-rotated +/-90deg spine quad is placed just
  outside the backdrop side (`x = +/-(contentW/2 + DECO_WIDTH)`) with its glyph
  band centred on the slab depth (`z = -1.5 x BODY_DEPTH`, half a glyph proud
  of each glass face, the sign following the pre-rotation), so on a turned
  window or on the bookshelf the title reads on the green edge like a book
  spine instead of floating over the window rim. Placement derives from the
  public `Frame3DWindowDecoration.BODY_DEPTH` / `DECO_WIDTH` constants so the
  app helper and the decoration cannot drift apart.
- **Blank desktop (missing icons/wallpapers/background chooser)** — caused by the
  `resources/` classpath-prefix mismatch: lg3d requests artwork under a top-level
  `resources/` prefix, but the per-module Gradle builds emit those assets at other
  paths. Resolved by the additive `runtimeResources` assembly (no jar
  restructuring). The background manager's taskbar icon
  (`resources/images/icon/bgicon*.png`) was included in the same fix, clearing the
  `downImage cannot be null` error.
- **Startup halt on modern JVMs** — removed `Main.java`'s obsolete blocking
  "upgrade to JDK 1.6 (Mustang)" dialog, which fired on any non-1.6 JVM.
- **`StringIndexOutOfBoundsException` at startup** — `LgBuildInfo`'s `JAVA_VERSION`
  token must be a full version string (`21.0.12`), because `Main.java` takes
  `substring(0,5)`; a bare `21` crashed.
- **`UnsatisfiedLinkError` during `VirtualUniverse` init** — the Jogamp native
  classifier jars are not pulled transitively and had to be added explicitly.
- **Compile/runtime Java-version guard** — the `run` task now uses
  `javaLauncher = javaToolchains.launcherFor { languageVersion = 21 }` so the
  desktop runs on the same JDK it was compiled with, not the (possibly newer) JVM
  that launched Gradle.
- **Negative taskbar indices** — `Taskbar.addTaskbarItem(item, -n)` now places
  the item n-th from the right of the right-hand group (`-1` rightmost) instead
  of clamping every negative index to append-at-end, so the dock stacks sit
  immediately before Exit regardless of plugin initialisation order.
- **`SwingNode` blank quads + stray `JFrame`s** — Swing content (desktop
  widgets, the file/task manager, control center, dock stack popups and the
  SwingNode/StickyNote demos) rendered as an empty white rectangle while the
  hidden `SwingNodeJFrame` popped up as a real window. The offscreen capture
  lived in the excluded `lg3d-awt` peer toolkit (`lg.use3dtoolkit`, off in this
  build); `SwingNode` now paints its panel into a power-of-two `Texture2D`
  directly on stock JDK 21, driven by a `RepaintManager` repaint hook, and never
  maps the hidden frame.
- **`SwingNode` content nearly invisible (over-transparent)** — the offscreen
  capture above used an alpha-less `RGB` texture under `TextureAttributes.REPLACE`
  with `TransparencyAttributes.FASTEST` (screen-door); on Jogamp the fragment
  alpha came out ~0, so apps and widgets faded to barely-visible even when they
  called `setTransparency(0.0f)`. The capture now uses an `RGBA` texture laid over
  an opaque backdrop, and `DefaultSwingNodeRenderer` blends with
  `BLENDED` + `SRC_ALPHA`/`ONE_MINUS_SRC_ALPHA` (the same configuration
  `SimpleAppearance` uses to render native windows opaque) defaulting to fully
  opaque; translucency stays opt-in via `setTransparency`.
- **Terminal launcher dropped when `xterm` is absent** — the taskbar and
  start-menu Terminal items hard-referenced `xterm`, so on a host without it
  `ApplicationDescription.isApplicationAvailable` returned false, discovery
  logged `Executable xterm not found, ignoring taskbar item` and the item
  silently disappeared. Both configs now carry a portable fallback list
  (`alternateExec` on the taskbar `ApplicationDescription`, `alternateCommands`
  on the start-menu `StartMenuItemConfig`) of `gnome-terminal`, `konsole`,
  `xfce4-terminal`, `mate-terminal`, `lxterminal`, `xterm`, so whichever emulator
  is installed is used and the item (renamed **Terminal**) is shown. In dev mode
  the native terminal still opens as an ordinary host window, not embedded in the
  3D scene — embedding real X11 clients needs the separate `-Pcompositor` path.
- **All `GlassyText2D` labels invisible** (window titles, taskbar/button text,
  Image Studio toolbar) — two compounding Jogamp migration bugs. (1) The glyph
  texture was built with `ImageComponent2D(..., byReference=true)`, so its pixels
  were never uploaded and every label sampled a fully transparent texture;
  `GlassyTextTextureGenerator` now copies the image (`byReference=false`).
  (2) The texture was uploaded `yUp=true` while the quad's texture coordinates
  sample `v` in `[0, heightRatio]`, so the sampled region missed the glyph rows
  entirely; the generator now uploads with the image origin at the upper left
  (`yUp=false`) and `GlassyText2D` maps the quad bottom edge to `v=0` so the text
  reads upright.
- **Glass panels washed out to near-invisible white** — `GlassyPanel` sets white
  per-vertex `COLOR_4` values, which under Java 3D *replace* the `Material`
  ambient/diffuse, so the themed tint (e.g. the green window decoration) never
  showed. `GlassyPanel` now tints its vertex colors by the material's diffuse
  color (reading it via a new `Material.ALLOW_COMPONENT_READ` capability on
  `SimpleAppearance`).
- **Image Studio histogram channels swapped** — for `TYPE_3BYTE_BGR` images the
  JAI histogram band order is `{2,1,0}`, so band 0 is red; `Histogram3D` now maps
  bands to R/G/B with the identity `{0,1,2}` instead of reversing them.
- **Image Studio open (file chooser / filmstrip) appeared to do nothing** —
  `ImageCanvas3D.setImage` attached the new `Texture2D` to the live appearance
  (`Appearance.setTexture`) while its `ImageComponent2D` still held no pixels;
  under Jogamp that makes `TextureRetained.setLive` dereference null image data
  and throw, and the NPE propagated back through `EditorModel.setImage` into
  `FileStrip3D.loadPath`'s catch, which reported "Could not open ..." and left
  the canvas on the old image. The canvas now paints and uploads the pixels
  before building/attaching the texture, so opening a file updates the viewport
  and histogram.
- **`Frame3D` maximize only enlarged the window in place and covered the taskbar**
  — clicking maximize on a native 3D app window scaled it about its current
  origin without re-centering, so an off-center window merely grew (~2x), the
  pre-maximize position was lost on restore, and filling the full screen height
  made the window overlap the bottom taskbar. `Frame3DWindowDecoration.toggleMaximized`
  now saves both the final scale and translation, fits the window into the
  *usable* desktop area above the taskbar (a new `Taskbar.getReservedBottomHeight()`
  published by `AdvancedGlassyTaskbar`/`GlassyTaskbar`), applies an aspect-preserving
  uniform scale (`min(screenW/frameW, usableH/frameH) * margin`, so the aspect
  ratio is never distorted and the width stays proportional), centers the window
  in that usable band on the front plane, and restores the original scale *and*
  position on toggle-off.
- **Image Studio maximized without filling the screen width** — the frame's
  preferred size used fixed fractions of the raw screen (0.62 W x 0.66 H), an
  aspect narrower than the usable desktop band, so the aspect-preserving
  maximize scale hit the height bound first and left wide empty margins left
  and right while the height looked correct. `ImageStudioFrame3D` now derives
  its preferred size from the usable area (`screenHeight -
  Taskbar.getReservedBottomHeight()`) with the matching aspect, so a maximized
  window fills the viewport on both axes (uniform 5% margin) and the normal
  window keeps screen proportions.

### Known non-fatal runtime messages
These are harmless and expected in dev mode:
- `Executable <app> not found, ignoring taskbar item` — a sample taskbar or
  start-menu item references an app that is not installed on the host and has no
  available alternate (e.g. Firefox/Thunderbird). The **Terminal** item no longer
  triggers this: it now resolves through its `alternateExec` / `alternateCommands`
  fallback list to an installed emulator.
- `Could not lock System prefs` — the JDK preferences backing store warning.
- `No default preferences file found: /etc/lg3d/skel/prefs.xml` — first-run
  defaults are created instead.
- `PluginJ3fData` user-data `ClassNotFoundException` in `J3fLoader` — the original
  J3dFly plugin class is not present; the model geometry still loads.
