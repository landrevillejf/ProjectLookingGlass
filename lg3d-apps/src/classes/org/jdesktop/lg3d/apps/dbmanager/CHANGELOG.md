# Changelog

All notable changes to Dbmanager are documented here.

## [1.9.0] — 2026-09-24

### Added

- **Database Manager** — a new
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
