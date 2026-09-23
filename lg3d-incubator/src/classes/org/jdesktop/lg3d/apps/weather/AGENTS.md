# Weather — dormant prototype

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Dormant prototype** (GPL-licensed 2006-era; compiles, no descriptor, not start-menu registered) |
| Entry point | `weather.weather` (`public static void main`); `weather.config` holds settings and also has a `main` |
| Surface | **pure-3D** panel: builds a `Frame3D` + `Component3D` with a `utils.shape.Box` and `SimpleAppearance` |
| Start-menu name / group | **None** — there is no `weather.lgcfg` in `src/config`, so it is not discoverable at all |
| Command | `java org.jdesktop.lg3d.apps.weather.weather` |
| Runtime notes | Any live weather feed would need a network data source; the shipped code is a self-contained 3D panel |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A minimal pure-3D weather display (a `Frame3D` + `Box`/`SimpleAppearance`
  panel). Dormant and unregistered: it has no `.lgcfg`, so it never reaches the start menu.
  Promotion needs a descriptor added to `lg3d-apps/src/config` **and** a real data
  source wired in.
- **Engineer / Developer** — Obey the core UI/UX rulebook (texture pixels before attach,
  wrap nodes in `Component3D`, sort translucency, EDT hops). Any network fetch must run off
  the render thread. Dev mode routes no keyboard focus — click-driven only. Jogamp only.
- **QA** — Verify the panel constructs/renders with the in-JVM probe + internal
  screencapture; a black host capture under Wayland is not a defect. Not start-menu
  reachable (no descriptor).
- **Business Analyst** — Historical weather-widget concept. No committed product value and
  no live data feed.
- **Functional Analyst** — Spec as a demonstration (render a weather panel). Record the
  absent-descriptor fact and the missing data source.
- **Project Manager** — Commit scope `lg3d-incubator`. Dormant — low priority.
- **UI/UX (3D & 2D)** — **3D only**: a box/appearance panel. Follow the glassy vocabulary
  and depth ordering from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
