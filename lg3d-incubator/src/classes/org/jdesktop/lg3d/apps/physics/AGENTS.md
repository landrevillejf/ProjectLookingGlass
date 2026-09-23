# Physics (Rigid-Body Engine & Demos)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Dormant prototype** (2006-era; compiles, not start-menu registered) |
| Entry points | `physics.NewtonCradleDemo` (extends `Frame3D`), `physics.PhysicsTutorial1`, `physics.PhysicsTutorial2` — each has `public static void main` |
| Surface | **pure-3D** rigid-body simulations driven by an in-package physics engine |
| Start-menu name / group | Newton Cradle Demo / Physics Tutorial 1 / Physics Tutorial 2 — **Tests** group; descriptor `physics.lgcfg` is in `lg3d-incubator/src/config` → `config/` (**not scanned**). The commented taskbar entry references a `PhysTest` class that is not the shipped main. |
| Command | `java org.jdesktop.lg3d.apps.physics.NewtonCradleDemo` |
| Engine | `PhysicsThread`, `PhysicsBody`/`PhysicsObject`/`PhysicsParticle`, `RigidBodyState`, `Integrator`/`LeapFrogIntegrator`, `CollisionResolver`/`ImpulseResolver`, `Contact`/`ContactComparator`, `ConvexPolyhedra`, `Gravity`, `Spring`, `Effector`/`InteractiveEffector`, `FixedPoint`, shapes `PhysicsBox`/`PhysicsSphere`/`PhysicsPlane`/`BoxPlane`/`Collideable` |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A self-contained rigid-body physics engine plus three demo scenes.
  Distinct from the ODE path excluded in core (superseded by an in-tree spring-damper);
  this is an independent incubator engine. Dormant; descriptors are unscanned. Keep the
  engine API (body/force/integrator/collision) stable if promoted.
- **Engineer / Developer** — Step the simulation on `PhysicsThread`, never on the
  render thread; copy resulting transforms into the scene graph under the core
  threading rules. Obey the UI/UX rulebook (wrap nodes in `Component3D`, translucency
  sorting, EDT hops). Dev mode routes no keyboard focus — click-driven only. Jogamp only.
- **QA** — Verify each demo animates (cradle swings, tutorials settle) with the in-JVM
  probe + internal screencapture; a black host capture under Wayland is not a defect.
  Not start-menu reachable today; the commented `PhysTest` exec is stale.
- **Business Analyst** — Technology demonstrator for a physics engine. No committed
  product value; niche/historical.
- **Functional Analyst** — Spec the three demos (Newton's cradle, two tutorials) and the
  engine contract (bodies, forces, integration, collision resolution). Record the
  descriptor-not-scanned fact and the stale `PhysTest` reference.
- **Project Manager** — Commit scope `lg3d-incubator`. Dormant — low priority; promotion
  requires moving descriptors to `lg3d-demo-apps/src/config`.
- **UI/UX (3D & 2D)** — **3D only**: physical bodies rendered as scene-graph shapes.
  Follow the glassy vocabulary and depth ordering from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
