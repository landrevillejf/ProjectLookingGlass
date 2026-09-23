# BlackGoat (3D Email Client)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Dormant prototype** (2006-era; compiles, needs a live POP server, not start-menu registered) |
| Entry point | `blackgoat.BlackGoat` (`extends Frame3D`, `public static void main`); `FolderViewer`/`MessageViewer` drive the UI |
| Surface | **pure-3D** email client: `component/folder/` (`ALetterContainer3D`/`FolderContainer3D` extend `Container3D`, `CubeComponent3D` extends `Component3D`), `component/letter/` (`LetterComponent3D`, `HeaderComponent3D`, `PostitComponent3D`, `ReplyForwardComponent3D`, `SmallLetterComponent3D`), `button/Button`+`ButtonAppearance`, `action/` (`PopupAction`, `ComponentAppearanceChangeAction`), `layout/CubeLayout`, `emessage/read|write` (POP), `draw/letter` |
| Start-menu name / group | BlackGoat / **Early Prototypes** (desc "Email Client") — descriptor `blackgoat.lgcfg` is in `lg3d-incubator/src/config` → `config/` (**not scanned**) |
| Command | `java org.jdesktop.lg3d.apps.blackgoat.BlackGoat` |
| Runtime blocker | Needs a reachable **POP mail server** + `utils/UserInfo` credentials (`emessage/read/EMessageReader`, `PopTest`) |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — An experimental native-3D email client: mail folders and letters are
  3D containers/components you navigate spatially. Dormant; descriptor is unscanned and it
  needs a live POP server. Keep the `component`/`draw`/`emessage`/`layout` separation
  (scene nodes vs rendering vs protocol vs layout) intact if promoted.
- **Engineer / Developer** — Do POP/network I/O off the EDT/render thread; render letter
  bodies into textures (upload pixels before attach, power-of-two, wrap in `Component3D`).
  Sort translucency, hop to the EDT for the preference panels, `dispose()` discarded nodes.
  Never hardcode credentials. Dev mode routes no keyboard focus — click-driven only. Jogamp
  packages only.
- **QA** — Classify honestly: **compiles, but cannot fully run** without a POP server and
  credentials. Verify the folder/letter scene constructs via the in-JVM probe + internal
  screencapture; a black host capture under Wayland is not a defect. Do not file "cannot
  fetch mail" as a regression in a network-less CI.
- **Business Analyst** — Visionary 3D-mail demonstrator. Niche/historical; blocked on mail
  infrastructure and an aging POP stack.
- **Functional Analyst** — Spec folder browsing, letter reading/reply/forward, and the
  preference panel, plus the POP/credential contract. Record the descriptor-not-scanned fact.
- **Project Manager** — Commit scope `lg3d-incubator`. Dormant — track the POP-server /
  credential dependency as the gating risk; low priority.
- **UI/UX (3D & 2D)** — **Mostly 3D** (folders/letters as `Container3D`/`Component3D`,
  popup text, cube layout) with **2D Swing** preference panels. Follow the glassy
  vocabulary, `Cursor3D` on interactive components, depth ordering, and the SwingNode rules
  from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
