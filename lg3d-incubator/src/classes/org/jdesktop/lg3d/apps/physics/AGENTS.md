# Physics (Rigid-Body Engine, Newton's Cradle & 2D Desk Toy)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Live** (resurrected from the dormant 2006 prototype and ported to JDK 21; registered in both desktops) |
| Entry points | 3D: `physics.NewtonCradleDemo` (extends `Frame3D`), `physics.PhysicsTutorial1`, `physics.PhysicsTutorial2` — each has `public static void main`. 2D: `physics.NewtonCradlePanel` (plain Swing) |
| Surface | **3D + 2D**: the native-3D demos render the rigid-body scene into a `Frame3D`; the 2D/Swing desktop hosts `NewtonCradlePanel`, which paints the same cradle with Java 2D |
| Start-menu name / group | **Newton's Cradle** / **Education**; descriptor `newtonscradle.lgcfg` is in `lg3d-apps/src/config` → `config/demo` (**scanned**). The legacy `lg3d-incubator/src/config/physics.lgcfg` is unscanned and stale (names a `physics.jar` and a `PhysTest` class that do not exist); it is left in place as history and is not the live descriptor. |
| Command | `java org.jdesktop.lg3d.apps.physics.NewtonCradleDemo` (one shared descriptor serves both desktops via `Desktop2DAppRegistry.PANEL_APPS`) |
| 2D core | `physics.model` — `Vec2`, `Particle`, `Spring`, `NewtonCradleModel`: an AWT-free, Java 3D-free planar transcription of the demo's dynamics (same algorithm, same physical constants) so the 2D desktop never loads the scene graph |
| Engine (3D) | `PhysicsThread`, `PhysicsBody`/`PhysicsObject`/`PhysicsParticle`, `Integrator`/`LeapFrogIntegrator`, `CollisionResolver`/`ImpulseResolver`, `Contact`/`ContactComparator`, `ConvexPolyhedra`, `Gravity`, `Spring`, `Effector`/`InteractiveEffector`, `FixedPoint`, shapes `PhysicsBox`/`PhysicsSphere`/`PhysicsPlane`/`BoxPlane`/`Collideable`. (The one-byte empty `RigidBodyState` stub was deleted in the resurrection.) |
| Icon | generated glass tile `newtonscradle.png` (`lg3d-art/tools/GenerateAppIcons.java`, cradle glyph) in `lg3d-core/src/resources/images/icon/` |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A self-contained rigid-body physics engine plus three demo scenes,
  now fronted by a desktop-independent planar core. Distinct from the ODE path
  excluded in core (superseded by an in-tree spring-damper); this is an independent
  incubator engine. The 3D engine and the 2D `physics.model` core are deliberately
  separate: `PhysicsObject` imports Java 3D / `Component3D` at class level, so the
  2D face must not reuse it. Keep the engine API (body/force/integrator/collision)
  and the `physics.model` read API stable.
- **Engineer / Developer** — Step the 3D simulation on `PhysicsThread`, never on the
  render thread; copy resulting transforms into the scene graph under the core
  threading rules. The 2D panel steps `NewtonCradleModel` on a `javax.swing.Timer`
  started in `addNotify` / stopped in `removeNotify`; drive it via `tick()` in tests,
  never by starting the timer headless. Obey the UI/UX rulebook (wrap nodes in
  `Component3D`, translucency sorting, EDT hops) and the hosted-panel palette rule
  (panel chrome wears only LAF colours; only the rendered simulation content is
  painted). Dev mode routes no keyboard focus — click-driven only. Jogamp only.
- **QA** — The headless suite (`NewtonCradleModelTest`, `NewtonCradlePanelTest`)
  covers the planar core and the panel raster. Verify the 3D demos animate (cradle
  swings, tutorials settle) with the in-JVM probe + internal screencapture; a black
  host capture under Wayland is not a defect. The app is start-menu reachable now
  (Education group); the legacy `PhysTest` exec in the unscanned descriptor is stale.
- **Business Analyst** — Technology demonstrator for a physics engine, now a usable
  desk toy on both desktops. Niche/historical but live.
- **Functional Analyst** — Spec the demos (Newton's cradle, two tutorials), the engine
  contract (bodies, forces, integration, collision resolution) and the planar core's
  invariants (momentum transfer through a resting chain, energy decay, balls within
  the static world bounds). Record that the live descriptor is the `lg3d-apps` one.
- **Project Manager** — Commit scope `lg3d-incubator` (plus the `lg3d-apps`
  descriptor / icon and the `lg3d-core` registry entry that the integration touches).
- **UI/UX (3D & 2D)** — **3D**: physical bodies as scene-graph shapes, glassy
  vocabulary and depth ordering from core. **2D**: `NewtonCradlePanel`'s canvas is
  rendered simulation content (steel on slate); its toolbar / buttons / status line
  wear only LAF colours.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
