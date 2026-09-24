# db-manager

> Role-aware guide for everyone working on **db-manager**. The root
> [`../AGENTS.md`](../AGENTS.md) governs the build system, module map and commit
> conventions; [`../lg3d-core/AGENTS.md`](../lg3d-core/AGENTS.md) is the
> canonical UI/UX rulebook for the 3D desktop. This file adds module-specific
> guidance and a shared **per-role view** so all roles stay coherent.

## Module at a glance

| Item | Value |
| --- | --- |
| Purpose | A self-contained, **driver-agnostic JDBC database client** styled after DBeaver: connection profiles, a metadata navigator, a multi-tab SQL editor with syntax highlighting, a results grid (paging + sort), CSV export, DDL viewing and transaction control. |
| Root package | `org.jdesktop.lg3d.dbmanager.*` — `model` (profiles, settings, drivers, persistence), `jdbc` (connection, metadata, query, DML/DDL, CSV), `session` (a live connection + its executor), `ui` (the Swing client). |
| Depends on | Jackson (JSON persistence), slf4j (+nop); runtime JDBC drivers SQLite + H2 (embedded, offline, and back the tests) and PostgreSQL + MariaDB. Tests use JUnit 5, Mockito, AssertJ. **No `lg3d-core` dependency.** |
| Surfaced as | The **"Database Manager"** start-menu app (Developers group): `DbManagerPanel` (`lg3d-apps`) → `DbManager` (`TitledSwingWindow`/`SwingNode` in 3D) or an MDI frame in the 2D desktop via `Desktop2DAppRegistry.PANEL_APPS`. |
| Build / test | `./gradlew :db-manager:test` (132 tests, headless, real JDBC over H2/SQLite) · `:db-manager:build` · `:db-manager:pitest` (report-only). |
| Config | Profiles + settings persist as JSON under `~/.lg3d/dbmanager/`; override the directory with the `lg3d.dbmanager.dir` system property (`ProfileStore.DIR_PROPERTY`) for test isolation. |

## How the roles work together

This module is a **pure Java/Swing library with no lg3d coupling**. The Architect
guards the driver-agnostic JDBC seam and the strict "all I/O off the EDT" rule;
Engineers keep the core compiling only against `java.sql` (drivers are runtime
pluggables) and keep the panel constructor non-throwing; QA runs the headless
suite against real in-memory H2 / temp-file SQLite; the Analysts keep the
password-handling and custom-driver contracts explicit; the PM tracks the
desktop-integration dependency (`lg3d-apps` wrapper + run/release classpath).
Everyone works from this file plus the root `AGENTS.md`.

## Architect

- Keep the core **driver-agnostic**: it compiles only against the JDK `java.sql`
  API and loads drivers through `DriverManager` (or a `URLClassLoader` + shim for
  a user-added JAR). Never hard-code a vendor's SQL dialect in the core; read
  structure through `DatabaseMetaData`.
- Preserve the **layering**: `model` (data + persistence) → `jdbc` (blocking,
  immutable-snapshot operations) → `session` (`DbSession` owns one live
  connection + its `QueryExecutor`) → `ui` (Swing). Lower layers never import
  Swing.
- **All JDBC I/O runs off the EDT** (`SwingWorker` / a per-connection executor),
  with connection + query timeouts, strict resource close, and cancellation via
  `Statement.cancel()`. This is the reliability contract; do not regress it.
- The module is **not** wired into the display server directly — it is surfaced
  only through the `lg3d-apps` panel. Keep that loose coupling; do not add a hard
  `lg3d-core` dependency.

## Engineer / Developer

- **Passwords are never saved by default.** Saving is an explicit opt-in and
  stores an *obfuscated* value (`PasswordObfuscator`, `obf1:` prefix — XOR +
  Base64, **not** encryption) under `~/.lg3d/dbmanager/`; the UI warns that it is
  only obfuscated. Never commit a profile with a secret, and never log one.
- Persistence is JSON via Jackson (`ProfileStore`, `AppSettings`). Point the
  directory at a temp folder in tests via `ProfileStore.DIR_PROPERTY`; do not
  read or write the developer's real `~/.lg3d/dbmanager` from a test.
- Keep the `ui` panel constructors **non-throwing** and headless-safe: build with
  `java.awt.headless=true`, create modal dialogs only on user action, and degrade
  to a readable message when a driver is missing (the wrapper does the same on
  `LinkageError`).
- **Swing document rule:** never mutate a `StyledDocument` from inside its own
  insert/remove notification. `SqlHighlighter` defers its rescan with
  `SwingUtilities.invokeLater` — keep it that way (a synchronous
  `setCharacterAttributes` inside `insertUpdate` throws
  `IllegalStateException: Attempt to mutate in notification`).
- The `:lg3d-core:run` and `:lg3d-core:releaseBundle` classpaths are
  hand-assembled, so a new runtime dep (or JDBC driver) needs an explicit
  detached-configuration entry there (see *Desktop integration*).
