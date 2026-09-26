# PeriodicTable3D

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Live** — native-3D app **plus** a plain-Swing 2D panel; start-menu registered (see descriptor note below) |
| Entry point (3D) | `periodictable.PeriodicTable3D` (`public static void main`) |
| Entry point (2D) | `periodictable.PeriodicTablePanel` (a `JPanel`; hosted as an MDI frame via `Desktop2DAppRegistry.PANEL_APPS`) |
| Surface | 3D: element grid as scene-graph nodes (`ImageFactory`). 2D: pure-Swing 18x7 grid + detached lanthanide/actinide rows, category colours + legend, hover tooltips, click-to-select detail line — no Java 3D |
| Start-menu name / group | **Periodic Table** / **Education** — descriptor `periodictable.lgcfg` lives in `lg3d-apps/src/config` (bundled to the **scanned** `config/demo`). The legacy `PeriodicTable3D.lgcfg` in this module's own `src/config` bundles to jar-root `config/`, which discovery does **not** scan, so it is inert (kept for the standalone 3D jar) |
| Command | `java org.jdesktop.lg3d.apps.periodictable.PeriodicTable3D` (one descriptor serves both desktops) |
| Runtime notes | Self-contained. The 2D panel embeds all 118 elements (modern symbols/names) as a parsed string constant; the incubator jar is on the `:lg3d-core:run` classpath so the reflective panel lookup resolves |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A periodic-table reference with two surfaces: a native-3D
  visualization (`PeriodicTable3D`) and a pure-Swing 2D panel
  (`PeriodicTablePanel`) following the games' panel-port pattern. Discovery only
  scans `config/demo` and `config/incubator`, so the start-menu descriptor lives
  in `lg3d-apps/src/config` (bundled to `config/demo`), exactly like Chess 3D and
  Agenda 3D; the module's own `src/config` descriptor is never scanned.
- **Engineer / Developer** — 3D: obey the core UI/UX rulebook (upload texture
  pixels before attach, wrap raw `Node`s in `Component3D`, sort translucency, EDT
  hops); element tiles should be power-of-two textures. 2D: the panel keeps the
  SwingNode-offscreen rule (null layout with explicit `setBounds`, fixed
  preferred size, `JList`/label-only controls), and its element data + grid
  layout stay pure static helpers so they are headless-testable. Jogamp packages
  only in the 3D path.
- **QA** — The 2D panel's data, layout and selection are covered by headless
  JUnit 5 (`PeriodicTablePanelTest`) and its registration by
  `Desktop2DAppRegistryTest`; verify the rendered table and the 3D app with the
  in-JVM probe + internal screencapture (a black host capture under Wayland is
  not a defect).
- **Business Analyst** — Educational/historical reference, now reachable from the
  start menu in both desktops.
- **Functional Analyst** — Spec: render the element grid, colour by category,
  hover for mass/category, click to select and show details. The descriptor
  location (scanned `config/demo`, not the module's inert `config/`) is the key
  discovery fact.
- **Project Manager** — Commit scope `lg3d-incubator` (plus `lg3d-apps` for the
  descriptor and `lg3d-core` for the registry mapping).
- **UI/UX (3D & 2D)** — 3D: element tiles as scene-graph nodes, glassy vocabulary
  and depth ordering from core. 2D: a flat category-coloured grid with a legend,
  matching the other Swing panels.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
