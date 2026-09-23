# SmallToolKit (Sample Demo)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Sample / demo** (teaching code; compiles, not start-menu registered) |
| Entry point | `smalltoolkit.sample.SmallToolKit` (`public static void main`) |
| Surface | **pure-3D** widget showcase: a `Frame3D` holding a spinning `ColorCube` (`NaturalMotionAnimation`) plus 3D buttons (`CylinderButtonComponent3D`, `BoxButtonComponent3D`) wired with `ActionBoolean` pressed-actions |
| Start-menu name / group | SmallToolKit Sample Demo / **Demos** — descriptor `smalltoolkit.lgcfg` is in `lg3d-incubator/src/config` → `config/` (**not scanned**) |
| Command | `java org.jdesktop.lg3d.apps.smalltoolkit.sample.SmallToolKit` |
| Runtime notes | Loads button textures from package `resources/*.png`; bundles `smalltoolkit.jar` |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A canonical *teaching* sample for the small 3D-widget toolkit: how to
  build a `Frame3D`, add a `Component3D` with an animation, and wire textured 3D buttons
  to actions. Treat it as documentation-by-example, not product surface. Dormant;
  descriptor is unscanned.
- **Engineer / Developer** — Mirror the core UI/UX rulebook: upload texture pixels before
  attach, wrap raw nodes in `Component3D`, use event-adapter + `Action` wiring, keep
  animations off the render thread's critical path. Dev mode routes no keyboard focus —
  click-driven only. Jogamp packages only. Keep this sample minimal and copy-friendly.
- **QA** — Verify the cube spins and buttons respond via the in-JVM probe + internal
  screencapture; a black host capture under Wayland is not a defect. Not start-menu
  reachable today.
- **Business Analyst** — Developer-education artifact. No end-user product value; its
  value is onboarding engineers to the widget toolkit.
- **Functional Analyst** — Spec as an example (render a cube, click buttons to change
  rotation). Record the descriptor-not-scanned fact.
- **Project Manager** — Commit scope `lg3d-incubator`. Sample — low priority; keep in sync
  with the core widget APIs it demonstrates.
- **UI/UX (3D & 2D)** — **3D only**: textured 3D buttons and an animated cube. Follow the
  glassy vocabulary, `Cursor3D` on interactive components, and depth ordering from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
