# Changelog

All notable changes to Dockermanager are documented here.

## [1.53.1] — 2026-10-06

### Added

- **Docker Manager for the 2D/Swing and 3D desktops** (`lg3d-apps`,
  `org.jdesktop.lg3d.apps.dockermanager`; registration in `lg3d-core`, icon in
  `lg3d-core` resources) — a production Docker workbench ported **verbatim** from
  the Swing IDE docker plugin with its appearance preserved
  exactly: the plugin's `DockerManagementPanel` (a tabbed **Terminal** /
  **Containers** / **Files** / **Images** workspace) plus its `ContainerInfo` /
  `ImageInfo` / `ContainerTableModel` / `ImageTableModel` / `StatusCellRenderer` /
  `FileTreeCellRenderer` support classes were copied into the desktop and adapted
  only where the plugin-API shell was stripped (package rename, Lombok `@Slf4j` /
  `@Data` replaced by an explicit SLF4J logger and plain getters/setters, and the
  IDE's `Project`/`ProjectManager` coupling reduced to a `File projectRoot` with a
  no-arg constructor for reflective hosting). Every toolbar button, tab and
  context-menu glyph still resolves through the bundled `IconManager` library
  (`libs/IconManager-1.6.0.jar`, already on the `lg3d-core` run classpath and now a
  compile-only + test dep of `lg3d-apps`), which is what keeps the look identical
  to the source plugin; there is **no bundled docker-java and no Java 3D** — the
  panel drives the system `docker` CLI over `ProcessBuilder`/`Runtime.exec` on a
  cached thread pool, and with no daemon on the PATH it degrades to a readable
  "not running" status rather than failing. The one panel serves both desktops: in
  2D `Desktop2DAppRegistry.PANEL_APPS` opens it as an MDI internal frame, in 3D the
  new `DockerManager` wrapper hosts it on a `SwingNode` inside a `Frame3D` via
  `TitledSwingWindow`. Registered in the start menu (**Developers** group) via
  `docker.lgcfg` in `lg3d-apps` `src/config`, with a Docker whale-and-containers
  icon in `lg3d-core/src/resources/images/icon/docker.png`. Covered by the headless
  `DockerManagementPanelTest` (no-arg + project-root constructors build without
  throwing, tabs lay out, `cleanup()`/`refreshAll()` are safe) plus a
  `Desktop2DAppRegistry` mapping; real container/image round-trips need a live
  daemon and are verified via the in-JVM probe + `lgscreen-*.png` capture.
