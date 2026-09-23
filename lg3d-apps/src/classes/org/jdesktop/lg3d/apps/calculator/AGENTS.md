# Calculator Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (also a reference SwingNode host) |
| Entry point | `Calculator.main` → `TitledSwingWindow.show(...)` |
| Surface | **SwingNode-in-Frame3D** (Swing panel on a `SwingNode` quad under a glassy title bar) |
| Start-menu name / group | Calculator / **Utilities** |
| Command | `java org.jdesktop.lg3d.apps.calculator.Calculator` |
| Descriptor | `src/config/calculator.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **Calculator** — entry point; installs the hosted Metal LAF and shows the window.
- **CalculatorPanel** — Swing UI (null layout, explicit bounds), keypad + history.
- **CalculatorEngine** — headless expression engine (unit-testable, no Swing/AWT).

## Roles

- **Architect** — Reference SwingNode app: consume `TitledSwingWindow`/`SwingNode`
  from `lg3d-core`, never re-implement window plumbing. Keep the engine free of
  UI so it stays headless-testable.
- **Engineer / Developer** — Follow every rule in the core UI/UX rulebook:
  SwingNode paints offscreen with no layout pass (null layout + explicit bounds),
  no modal dialogs (use in-panel overlays), hop to the EDT from lg3d listeners,
  `dispose()` the node when discarded. Use Metal LAF (`installHostedLookAndFeel`)
  — Synth widgets NPE under SwingNode capture. Jogamp packages only.
- **QA** — Unit-test `CalculatorEngine` headless (`src/test/java`, JUnit 5).
  Verify the SwingNode view with the in-JVM probe + internal screencapture
  (`lg3d-core/lgscreen-*.png`); external capture tools return black under
  GNOME/Wayland. Read the desktop log for `EventProcessor` warnings first.
- **Business Analyst** — A daily-driver utility *and* the canonical template other
  SwingNode apps copy. Value = a working calculator + a proven hosting pattern.
- **Functional Analyst** — Spec the panel as user-visible function (keypad,
  expression evaluation, history) plus the contract with core (SwingNode surface,
  descriptor fields). Keep the engine/panel split explicit and stable.
- **Project Manager** — Commit scope `lg3d-apps`. Done = build + `./run-lg3d.sh`
  + screencapture/log evidence in the PR. Branch → PR against `main`; never commit
  to `main`.
- **UI/UX (3D & 2D)** — 3D: the glassy `TitledSwingWindow` frame (title strip,
  gesture handle, transparency ordering). 2D: the Swing keypad/history panel.
  Verify overlay ordering with a capture over a maximized window.

## Communication & coherence

Single source of truth: this file (app) → module `AGENTS.md` → core UI/UX rulebook
→ root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode), any descriptor change, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump. Stage only intended paths (never `git add -A`).
