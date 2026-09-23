# Clock (3D Clock)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Dormant prototype** (2006-era; compiles, not start-menu registered) |
| Entry point | `clock.ClockContainer` (abstract `Clock` extends `Container3D`; `AnalogicClock` / `MixedClock`) |
| Surface | **pure-3D** `Container3D` clock faces; `ClockTimeAction` (`TimerTask`) drives the hands |
| Start-menu name / group | Clock / **Utilities** — descriptor `clock.lgcfg` is in `lg3d-incubator/src/config` → bundled to `config/` (**not scanned**), so it does **not** appear in the menu |
| Command | `java org.jdesktop.lg3d.apps.clock.ClockContainer` |
| Runtime notes | Self-contained (no external libs); a `StartMenuTapp` variant exists in the legacy descriptor |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A self-contained native-3D widget built on `Container3D` +
  `TimerTask`. Dormant: to ship it, its descriptor must move to
  `lg3d-apps/src/config` (incubator `src/config` is not scanned).
- **Engineer / Developer** — Obey the core UI/UX rulebook (texture pixels before
  attach, `Component3D` wrapping, translucency sorting, EDT hops). The time action
  runs on a timer thread — hop to the correct thread before mutating the graph.
  Jogamp packages only.
- **QA** — Verify it renders and the hands advance with the in-JVM probe + internal
  screencapture (`lg3d-core/lgscreen-*.png`); a black host capture under Wayland is
  not a defect. Note it is **not** start-menu reachable today.
- **Business Analyst** — Nostalgic/niche prototype (a 3D desk clock). No committed
  product value; a candidate for promotion if a desktop clock is wanted.
- **Functional Analyst** — Spec as a demonstration (analog/mixed 3D clock face).
  Record the descriptor-not-scanned fact so it is not mistaken for a broken app.
- **Project Manager** — Commit scope `lg3d-incubator`. Dormant — low priority;
  promotion requires a descriptor move to `lg3d-apps`.
- **UI/UX (3D & 2D)** — **3D only**: clock faces as scene-graph nodes. Follow the
  glassy vocabulary and depth ordering from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
