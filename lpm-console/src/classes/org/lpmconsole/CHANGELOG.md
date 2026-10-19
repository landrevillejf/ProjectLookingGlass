# Changelog

All notable changes to Lpmconsole are documented here.

## [1.9.0] — 2026-09-24

### Added

- **LPM Console** — a graphical package-manager
  front-end for LPM (the BLFS package manager), delivered as a standalone Java 21
  Swing module and wired into the lg3d desktop start menu under the *System*
  group. It is a real GUI (category rail over a sortable, searchable package
  table with a details pane, action toolbar, collapsible log and status bar),
  not a terminal: package listings are read read-only from LPM's database , while every mutation shells out to `/usr/bin/lpm` with
  `--no-color`, previews with `--dry-run`, and confirms destructive actions in
  an in-panel overlay (modal dialogs escape the SwingNode capture). It honours
  the LPM Control Application Contract : no
  re-implementation of dependency resolution, checksums, locking or rollback.
  The panel is registered with `Desktop2DAppRegistry` so it is hosted as an MDI
  internal frame in the 2D/Swing desktop, and its `swingapp` descriptor lets
  `SwingNodeWindowCapture` texture its window into the 3D desktop; its jar is
  added to the `:lg3d-core:run` classpath and a drawn package-box start-menu
  icon is generated.
