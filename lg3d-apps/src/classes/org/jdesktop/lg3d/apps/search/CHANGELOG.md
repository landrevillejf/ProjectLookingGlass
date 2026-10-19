# Changelog

All notable changes to Search are documented here.

## [1.64.0] — 2026-10-07

### Added

- **Advanced Search: a streaming file &amp; content finder bound to Ctrl+Shift+F**
  (`lg3d-core` `org.jdesktop.lg3d.utils.search` +
  `...displayserver.desktop2d`; `lg3d-apps` `org.jdesktop.lg3d.apps.search`) — a
  new production search utility for the desktop and the minimal LFS system, with
  no external indexer dependency (it works on a bare X11/Wayland session with only
  a JVM). A new headless engine (`SearchEngine` + an immutable `SearchQuery`
  builder) walks one or more roots with a fixed pool of daemon workers sized to
  the CPU count and *streams* each hit to the caller the moment it is found, so
  results appear live instead of after the whole tree is scanned. It supports
  name matching in three modes (case-sensitive substring, shell glob and regex,
  each compiled once and shared read-only across workers), an optional content
  grep that detects binaries by a NUL sniff and skips huge files, and type /
  min-max size / modified-within-days filters, with a hard result cap and a
  cancel handle so a runaway walk can be stopped. Every hit is
  graded for relevance (exact &gt; prefix &gt; mid-string name match, shallower path,
  recency, number of content hits) and the UI re-ranks best-first on completion.
  Robustness first: unreadable directories, permission errors, dangling symlinks
  and vanished files are skipped rather than thrown, symlinked folders are never
  descended into (so loops cannot hang the walk), and an invalid glob/regex
  matches nothing instead of crashing. The `SearchPanel` UI uses only
  SwingNode-safe widgets (JList selectors, plain JButtons, JTextField, JTable,
  JTextArea) per the offscreen rule, shows a content-preview pane with matching
  line numbers and snippets, and opens a hit or its containing folder through the
  shared `Opener`. It is a Start-Menu &rarr; Utilities app (`search.lgcfg`, a new
  magnifier `search.png` icon) registered as a hosted panel in
  `Desktop2DAppRegistry` (so the same panel serves the 2D MDI frame and the 3D
  `SwingNode` window), and is wired to a new desktop-wide `Ctrl+Shift+F`
  accelerator via `ShortcutMap.SEARCH` / `Shortcuts.Target.search()`.
