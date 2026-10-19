# Changelog

All notable changes to Openapieditor are documented here.

## [1.53.1] — 2026-10-06

### Added

- **OpenAPI Contract Editor** — the external
  **OpenAPI-Contract-Editor** project (a full-featured YAML/OpenAPI specification
  editor: RSyntaxTextArea syntax highlighting, swagger-parser validation,
  OpenAPI Generator code generation, Swagger-UI-coloured endpoint tree, YAML
  structure navigator, find/replace, undo/redo, drag-and-drop) integrated as a
  *Developers* start-menu app. Like the IDE, the editor is **not** loaded into
  the desktop JVM: it ships as a self-contained fat jar
  (`libs/openapi-editor.jar`, ~25 MB, built by the external project's own
  Gradle/shadow build and fetched on demand via a new
  `:fetchOpenApiEditorJar` task, so it stays out of git) and the new
  `OpenApiEditor` launcher forks it as a **separate child process** on the lg3d display. The isolation is load-bearing: the editor
  calls `System.exit` when its main window closes , which
  would otherwise tear down the desktop, and its fat jar bundles unrelocated
  third-party libraries (snakeyaml, swagger-parser, openapi-generator,
  RSyntaxTextArea, Jackson) that could clash with the desktop classpath —
  neither can reach the desktop JVM because the jar is never on it. The child's
  own `JFrame` therefore appears as an ordinary top-level window: composited
  over the 3D scene and beside the 2D/Swing desktop.
  `openapieditor.lgcfg` registers the menu item with the `java <class>` verb
  and a new `openapi-editor.png` icon (purple glass tile with a
  contract-page-and-braces glyph drawn in `GenerateAppIcons`); the jar path is
  resolved from a new `openapieditor.jar` system property (set by
  `:lg3d-core:run` and the release `lg3d.sh`, and the jar is bundled into
  `releaseBundle`), then `<lg.appcodebase>/libs`, then the working directory,
  degrading to a readable "unavailable" message when absent.
  `Desktop2DAppRegistry` classifies the launcher as a `SWING_FRAME` app so the
  2D desktop runs its (child-forking) main beside the desktop. Covered by a new
  headless JUnit 5 suite (`OpenApiEditorTest`, 12 tests pinning the jar-path
  precedence, child-command shape and display selection without ever spawning a
  process) and a `Desktop2DAppRegistryTest` classification case.