- **Inline grid cell-editing is intentionally not wired yet.** `DmlBuilder`
  exists and is unit-tested (it builds INSERT/UPDATE/DELETE from grid edits via
  the primary key), but `ResultsTable` is read-only (`isCellEditable=false`);
  edits happen through the SQL editor. Do not claim in-place grid editing.

## QA

- The suite is **headless** (`java.awt.headless=true`, set by the module's test
  task) and uses **real JDBC** — in-memory H2 (`jdbc:h2:mem:NAME;DB_CLOSE_DELAY=-1`)
  and temp-file SQLite — not mocks, for the data layer. Run
  `./gradlew :db-manager:test` (132 tests).
- Isolate persistence with `@TempDir` + `ProfileStore.DIR_PROPERTY`, and drop
  tables created in a shared `DB_CLOSE_DELAY=-1` database in `setUp` so tests do
  not leak state into each other.
- H2 2.x reports the SQL-standard type name (`CHARACTER VARYING`, not `VARCHAR`);
  assert on driver-agnostic substrings rather than a vendor literal.
- PIT is report-only (`mutationThreshold = 0`,
  `avoidCallsTo = ['java.awt', 'javax.swing']`). Coverage/mutation gates are the
  stated 100% / 0 target; a conservative `db-manager` coverage floor is pinned in
  the root `build.gradle`.

## Business Analyst

- Value: a **trustworthy, easy-to-use SQL workbench** bundled with the desktop —
  browse any JDBC database, run queries, export results, and control
  transactions — without installing a separate tool.
- The **safety posture** is deliberate: passwords are not stored unless the user
  explicitly opts in (and then only obfuscated, with a warning), queries are
  row-limited and cancellable, and destructive DDL/DML goes through the editor
  where the user sees the SQL.

## Functional Analyst

- Specify behaviour in terms of **user-visible function** (connect, browse the
  metadata tree, edit and run SQL, page/sort/export the grid, toggle auto-commit,
  commit/rollback) and the **JDBC contract** (`DatabaseMetaData` reads, statement
  splitting, `fetchSize` paging, row cap, cancellation).
- Keep the **driver model** explicit: bundled drivers (SQLite, H2, PostgreSQL,
  MariaDB — the latter also covers MySQL) plus a user-configurable "add a custom
  driver/JAR" path (`DriverRegistry` / `DbDriver`). No proprietary Oracle/SQL
  Server driver is bundled.
- Document the read-only-grid limitation as a functional scope boundary, not a
  defect.

## Project Manager

- Commit scope is **`db-manager`** (or `agents` for this file). Branch → commit →
  push → PR against `main`; never commit to `main`.
- This module is coupled to the desktop integration in `lg3d-apps` (the wrapper)
  and `lg3d-core` (the registry entry + run/release classpath); a change to the
  public panel API or a new runtime dep must update those in the same PR.
- Done = `:db-manager:test` green + `build` (jar + checkstyle report-only +
  jacoco) + the desktop integration (start-menu app) verified.

## UI/UX (2D)

- **2D Swing only.** The user surface is `DbManagerMainPanel` (toolbar NORTH, a
  navigator|editor `JSplitPane` CENTER, status/log SOUTH) embedded in
  `DbManagerPanel`; in the 3D desktop it is hosted on a `SwingNode` inside a
  `Frame3D` via `TitledSwingWindow`, and in the 2D desktop as an MDI internal
  frame — the **same panel** drives both.
- Because it is hosted offscreen on a `SwingNode`, follow the core rulebook's
  SwingNode rules: no modal dialogs escaping the capture (use in-panel overlays),
  `dispose()` the node when discarded, and hop to the EDT from listeners. See
  [`../docs/swingnode.md`](../docs/swingnode.md).
- The UI is **English**, light-themed, idiomatic Swing; the architecture leaves
  room for a dark theme later. Keep every blocking JDBC call off the EDT so the
  UI stays responsive and Stop can cancel a long-running statement.

## Communication & coherence

- Single source of truth: this file (module), the core UI/UX rulebook (UI), the
  root [`../AGENTS.md`](../AGENTS.md) (build/commits). Conflict → root wins, fix
  here in the same PR.
- Every PR states: the layer touched (model / jdbc / session / ui), whether the
  persistence schema, driver model or password handling changed, whether a new
  runtime dep needs a run/release classpath entry, and the headless-test evidence.

## Commit / PR

- Conventional Commit scope **`db-manager`** (or `agents` for this file).
  Imperative subject ≤ 50 chars; body wrapped at 72.
- Add a `CHANGELOG.md` bullet under `[Unreleased]`; do **not** bump the version.
- Stage only intended paths (never `git add -A`; exclude build output). Run
  branch → commit → push → PR against `main`.

---

## Overview

