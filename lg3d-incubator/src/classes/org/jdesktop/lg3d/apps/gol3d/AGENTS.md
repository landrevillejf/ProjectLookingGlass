# Gol3D (Game of Life)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Dormant prototype** (2006-era; compiles, not start-menu registered) |
| Entry point | `gol3d.Gol3D` |
| Surface | **pure-3D** Conway's Game of Life grid (`GolGroundComponent` on `Component3D`; `GolGridReader` runs on a `Thread`) |
| Start-menu name / group | Gol3D / **Games** — descriptor `gol3d.lgcfg` is in `lg3d-incubator/src/config` → `config/` (**not scanned**) |
| Command | `java org.jdesktop.lg3d.apps.gol3d.Gol3D` |
| Runtime notes | Self-contained; the grid reader is a background thread |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A self-contained native-3D simulation. The generation loop runs on
  its own `Thread` (`GolGridReader`) — keep model stepping off the render thread and
  publish to the graph safely. Dormant; promotion needs a descriptor move to
  `lg3d-demo-apps/src/config`.
- **Engineer / Developer** — Obey the core UI/UX rulebook and the **live-texture
  rule** if the grid is drawn into a texture (single `ImageComponent2D`, `.set()` in
  place, never re-attach; POT sizes; pixels before attach). Synchronize the reader
  thread against the graph. Jogamp packages only.
- **QA** — Verify the grid evolves with the in-JVM probe + internal screencapture; a
  black host capture under Wayland is not a defect. The Life stepping logic can be
  unit-tested headless if extracted. Not start-menu reachable today.
- **Business Analyst** — Niche/nostalgic demo (3D Game of Life). No committed product
  value.
- **Functional Analyst** — Spec as a demonstration (a Life grid rendered in 3D).
  Record the descriptor-not-scanned fact.
- **Project Manager** — Commit scope `lg3d-incubator`. Dormant — low priority.
- **UI/UX (3D & 2D)** — **3D only**: a ground/grid component in a `Frame3D`. Follow
  the glassy vocabulary and depth ordering from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
