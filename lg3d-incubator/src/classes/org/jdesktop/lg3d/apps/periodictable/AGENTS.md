# PeriodicTable3D

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Dormant prototype** (2006-era; compiles, not start-menu registered) |
| Entry point | `periodictable.PeriodicTable3D` (`public static void main`) |
| Surface | **pure-3D** element grid (`ImageFactory` builds element tiles as scene-graph nodes) |
| Start-menu name / group | PeriodicTable3D / **Early Prototypes** — descriptor `PeriodicTable3D.lgcfg` is in `lg3d-incubator/src/config` → `config/` (**not scanned**) |
| Command | `java org.jdesktop.lg3d.apps.periodictable.PeriodicTable3D` |
| Runtime notes | Self-contained; bundles `PeriodicTable3D.jar` + a `resources/PeriodicTable3D.png` icon in the descriptor |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A native-3D periodic-table visualization. Dormant; its descriptor
  lives in the incubator's unscanned `config/`, so it never reaches the start menu.
  Promotion needs the descriptor moved to `lg3d-demo-apps/src/config`.
- **Engineer / Developer** — Obey the core UI/UX rulebook (upload texture pixels
  before attach, wrap raw `Node`s in `Component3D`, sort translucency, EDT hops).
  Element tiles should be power-of-two textures. Dev mode routes no keyboard focus —
  click-driven only. Jogamp packages only.
- **QA** — Verify the table renders with the in-JVM probe + internal screencapture; a
  black host capture under Wayland is not a defect. Not start-menu reachable today.
- **Business Analyst** — Educational/historical showpiece. No committed product value.
- **Functional Analyst** — Spec as a demonstration (render the element grid, pick an
  element). Record the descriptor-not-scanned fact.
- **Project Manager** — Commit scope `lg3d-incubator`. Dormant — low priority.
- **UI/UX (3D & 2D)** — **3D only**: element tiles as scene-graph nodes. Follow the
  glassy vocabulary and depth ordering from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
