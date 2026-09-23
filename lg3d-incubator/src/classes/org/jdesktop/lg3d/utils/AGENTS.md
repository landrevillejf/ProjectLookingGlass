# org.jdesktop.lg3d.utils.smalltoolkit — 3D Button Widget Library

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Shared widget library** (compiles; no app, no descriptor, no UI of its own) |
| Packages | `org.jdesktop.lg3d.utils.smalltoolkit.buttons` — `ButtonComponent3D extends Component3D` (base, with `PressedAction`/`EnteredAction` implementing `ActionBoolean`) and subclasses `BoxButtonComponent3D`, `ConeButtonComponent3D`, `CylinderButtonComponent3D`, `ImagePanelButtonComponent3D`, `MinimizeButtonComponent3D`, `ExitButtonComponent3D` |
| Surface | **None directly** — provides textured 3D button components consumed by apps |
| Used by | The `smalltoolkit` sample app (`org.jdesktop.lg3d.apps.smalltoolkit`, see its `AGENTS.md`) |
| Registration | N/A — a library, not a start-menu app |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A small reusable library of textured 3D button widgets built on core's
  `Component3D` + `ActionBoolean` event model. It lives under `org.jdesktop.lg3d.utils.*`
  (a utility namespace), not `apps.*`, because it is meant to be consumed, not launched. Keep
  the `ButtonComponent3D` base contract (pressed/entered actions, texture handling) stable —
  that is the reuse surface for `smalltoolkit` and any future consumer.
- **Engineer / Developer** — Follow the core UI/UX rulebook: upload texture pixels before
  attach, power-of-two textures, wrap geometry in `Component3D`, wire interactions through
  event adapters + `ActionBoolean`. These are building blocks — do not add app-specific
  logic here. Dev mode routes no keyboard focus, so buttons must be click/hover-driven.
  Jogamp packages only.
- **QA** — Unit-test the action wiring and state changes headless where possible; verify
  visual rendering through a consumer (e.g. `smalltoolkit`) with the in-JVM probe + internal
  screencapture. A black host capture under Wayland is not a defect. Nothing to launch here.
- **Business Analyst** — Enabling library, no direct product value; its value is the apps
  that reuse it.
- **Functional Analyst** — Spec the button contract (pressed/entered actions, appearance,
  texture source) rather than a user-facing feature.
- **Project Manager** — Commit scope `lg3d-incubator`. Low priority; changes here ripple to
  consumers, so review for backward compatibility.
- **UI/UX (3D & 2D)** — **3D only**: textured button geometry with hover/press feedback.
  Follow the glassy vocabulary, `Cursor3D` on interactive components, and depth ordering from
  core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. See also `../apps/smalltoolkit/AGENTS.md` (its consumer). On conflict the
higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
