# Zoetrope (3D Image Viewer)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Dormant prototype** (2006-era; compiles, not start-menu registered) |
| Entry point | `zoetrope.Zoetrope` (`public static void main`); `ImageLoaderThread` loads pictures off-thread |
| Surface | **pure-3D** carousel image viewer: `ThumbnailWheel`/`WheelLayout` arrange `ImageComponent` tiles, `event/SelectionEvent`+`SelectionListener` and `LayoutEvent` drive interaction, `Direction`/`ImageInfo` model the wheel |
| Start-menu name / group | Zoetrope - Image Viewer / **Media** — descriptor `zoetrope.lgcfg` is in `lg3d-incubator/src/config` → `config/` (**not scanned**) |
| Command | `java org.jdesktop.lg3d.apps.zoetrope.Zoetrope` |
| Runtime notes | Self-contained; loads image files into textures |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A native-3D carousel image viewer (a rotating wheel of thumbnails).
  Dormant; descriptor lives in the incubator's unscanned `config/`. Promotion needs the
  descriptor moved to `lg3d-apps/src/config`. Keep the wheel/selection event contract
  (`SelectionEvent`, `LayoutEvent`) stable — that is its interaction surface.
- **Engineer / Developer** — Load and decode images on `ImageLoaderThread`, never on the
  render thread; upload texture pixels **before** attach and use power-of-two textures.
  Wrap tiles in `Component3D`, sort translucency, hop to the EDT for any Swing. Dev mode
  routes no keyboard focus — click-driven only. Jogamp packages only.
- **QA** — Verify the wheel renders and spins/selects via the in-JVM probe + internal
  screencapture; a black host capture under Wayland is not a defect. Not start-menu
  reachable today.
- **Business Analyst** — Photo-browser demonstrator. Niche/historical; overlaps the shipped
  `fm3d`/image tools.
- **Functional Analyst** — Spec as a demonstration (load images, rotate the wheel, select a
  thumbnail). Record the descriptor-not-scanned fact.
- **Project Manager** — Commit scope `lg3d-incubator`. Dormant — low priority.
- **UI/UX (3D & 2D)** — **3D only**: image tiles arranged on a rotating wheel. Follow the
  glassy vocabulary, `Cursor3D` on interactive tiles, and depth ordering from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
