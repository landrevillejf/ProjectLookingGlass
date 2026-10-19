# Changelog

All notable changes to Help are documented here.

## [Unreleased]

### Added

- **The Help Center now writes its own What's New page from the CHANGELOG**
  (`lg3d-apps`, `org.jdesktop.lg3d.apps.help` + `scripts/mandela/`) — the built-in JavaHelp
  guide used to say only what a person remembered to write into a topic, so it went stale on
  the day its refresh PR merged, and the desktop's own release notes were unreadable from
  inside the desktop. `whats-new.mnd` now renders the `[Unreleased]` section and the three
  most recently released ones into a nineteenth topic , which the existing
  `generateHelpSearchIndex` task indexes like any other because it globs every `*.html` in
  the directory, so release notes are full-text searchable from the Search tab. The same
  script is the gate: run without its `write` word it re-renders in memory and fails when the
  committed topic no longer matches `CHANGELOG.md`, which is what turns "remember to refresh
  the help" into a build failure, and it reports how many `Added` entries are still not named
  in any topic — the candidates for a real new page. The rendered topic is a committed
  generated file: edit `CHANGELOG.md` and re-run `:lg3d-mandela:whatsNewRefresh`, never the
  HTML.

## [1.14.0] — 2026-09-25

### Added

- **Help Center coverage for the 2D/Swing desktop features** — the JavaHelp user guide now documents the
  desktop conveniences added this cycle. *The 2D and Swing Desktops* topic gains
  sections for type-to-search Start Menu filtering, edge window snapping, the
  **Alt+`** window switcher, the global keyboard-shortcut table, the **Alt+F2**
  run-command dialog, notifications and toasts, the volume / network / battery
  taskbar indicators, the clock's calendar popup and session restore; *The
  Taskbar* and *The Start Menu* topics cross-link to it, and *Built-in
  Applications* now lists the **About** app. The keyword index gains an entry for
  each concept. This is a content-only change — no new topic — so `map.jhm`,
  `toc.xml` and the `HelpContentTest` target set are unchanged, and the full-text
  search index is regenerated at build time by
  `:lg3d-apps:generateHelpSearchIndex`.


## [1.9.0] — 2026-09-24

### Added

- **Help Center** — a complete,
  navigable desktop user guide built on the latest **JavaHelp** , replacing the old static-image *Simple Sample
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
