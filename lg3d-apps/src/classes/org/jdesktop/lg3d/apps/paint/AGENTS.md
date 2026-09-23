# Paint Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (full raster paint program) |
| Entry point | `PaintApp.main` → builds `PaintFrame` on the EDT |
| Surface | **swingapp 2D** — an ordinary `JFrame` picked up by `SwingNodeWindowCapture` (not a hand-built `SwingNode`) |
| Start-menu name / group | Paint / **Utilities** |
| Command | `swingapp org.jdesktop.lg3d.apps.paint.PaintApp` |
| Descriptor | `src/config/paint.lgcfg` → `config/demo` |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **PaintApp** — entry point; builds and shows `PaintFrame` on the EDT.
- **PaintFrame** — the `JFrame` shell: menu, toolbox, status bar, canvas scroll.
- **PaintDocument / PaintLayer / PaintState** — the raster document model, layers
  and undo/redo state.
- **PaintCanvas / PaintContext** — the drawing surface and per-stroke context.
- **Tool hierarchy** — `Tool`/`AbstractTool` plus brush, pencil, spray, line,
  shape (rect/ellipse/polygon), freehand/freeform, fill, eyedropper, eraser,
  select-rect/lasso, text tools.
- **Dialogs** — `NewImageDialog`, `ResizeDialog`, `RotateDialog`, `TextDialog`,
  `FilterDialog`; **ImageOps** for pixel filters; **PaintIO** for load/save;
  **PaintEdits** for undoable edits; **PaintIcons** for tool glyphs.

## Roles

- **Architect** — The largest 2D app in the module and the reference for the
  `swingapp` launch path: `PaintApp` produces a plain `JFrame`, and core's
  `SwingNodeWindowCapture` hook (installed explicitly at display-server start-up)
  brings it into the desktop. Keep the document/tool/dialog separation intact.
- **Engineer / Developer** — This is conventional Swing (real layout manager, real
  modal dialogs are fine here) — the offscreen no-layout SwingNode constraints do
  **not** apply because capture wraps a live `JFrame`. Keep pixel work off the EDT
  where it is heavy; publish back on the EDT. Route every mutation through
  `PaintEdits` so undo/redo stays consistent. Jogamp packages only where 3D is used.
- **QA** — Unit-test the headless pieces: `ImageOps` filters, `PaintDocument`/
  `PaintLayer` model, `PaintEdits` undo/redo, `PaintIO` round-trips. Verify the
  live frame with the in-JVM probe (`./run-lg3d.sh -s org.jdesktop.lg3d.apps.paint.PaintApp`)
  + internal screencapture; a black host screenshot under Wayland is **not** a
  defect. `SwingAppLauncher` logs only on failure — silence means the frame came up.
- **Business Analyst** — A daily-driver raster drawing utility (draw/paint images).
  Value = a usable, familiar paint program integrated into the desktop.
- **Functional Analyst** — Spec user-visible function per tool (stroke, fill,
  select, text, transforms), the layer/undo model, and file load/save. Keep the
  `swingapp` contract explicit (ordinary `JFrame`, captured by core).
- **Project Manager** — Commit scope `lg3d-apps`. Done = build +
  `:lg3d-core:runtimeResources` (icon) + `./run-lg3d.sh` + capture/log evidence.
  Branch → PR against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — Predominantly **2D**: a normal Swing frame with toolbox,
  canvas, dialogs and status bar; the desktop supplies the window decoration and
  integration via capture. Follow Swing LAF/EDT conventions; keep tool glyphs and
  cursor feedback consistent.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Every
PR states the surface (`swingapp` 2D), any descriptor/icon change, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump. Stage only intended paths (never `git add -A`; `lg3d-core/lgscreen-*.png`
are runtime artifacts).
