# PingPong

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Dormant prototype** (2006-era; compiles, not start-menu registered) |
| Entry point | `pingpong.PingPong` (`extends Frame3D`, `public static void main`) |
| Surface | **pure-3D** game; `PingPongAction extends TimerTask` drives the loop, `Button extends Component3D` + `ButtonAppearance extends SimpleAppearance` for controls |
| Start-menu name / group | PingPong / **Games** — descriptor `pingpong.lgcfg` is in `lg3d-incubator/src/config` → `config/` (**not scanned**) |
| Command | `java org.jdesktop.lg3d.apps.pingpong.PingPong` |
| Runtime notes | Self-contained; animation runs on a `Timer`, not the render thread |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A minimal native-3D ping-pong game. Dormant; descriptor lives in the
  incubator's unscanned `config/`. Note the shipped `games/` suite (chess, solitaire,
  sudoku, tictactoe) is the maintained game set — this is an older standalone demo.
  Promotion needs the descriptor moved to `lg3d-demo-apps/src/config`.
- **Engineer / Developer** — Drive the game loop from `PingPongAction` (`TimerTask`) and
  only mutate the scene graph under the core threading rules; wrap nodes in
  `Component3D`, sort translucency, hop to the EDT for any Swing. Upload texture pixels
  before attach. Dev mode routes no keyboard focus — click-driven only. Jogamp only.
- **QA** — Verify the game renders and animates with the in-JVM probe + internal
  screencapture; a black host capture under Wayland is not a defect. Not start-menu
  reachable today.
- **Business Analyst** — Casual game demonstrator. Niche/historical; overlaps the
  maintained `games/` suite.
- **Functional Analyst** — Spec as a demonstration (paddle/ball interaction, score).
  Record the descriptor-not-scanned fact and the overlap with `games/`.
- **Project Manager** — Commit scope `lg3d-incubator`. Dormant — low priority.
- **UI/UX (3D & 2D)** — **3D only**: paddles/ball as `Component3D` nodes with a
  `SimpleAppearance`-based button. Follow the glassy vocabulary and depth ordering.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
