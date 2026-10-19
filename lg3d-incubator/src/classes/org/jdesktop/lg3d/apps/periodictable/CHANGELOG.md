# Changelog

All notable changes to Periodictable are documented here.

## [1.28.0] — 2026-09-30

### Added

- **Periodic Table Swing panel for the 2D desktop** (`lg3d-incubator`,
  `org.jdesktop.lg3d.apps.periodictable`; registered in `lg3d-core`'s
  `Desktop2DAppRegistry`, descriptor in `lg3d-apps/src/config`) — the
  native-3D `PeriodicTable3D` app (untouched) gains a pure-Swing counterpart,
  `PeriodicTablePanel`, that renders the standard 18x7 grid plus the detached
  lanthanide and actinide rows, colours each cell by category with a legend,
  shows the mass and category as a hover tooltip, and fills a detail line under
  the grid when an element is clicked. All 118 elements (with modern symbols and
  names — Copernicium rather than the 2006 "Uub", plus Flerovium, Livermorium,
  Nihonium, Moscovium, Tennessine and Oganesson) are embedded as a compact
  string constant parsed by a pure static helper into an immutable `Element`
  record list, and the grid placement is a pure `gridPosition` function; the
  panel uses a null layout with explicit `setBounds` and a fixed preferred size
  (the `CalculatorPanel`/`ChessPanel` SwingNode-offscreen rule). The one
  start-menu descriptor (keyed on the 3D main class) now launches the panel as an
  MDI frame in the 2D desktop while the 3D desktop keeps building the `Frame3D`.
  Because discovery only scans `config/demo` and `config/incubator` — and the
  incubator's own `src/config` bundles to the unscanned jar-root `config/` — the
  descriptor lives in `lg3d-apps/src/config` (bundled to `config/demo`),
  following the Chess 3D / Agenda 3D precedent, so the app is genuinely reachable
  from the start menu for the first time. The entry files under a new
  **Education** category: the group is declared in
  `lg3d-core/src/etc/lg3d/startmenu.lgcfg` (a `StartMenuGroupConfig` plus a link
  from `Main`, shared by the 3D and the 2D start menus) — an item naming an
  undeclared group would otherwise be appended to the menu root as an orphan
  instead of under its category. Covered by headless JUnit 5 tests
  (`PeriodicTablePanelTest` for the 118-element parse, contiguous atomic numbers,
  categories, layout and selection; a `Desktop2DAppRegistryTest` mapping
  assertion).
