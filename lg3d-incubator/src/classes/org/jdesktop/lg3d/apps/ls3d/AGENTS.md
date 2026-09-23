# Ls3D (Director Listing)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Dormant prototype** (2006-era; compiles, not start-menu registered) |
| Entry point | `ls3d.Ls3D` |
| Surface | **pure-3D** directory listing (`FilePanel` extends `Component3D`) |
| Start-menu name / group | Ls3D (Director Listing) / **Utilities** — descriptor `ls3d.lgcfg` is in `lg3d-incubator/src/config` → `config/` (**not scanned**) |
| Command | `java org.jdesktop.lg3d.apps.ls3d.Ls3D` |
| Runtime notes | Self-contained `ls`-style listing; overlaps `fm3d`/`lgscope` |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A minimal native-3D `ls` (directory listing) demo. Dormant; overlaps
  the other 3D file browsers. Promotion needs a descriptor move to
  `lg3d-apps/src/config`.
- **Engineer / Developer** — Obey the core UI/UX rulebook (texture pixels before
  attach, `Component3D` wrapping, translucency sorting, EDT hops); directory I/O off
  the render thread. Dev mode routes no keyboard focus — click-driven only. Jogamp only.
- **QA** — Verify the listing renders with the in-JVM probe + internal screencapture; a
  black host capture under Wayland is not a defect. Not start-menu reachable today.
- **Business Analyst** — Niche/historical demo. No committed product value.
- **Functional Analyst** — Spec as a demonstration (list a directory in 3D). Record the
  descriptor-not-scanned fact and overlap with `fm3d`/`lgscope`.
- **Project Manager** — Commit scope `lg3d-incubator`. Dormant — low priority.
- **UI/UX (3D & 2D)** — **3D only**: file panels as scene-graph nodes. Follow the
  glassy vocabulary and depth ordering from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
