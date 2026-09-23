# Googler (Google Desktop Search)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Dormant prototype** (2006-era; compiles, effectively non-functional — dead backend) |
| Entry point | `googler.Main` |
| Surface | **pure-3D** results UI; search runs on `SearchThread`, results arrive as `SearchEvent`/`ResultEvent`/`SearchErrorEvent` |
| Start-menu name / group | Googler / **Internet** — descriptor `googler.lgcfg` is in `lg3d-incubator/src/config` → `config/` (**not scanned**) |
| Command | `java org.jdesktop.lg3d.apps.googler.Main` |
| Runtime blocker | Talks to the long-defunct **Google Desktop Search** local API; cannot return results on a modern host |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — An event-driven search client (`SearchThread` → `SearchEvent`).
  Dormant **and** functionally dead: its Google Desktop backend was discontinued. Do
  not invest in porting a dead-backend prototype (same policy as `wilkoaim3d`'s AIM).
- **Engineer / Developer** — If touched at all, keep the search off the render thread
  and marshal results back safely; obey the core UI/UX rulebook for the 3D results
  view. Jogamp packages only. Expect the network/backend calls to fail.
- **QA** — It compiles and registers nothing; runtime search cannot succeed (dead
  API). Classify as **compiles, does not run** — do not file the backend failure as a
  regression. Verify only that it launches without crashing the desktop.
- **Business Analyst** — Historical curiosity (Google Desktop Search on LG3D). No
  product value; the backend no longer exists.
- **Functional Analyst** — Document it as a dead-backend prototype so a future port is
  not attempted without replacing the search provider entirely.
- **Project Manager** — Commit scope `lg3d-incubator`. Dormant/dead — do not schedule.
- **UI/UX (3D & 2D)** — **3D** results view. Follow the glassy vocabulary and depth
  ordering from core if it is ever revived with a live provider.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
