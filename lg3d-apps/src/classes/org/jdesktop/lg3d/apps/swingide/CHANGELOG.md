# Changelog

All notable changes to Swingide are documented here.

## [1.9.0] — 2026-09-24

### Added

- **IDE** — the external
  **swing-ide** project (a full-featured modular Java Swing IDE: multi-language
  code editor, Maven/Gradle/Ant build tools, Git, debugger, database explorer,
  plugin system) integrated as a *Developers* start-menu app. Unlike every other
  desktop app, the IDE is **not** loaded into the desktop JVM: it ships as a
  self-contained fat jar (`libs/swing-ide.jar`, built by swing-ide's own Gradle
  9.7.1 build/CI and fetched on demand via a new `:fetchSwingIdeJar` task, since
  its ~138 MB exceeds GitHub's 100 MB per-file limit) and the new `SwingIde`
  launcher forks it as a **separate child process** on the lg3d
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