A self-contained, **driver-agnostic JDBC database client** for the desktop,
styled after DBeaver. It manages connection profiles, browses a database's
structure through the standard `DatabaseMetaData` API, runs SQL from a
multi-tab, syntax-highlighted editor (asynchronously, with cancellation and a row
cap), shows results in a paging/sorting grid, exports them to CSV, generates
CREATE TABLE DDL from metadata, and offers transaction control (auto-commit
toggle, commit, rollback).

It is a plain Java 21 / Swing library with a Maven-standard layout
(`src/main/java`, `src/test/java`) and **no `lg3d-core` dependency**, mirroring
the `update-manager` / `lpm-console` standalone-module pattern.

## Layers

- **`model`** — `ConnectionProfile` (name, dbType, JDBC URL, driver class, user,
  optional password, props, autoCommit, rowLimit), `ProfileStore` + `AppSettings`
  (Jackson JSON persistence under `~/.lg3d/dbmanager/`), `DriverRegistry` /
  `DbDriver` (bundled + user-added drivers), `PasswordObfuscator` (opt-in,
  obfuscated-not-encrypted secret storage).
- **`jdbc`** — `ConnectionProvider` (open/test/close via `DriverManager`,
  timeouts, custom-driver `URLClassLoader`), `MetadataReader`
  (catalogs/schemas/tables/views/columns/PK/FK, lazy), `QueryExecutor`
  (async execution, `Statement.cancel`, `fetchSize`, row cap, timing, error
  capture), `SqlStatementSplitter` (multi-statement scripts), `QueryResult` /
  `ColumnMeta` (immutable snapshots), `DmlBuilder` (INSERT/UPDATE/DELETE from
  grid edits via PK), `DdlGenerator` (CREATE TABLE from metadata), `CsvExporter`.
- **`session`** — `DbSession` (one live connection + its executor/metadata,
  transaction control, cancellation, idempotent close) and `ConnectionManager`
  (profiles + settings + the active session, custom-driver registry reload).
- **`ui`** — `DbManagerMainPanel` (the root client), `ConnectionDialog`,
  `NavigatorTree`, `SqlEditorTab` + `SqlHighlighter`, `ResultsTable` +
  `ResultTableModel`, `SettingsDialog`.

## Desktop integration

The module is **not** wired into the display server directly; it is surfaced as
the **"Database Manager"** start-menu app (Developers group), exactly like the
Software Update app:

- `lg3d-apps` → `org.jdesktop.lg3d.apps.dbmanager.DbManagerPanel` (a plain
  `JPanel` with a no-arg constructor) embeds `DbManagerMainPanel`. It never
  throws out of its constructor: if the client cannot be created (e.g. a missing
  runtime dependency) it degrades to a readable "unavailable" pane on
  `RuntimeException` / `LinkageError`.
- `DbManager` is the 3D-desktop wrapper: `TitledSwingWindow.show(...)` hosts the
  panel on a `SwingNode` inside a `Frame3D`.
- `lg3d-apps/src/config/dbmanager.lgcfg` registers the start-menu item
  (`command = java org.jdesktop.lg3d.apps.dbmanager.DbManager`).
- `Desktop2DAppRegistry.PANEL_APPS` maps the command to `DbManagerPanel`, so the
  2D/Swing desktop hosts the *same* panel as an MDI internal frame.
- The `:lg3d-core:run` **and** `:lg3d-core:releaseBundle` classpaths are
  hand-assembled from packaged jars, so they explicitly add the `db-manager` jar
  plus a detached configuration carrying its runtime deps: jackson-databind,
  slf4j-api/nop, and the bundled JDBC drivers (sqlite-jdbc, h2, postgresql,
  mariadb-java-client).

## Build & test

All Gradle invocations must run on the JDK 21 toolchain (Gradle 8.14 cannot run
on Java 25+):

```bash
export JAVA_HOME=/home/fedora/.jdks/jdk-21.0.12.1+1
./gradlew :db-manager:test          # 132 tests, headless, real JDBC (H2/SQLite)
./gradlew :db-manager:build         # jar + checkstyle (report-only) + jacoco
./gradlew :db-manager:pitest        # report-only mutation testing (on demand)
```

## Conventions & gotchas

- Tests run with `java.awt.headless=true`; the Swing panels construct headless
  and degrade to logging, and the JDBC tests use in-memory H2 / temp-file SQLite
  only (never a live server, never the developer's real config dir).
- `SqlHighlighter` must defer its rescan off the document notification
  (`SwingUtilities.invokeLater`); mutating a `StyledDocument` inside
  `insertUpdate`/`removeUpdate` throws `IllegalStateException: Attempt to mutate
  in notification` — and would break real typing in the editor.
- `ProfileStore.DIR_PROPERTY` (`lg3d.dbmanager.dir`) overrides the config
  directory; tests set it to a `@TempDir` so they never touch `~/.lg3d/dbmanager`.
- PIT mutation testing is configured report-only (`mutationThreshold = 0`,
  `avoidCallsTo = ['java.awt', 'javax.swing']`) for parity with the other modules.
