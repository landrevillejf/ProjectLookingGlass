# Tutorial Applications

> Role-aware per-app guide. Module: [`lg3d-demo-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Tutorial / sample** code (teaching material, not a shipped utility) |
| Entry points | `Tutorial1.main`, `Tutorial2.main`, `Tutorial3.main` (+ `SwingNodeTutorial`, `TestPanel`, `GlassyTutorial3TaskbarItem`) |
| Surface | **pure-3D `Frame3D` + `Component3D`** (Tutorials 1-3); `SwingNodeTutorial` shows the SwingNode path |
| Start-menu name / group | Tutorial 1 / 2 / 3 / **Tests** |
| Commands | `java org.jdesktop.lg3d.apps.tutorial.Tutorial1` (…2, …3) |
| Descriptors | `src/config/tutorial1.lgcfg`, `tutorial2.lgcfg`, `tutorial3.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-demo-apps:build` |

## Key components

- **Tutorial1/2/3** — progressive pure-3D lessons: build a `Frame3D`, add
  `Component3D` children, geometry, appearance and interaction.
- **SwingNodeTutorial** — the Swing-in-3D lesson (host a Swing panel on a `SwingNode`).
- **TestPanel** (+ `TestPanel.form`) — the Swing panel used by the SwingNode lesson.
- **GlassyTutorial3TaskbarItem** — a taskbar-item plugin variant used by Tutorial 3.
- **resources/** — per-tutorial icons (`resources/images/icon/tutorial{1,2,3}.png`)
  and textures (e.g. `images/earth.jpg`, reused by `tapps`).

## Roles

- **Architect** — These are **teaching examples**, deliberately minimal and heavily
  commented. They must model the *correct* core patterns (raw `Node`s wrapped in
  `Component3D`, the two window paths, event adapters + actions) because developers
  copy them. Keep them simple; do not grow production features here.
- **Engineer / Developer** — Follow the core UI/UX rulebook exactly (upload texture
  pixels before attaching; wrap `Node`s in `Component3D`; sort translucency; hop to
  the EDT from lg3d listeners). Jogamp packages only. The comments are part of the
  deliverable — keep them accurate when the API changes.
- **QA** — Primarily verified by *running* each tutorial in the desktop and reading
  the comments against behaviour: in-JVM probe (`./run-lg3d.sh -s org.jdesktop.lg3d.apps.tutorial.Tutorial1`)
  + internal screencapture (`lg3d-core/lgscreen-*.png`). A black host capture under
  Wayland is not a defect. There is little headless logic to unit-test here.
- **Business Analyst** — Value is **developer onboarding**, not end-user features.
  These occupy the **Tests** menu group on purpose; they are not daily-driver tools.
- **Functional Analyst** — Spec each tutorial as a *learning outcome* (what concept
  it demonstrates) rather than a product feature. Keep the progression 1 → 2 → 3 →
  SwingNode coherent and non-overlapping.
- **Project Manager** — Commit scope `lg3d-demo-apps`. Because these are reference
  material, an API change in `lg3d-core` that breaks a tutorial is a real regression
  to track. Done = build + `./run-lg3d.sh` + capture/log evidence.
- **UI/UX (3D & 2D)** — 3D: the canonical pure-`Frame3D` glassy-vocabulary example
  (`GlassyPanel`, `GlassyText2D`, `SimpleAppearance`, `Component3D`, `Cursor3D`).
  2D: `SwingNodeTutorial`/`TestPanel` demonstrate the SwingNode host. These are the
  copy-from patterns for both surfaces.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Every
PR states which tutorial changed, the surface, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-demo-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump. Stage only intended paths (never `git add -A`).
