# Background Manager (bgmanager)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** desktop infrastructure (the 3D background manager) — not a start-menu app |
| Entry point | `BgManager` (loaded by the desktop as the background manager; also the anchor class discovery uses to resolve `/config/incubator`) |
| Surface | **pure-3D scene** — backgrounds, containers and layouts rendered behind the windows |
| Start-menu name / group | *None* — a desktop component, not a launched app |
| Command | *n/a* |
| Descriptor / config | `BgConfig.xml` + per-background dirs under `src/classes/…/bgmanager/Backgrounds`, assembled into the runtime `resources/Backgrounds/` tree by `:lg3d-core:runtimeResources` |
| Build | `./gradlew :lg3d-incubator:build` (+ `:lg3d-core:runtimeResources` for assets) |

## Key components

- **BgManager** — the manager entry point; **BgFrame** / **BgLgComponent** — the
  background frame/component; **BgManagerIcon** — its control icon.
- **BgSorting** / **BgTypes** — background ordering and type metadata.
- **configReaders/** — parse `BgConfig.xml`; **layouts/** — background layout strategies.
- **Containers** — `EllipseContainer`, `TilleContainer`, `MouseTransContainer`,
  `SharedMenuContainer`; **Backgrounds/** + **res/** — the bundled background assets.

## Roles

- **Architect** — This is **desktop infrastructure**, not an app: it renders and
  cycles the 3D backgrounds behind the window layer. It is also architecturally
  load-bearing for discovery — `DefaultConfigControl` resolves `/config/incubator`
  against `org.jdesktop.lg3d.apps.bgmanager.BgManager`, so **renaming/moving this
  class breaks incubator config resolution**. Background assets are assembled by
  `:lg3d-core:runtimeResources`, not compiled in.
- **Engineer / Developer** — Obey the core UI/UX rulebook (texture pixels uploaded
  before attach, `Component3D` wrapping, translucency sorting, EDT hops). Backgrounds
  are configured via `BgConfig.xml` + `configReaders`; add a background by dropping a
  directory under `Backgrounds/` and registering it in the config, then re-run
  `:lg3d-core:runtimeResources`. Keep container/layout classes reusable. Jogamp only.
- **QA** — Verify a configured background actually renders behind windows with the
  internal screencapture (`lg3d-core/lgscreen-*.png`); a black host capture under
  Wayland is not a defect. Confirm `BgConfig.xml` parses (config-readers) and that the
  assembled `resources/Backgrounds/` tree contains the new assets. Because this is
  startup infrastructure, a regression here can prevent the desktop from coming up —
  smoke-test `./run-lg3d.sh -b` (3D background) and check the log for a clean boot.
- **Business Analyst** — Core desktop experience (the animated 3D background and its
  cycling/selection). It ships to end users even though it has no start-menu entry;
  treat it with production rigor.
- **Functional Analyst** — Spec user-visible function (background selection/cycling,
  the manager icon/control) plus the config contract (`BgConfig.xml` schema, the
  `Backgrounds/` asset layout, runtime-resource assembly). Note the discovery-anchor
  dependency on the `BgManager` class name.
- **Project Manager** — Commit scope `lg3d-incubator`; asset/config changes also touch
  `:lg3d-core:runtimeResources` output — call that out. Done = build +
  `:lg3d-core:runtimeResources` + `./run-lg3d.sh -b` + capture/log evidence.
- **UI/UX (3D & 2D)** — **3D only**: full-scene backgrounds, containers and layouts
  that sit behind the window layer. Depth/overlay ordering matters here more than
  anywhere — verify with a capture over real windows, not numeric Z.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Every PR
states the background/config/asset change, the runtime-resources impact, and the
evidence.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump.
Stage only intended paths (never `git add -A`).
