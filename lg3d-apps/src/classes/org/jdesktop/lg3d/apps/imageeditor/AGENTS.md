# Image Editor

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** GIMP-style raster editor (layers, tools, filters, undo) |
| Entry point | `ImageEditor.main` → `TitledSwingWindow.show(...)`; `ImageEditorClient.main` runs the same panel standalone |
| Surface | **SwingNode-in-Frame3D** (hosts `ImageEditorPanel` on a `SwingNode` quad); the same panel is reused in the 2D desktop |
| Start-menu name / group | Image Editor / **Media** |
| Command | `java org.jdesktop.lg3d.apps.imageeditor.ImageEditor` |
| Descriptor | `src/config/imageeditor.lgcfg` → `config/demo` |
| Format | **Fully native Java 2D** — no codec, no third-party library, no Java 3D. Load/save is `ImageIO` PNG; drawing and filters use `Graphics2D`, `RescaleOp` and `ConvolveOp` |
| Persistence | None by default (the working image is opened/saved through `ImageIO` on demand); the document model lives in memory |
| Reliability | Every destructive edit pushes a bounded `UndoStack` snapshot first, so a filter or stroke can always be undone; a rejected/unknown filter pops its own stray snapshot rather than corrupting the undo history |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **ImageEditor** — thin 3D entry point; installs the hosted look and feel and
  shows `ImageEditorPanel` in a `TitledSwingWindow`, wiring `setOnClose` to
  `frame.changeEnabled(false)`.
- **ImageEditorClient** — standalone `JFrame` (`DISPOSE_ON_CLOSE`) for running the
  editor outside the desktop; never calls `System.exit`.
- **ImageEditorPanel** — the one Swing UI (no-arg constructor, no Java 3D): a NORTH
  tool/colour/size toolbar, a CENTER scrollable canvas with a mouse listener, an
  EAST layers list (Add / Up / Down / Delete) and a SOUTH filter + undo/redo bar.
  `JFileChooser`s and `JColorChooser` are created lazily so the panel builds
  headless; `createGraphics()` results are typed `Graphics2D` so composites work.
- **EditorDocument** — the AWT-free model: an ordered layer stack (index 0 =
  bottom) with an active cursor, add/remove/reorder and `composite()` (paints the
  background then each visible layer per its opacity). Deep-copyable for undo.
- **ImageLayer** — one layer: a name, a `BufferedImage`, visibility and a clamped
  0..1 opacity; `copy()` deep-clones.
- **ToolEngine** — pure drawing primitives (line, rect, ellipse, fill, text,
  erase) over a `BufferedImage`, driven by a `Tool` enum; every op is null-safe.
- **FilterEngine** — pure static filters that return a **new** image and never
  mutate the source (brightness, contrast, grayscale, invert, sepia, threshold,
  blur, sharpen, edges).
- **UndoStack** — a generic bounded undo/redo stack (default limit 40) shared by
  the document snapshots.

## Roles

- **Architect** — Everything lives in this package: the model (`EditorDocument` /
  `ImageLayer`), the pure engines (`ToolEngine` / `FilterEngine`), `UndoStack`,
  `ImageEditorPanel` (the one Swing UI) and the two thin entry points. The same
  panel drives both desktops: 3D via `TitledSwingWindow`, 2D via
  `Desktop2DAppRegistry.PANEL_APPS` keyed on `ImageEditor`. **No new dependency is
  added** — it is all `java.awt` / `javax.imageio`. Keep pixel logic in the engines
  (testable without a display), never in the panel.
- **Engineer / Developer** — Filters must return a new image; never mutate the
  source in place (the undo snapshot is a shared deep copy). Type every
  `createGraphics()` result as `Graphics2D` before calling `setComposite`. Push an
  undo snapshot **before** a destructive edit and pop it again if the edit is a
  no-op (an unknown filter), so the stack never drifts. Guard every
  `JFileChooser`/`JColorChooser` path so it is only reached from a user action,
  never the constructor. Never call `System.exit`. Obey the core UI/UX rulebook.
- **QA** — `UndoStackTest`, `EditorDocumentTest`, `FilterEngineTest`,
  `ToolEngineTest`, `ImageLayerTest` and `ImageEditorPanelTest` run headless (38
  tests): the engine suites assert every filter produces a distinct, correctly
  sized image and that draw ops are null-safe; the document suite asserts layer
  ordering, reorder and composite; the undo suite asserts the bounded
  push/undo/redo invariants; the panel suite asserts construction, tool switching,
  a filter pushing a snapshot, an unknown filter leaving none, and a PNG
  save/load round-trip. For the 3D host use the in-JVM probe + internal
  screencapture (`lg3d-core/lgscreen-*.png`); a black capture under Wayland is not
  a defect.
- **Business Analyst** — A daily-driver raster editor a step above the bundled
  Paint: layered composition, a tool palette, one-click filters and reliable undo,
  in both desktops. Value = real image editing with no external app and no plugin.
- **Functional Analyst** — Spec this app as the *document contract*: a stack of
  layers composited in order, mutated by pure tools and filters, with every
  destructive step undoable — with pixel work hidden behind the engines. A filter
  is specified as "produce a new image", never "edit in place".
- **Project Manager** — Commit scope `lg3d-apps`; the registration also touches
  `lg3d-core` (`Desktop2DAppRegistry` panel mapping + test) and the icon in
  `lg3d-core` resources, plus the `lg3d-art` icon tool — call that out. Done =
  build + headless tests + `./run-lg3d.sh` capture/log evidence. Branch → PR
  against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame; the panel is the
  SwingNode content. 2D: the identical `ImageEditorPanel` in an MDI internal
  frame. Conventional editor chrome (toolbar + canvas + layers dock + filter bar),
  never a click-cycling 3D idiom; keep both surfaces pixel-identical. Every action
  must report through the status line so the current tool and last filter are
  always legible.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode host + 2D MDI), the pure-engine split, the
undo guarantee and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; feature branch off `main`; PR against `main` via `gh`. Stage
only intended paths (never `git add -A`); exclude runtime screenshots and stray
downloads.
