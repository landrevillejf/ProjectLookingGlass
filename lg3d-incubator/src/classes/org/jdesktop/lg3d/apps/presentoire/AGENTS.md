# Presentoire (Presentation Viewer)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Dormant prototype** (2006-era; compiles, not start-menu registered) |
| Entry point | `presentoire.Presentoire` (`public static void main`) |
| Surface | **3D slide viewer** with a Swing `FileChooser` (`JDialog`) to open a document; `PresentoireDocument`/`Slide` model, `elements/` (`ImageElement`, `ListElement`, `TextElement`, `ElementFactory`), `transition/` (`Scale`/`Rotation`/`Transparency`/`Script` transitions + `TransitionFactory`), `Colors` |
| Start-menu name / group | Presentation viewer / **Media** — descriptor `presentoire.lgcfg` is in `lg3d-incubator/src/config` → `config/` (**not scanned**) |
| Command | `java org.jdesktop.lg3d.apps.presentoire.Presentoire` |
| Runtime notes | Uses a real Swing `JDialog` file chooser (design docs in `lg3d-incubator/docs/presentoire/`) |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A 3D presentation viewer: parses a document into slides/elements and
  animates transitions. Dormant; descriptor is unscanned. Its use of a Swing `JDialog`
  `FileChooser` is the notable deviation — modal dialogs escape offscreen `SwingNode`
  capture, so if this is promoted the chooser must become an in-panel overlay (see the
  core SwingNode contract).
- **Engineer / Developer** — Obey the core UI/UX rulebook (upload texture pixels before
  attach for image slides, wrap nodes in `Component3D`, sort translucency, EDT hops for
  the Swing chooser). Run transitions off the render thread; mutate the graph under the
  threading rules. Dev mode routes no keyboard focus — click-driven only. Jogamp only.
- **QA** — Verify a slide deck renders and transitions with the in-JVM probe + internal
  screencapture; a black host capture under Wayland is not a defect. Confirm the file
  chooser behaviour (modal dialog caveat). Not start-menu reachable today.
- **Business Analyst** — Presentation/ slideshow demonstrator. Niche/historical; no
  committed product value.
- **Functional Analyst** — Spec document → slides → elements → transitions, and the file
  open flow. Record the descriptor-not-scanned fact and the modal-chooser caveat.
- **Project Manager** — Commit scope `lg3d-incubator`. Dormant — low priority; promotion
  needs the descriptor moved and the chooser reworked to an in-panel overlay.
- **UI/UX (3D & 2D)** — **3D slides** (images/text/lists) with animated transitions, plus
  a **2D Swing** file-open dialog. Follow the glassy vocabulary, depth ordering, and the
  SwingNode/modal-dialog rules from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
