# K-Web 3D UI Demo (kwebdemo1)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Dormant prototype** (2006-era demo; compiles, not start-menu registered) |
| Entry point | `kwebdemo1.Application` |
| Surface | **pure-3D** — many hand-built `Component3D` panels (`AgesInHistoryC3D`, `BigGlassyPanelC3D`, `BioDialogC3D`, `Century17thLegendC3D`, …) |
| Start-menu name / group | K-Web 3D UI Demo / **Media** — descriptor `kwebdemo1.lgcfg` is in `lg3d-incubator/src/config` → `config/` (**not scanned**) |
| Command | `java org.jdesktop.lg3d.apps.kwebdemo1.Application` |
| Runtime notes | Self-contained; a large scripted 3D "web" walkthrough |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A big hand-authored native-3D UI demo (one `*C3D` `Component3D` per
  panel/dialog). Useful as a catalogue of glassy 3D panel idioms, but it is bespoke —
  do not treat its structure as a reusable framework. Dormant.
- **Engineer / Developer** — Obey the core UI/UX rulebook (texture pixels before
  attach, `Component3D` wrapping, translucency sorting, EDT hops). Each panel is a
  separate `Component3D`; keep them independent. Dev mode routes no keyboard focus —
  click-driven only. Jogamp packages only.
- **QA** — Verify the walkthrough renders and panels transition with the in-JVM probe +
  internal screencapture; a black host capture under Wayland is not a defect. Not
  start-menu reachable today.
- **Business Analyst** — Demonstration value only (a scripted 3D UI tour). No product
  surface.
- **Functional Analyst** — Spec as a demonstration (navigate a series of 3D info
  panels). Record the descriptor-not-scanned fact.
- **Project Manager** — Commit scope `lg3d-incubator`. Dormant — low priority.
- **UI/UX (3D & 2D)** — **3D only**: glassy panels, legends and dialogs as
  scene-graph nodes. A good visual reference for the glassy vocabulary.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
