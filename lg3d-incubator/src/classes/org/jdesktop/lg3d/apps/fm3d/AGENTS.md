# fm3D File Manager

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Dormant prototype** (2006-era; compiles, not start-menu registered) |
| Entry point | `fm3d.Fm3DMain` |
| Surface | **pure-3D** file browser (`ControlBar` + `Icon`/`IconAppearance` on `Component3D`/`SimpleAppearance`) |
| Start-menu name / group | fm3D File Manager / **Utilities** — descriptor `fm3d.lgcfg` is in `lg3d-incubator/src/config` → `config/` (**not scanned**) |
| Command | `java org.jdesktop.lg3d.apps.fm3d.Fm3DMain` |
| Runtime notes | Overlaps the production Swing **File Manager** in `lg3d-demo-apps`; this is the older native-3D take |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A native-3D file browser predating the production SwingNode File
  Manager. Dormant; kept for reference. Do not confuse it with
  `lg3d-demo-apps`' `filemanager` (the shipped one). Promotion would need a
  descriptor move to `lg3d-demo-apps/src/config`.
- **Engineer / Developer** — Obey the core UI/UX rulebook (texture pixels before
  attach, `Component3D` wrapping, translucency sorting, EDT hops); file I/O off the
  render thread. Dev mode routes no keyboard focus — click-driven only. Jogamp only.
- **QA** — Verify with the in-JVM probe + internal screencapture; a black host
  capture under Wayland is not a defect. Note it is **not** start-menu reachable.
- **Business Analyst** — Niche/historical: superseded by the production File Manager.
  No committed product value.
- **Functional Analyst** — Spec as a demonstration (browse a directory as 3D icons).
  Record the descriptor-not-scanned fact and the overlap with the shipped File Manager.
- **Project Manager** — Commit scope `lg3d-incubator`. Dormant — low priority.
- **UI/UX (3D & 2D)** — **3D only**: file icons + control bar as scene-graph nodes.
  Follow the glassy vocabulary and depth ordering from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
