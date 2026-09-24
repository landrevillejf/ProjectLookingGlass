# Database Manager Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).
> The panel it hosts belongs to the [`db-manager`](../../../../../../../../db-manager/AGENTS.md) module.

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (host shim for the `db-manager` panel) |
| Entry point | `DbManager.main` → `TitledSwingWindow.show(...)` |
| Surface | **SwingNode-in-Frame3D** (hosts `DbManagerPanel` on a `SwingNode` quad); the same panel is reused in the 2D desktop |
| Start-menu name / group | Database Manager / **Developers** |
| Command | `java org.jdesktop.lg3d.apps.dbmanager.DbManager` |
| Descriptor | `src/config/dbmanager.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **DbManager** — thin entry point; installs the hosted look and feel and shows
  the `db-manager` module's `DbManagerPanel` in a `TitledSwingWindow`
  (`DbManagerMainPanel.WIDTH_PX` × `HEIGHT_PX`).
- **DbManagerPanel** — a plain `JPanel` (no-arg constructor, no Java 3D) that
  embeds the `db-manager` module's `DbManagerMainPanel` (connection profiles,
  metadata navigator, SQL editor, results grid, CSV export, transaction control).
  This app only hosts it.

## Roles

- **Architect** — This is a **hosting shim**, not the feature: the JDBC client and
  panel live in the `db-manager` module. Keep the boundary — `lg3d-apps` supplies
  the `Frame3D`/`TitledSwingWindow` 3D host, `db-manager` supplies the panel so
  the same UI drives both the 3D and 2D desktops. Do not fork database logic here.
- **Engineer / Developer** — Follow the core UI/UX rulebook for the SwingNode host:
  offscreen paint, no modal dialogs escaping the capture (in-panel overlays), EDT
  hops from lg3d listeners, `dispose()` on discard, hosted LAF via
  `installHostedLookAndFeel`. Keep the `DbManagerPanel` constructor
  **non-throwing**: it catches `RuntimeException` / `LinkageError` and degrades to
  a readable "unavailable" pane so a broken bundle cannot take down the host
  window. Behavioural changes belong in `db-manager`, not here. Jogamp packages only.
- **QA** — `DbManagerPanelTest` (headless) asserts the panel constructs without
  throwing, embeds the real client (db-manager is on this module's compile
  classpath), and reports the advertised host size; it isolates the config dir via
  `ProfileStore.DIR_PROPERTY`. The database/JDBC unit tests live in the
  `db-manager` module. Here, also verify the hosted window renders and delegates:
  in-JVM probe + internal screencapture (`lg3d-core/lgscreen-*.png`); a black host
  capture under Wayland is not a defect. Check the desktop log for
  `EventProcessor` warnings.
- **Business Analyst** — A daily-driver utility: browse and query any JDBC database
  from the desktop without installing a separate tool. Value = a trustworthy,
  easy-to-use SQL workbench; the query/transaction/password-handling flow is the
  product surface and lives in `db-manager`.
- **Functional Analyst** — Spec this app as the *host contract* (which panel, which
  window chrome, which start-menu slot: Developers group). The functional spec for
  database behaviour is owned by `db-manager/AGENTS.md`; keep the two coherent and
  cross-referenced.
- **Project Manager** — Commit scope `lg3d-apps` for this host; changes to database
  behaviour are a separate `db-manager` PR. Adding the app also touched
  `lg3d-core` (the `Desktop2DAppRegistry` panel mapping + the run/releaseBundle
  classpath) — call that out. Done = build + `./run-lg3d.sh` + capture/log
  evidence. Branch → PR against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame + transparency
  ordering. 2D: the shared `DbManagerPanel` (also used in the 2D desktop via
  `Desktop2DAppRegistry`) — keep it identical across both surfaces. Verify overlay
  ordering over a maximized window.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`; database behaviour → `db-manager/AGENTS.md`. On conflict the
higher file wins; fix here in the same PR. Every PR states the surface (SwingNode
host), the panel/module boundary, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump. Stage only intended paths (never `git add -A`).
