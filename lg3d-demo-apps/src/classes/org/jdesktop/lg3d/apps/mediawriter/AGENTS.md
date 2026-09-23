# Media Writer Application

> Role-aware per-app guide. Module: [`lg3d-demo-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (destructive media writes — safety-critical) |
| Entry point | `MediaWriter.main` → `TitledSwingWindow.show(...)` |
| Surface | **SwingNode-in-Frame3D** (Swing panel on a `SwingNode` quad under a glassy title bar) |
| Start-menu name / group | Media Writer / **Utilities** |
| Command | `java org.jdesktop.lg3d.apps.mediawriter.MediaWriter` |
| Descriptor | `src/config/mediawriter.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-demo-apps:build` |

## Key components

- **MediaWriter** — entry point; installs the hosted Metal LAF and shows the window.
- **MediaWriterPanel** — Swing UI (null layout, explicit bounds): device picker,
  image/source selection, progress, write/clone actions.
- **MediaWriterEngine** — headless burn/imaging logic (device & image handling,
  unit-testable apart from the actual native burn).

## Roles

- **Architect** — Reference SwingNode app alongside Calculator: consume
  `TitledSwingWindow`/`SwingNode`, keep device/imaging logic in the engine so it
  stays headless-testable and swappable.
- **Engineer / Developer** — Obey the core UI/UX rulebook: offscreen SwingNode
  paint (null layout + explicit bounds), no modal dialogs (in-panel overlays for
  device/confirm), EDT hops from lg3d listeners, `dispose()` on discard, Metal LAF
  via `installHostedLookAndFeel`. Native burn/`dd` work must be guarded and never
  block the EDT — run it off-thread and publish progress back on the EDT.
- **QA** — Unit-test `MediaWriterEngine` headless (device enumeration, image
  parsing, size math) without touching real media. Verify the panel with the
  in-JVM probe + internal screencapture; destructive write paths must be covered
  by dry-run/guard tests, never by burning real media in CI.
- **Business Analyst** — A daily-driver utility (burn CD/DVD, write/clone USB
  keys) and a second canonical SwingNode template. Value emphasises safety:
  writing to the wrong device is destructive.
- **Functional Analyst** — Spec user-visible function (pick device → pick image →
  confirm → progress) plus the destructive-action guardrails and the core contract
  (SwingNode surface, descriptor fields). Confirmation before any write is a hard
  requirement.
- **Project Manager** — Commit scope `lg3d-demo-apps`. Because writes are
  destructive, PRs must call out guard/confirmation coverage. Done = build +
  `./run-lg3d.sh` + screencapture/log evidence. Branch → PR against `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame + transparency
  ordering. 2D: the Swing device/progress panel; progress and confirmation must be
  legible in the offscreen capture. Verify ordering over a maximized window.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Every
PR states the surface (SwingNode), destructive-path guards, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-demo-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump. Stage only intended paths (never `git add -A`).
