# Image Studio Application

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · native-3D app guide: [`docs/lg3d-native-apps.md`](../../../../../../../../docs/lg3d-native-apps.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production-grade** native-3D app (supported showcase) |
| Entry point | `ImageStudioApp.main` → builds `ImageStudioFrame3D`, then `changeEnabled(true)` / `changeVisible(true)` |
| Surface | **3D:** pure-`Frame3D` — the entire UI is scene-graph nodes driven by the live-texture pattern. **2D desktop:** plain Swing `ImageStudioPanel` hosted as an MDI frame (`Desktop2DAppRegistry.PANEL_APPS`, keyed on `ImageStudioApp`) |
| Start-menu name / group | Image Studio / **Utilities** |
| Command | `java org.jdesktop.lg3d.apps.imagestudio.ImageStudioApp` |
| Descriptor | **`lg3d-apps/src/config/imagestudio.lgcfg`** → `config/demo` (incubator `src/config` is not scanned) |
| Runtime deps | `ext/jai_core.jar` + `ext/jai_codec.jar` on the `:lg3d-core:run` classpath **and** `--add-exports java.desktop/sun.awt.image=ALL-UNNAMED` |
| Build | `./gradlew :lg3d-incubator:build` |

## Key components

- **ImageStudioFrame3D** — assembles the UI; **ImageStudioApp** — entry point.
- **EditorModel** — bounded undo/redo + `beginContinuousEdit`/`preview`/`endContinuousEdit`
  for live slider dragging.
- **JaiProcessor** — ~27 JAI rendered ops (geometry/color/filter/math) + histogram +
  PNG/JPEG/TIFF I/O.
- **OpCatalog** — the single source of truth for the op catalog (four category
  tabs, ~27 labelled/ranged ops, the code→`JaiProcessor` factory and the
  parameter formatter), rendered as 3D buttons by **Toolbar3D** and as Swing
  buttons by **ImageStudioPanel**.
- **ImageStudioPanel** — the 2D/Swing editing surface: category tabs + op grid,
  armed-parameter slider (same continuous-edit semantics), fit-to-view canvas,
  RGB histogram, `~/Pictures` filmstrip + Open/Save/Save As. Loads no Java 3D.
- **ImageCanvas3D** — the image quad: one power-of-two RGBA `ImageComponent2D`
  updated via `.set(RenderedImage)`.
- **Ui3D / Slider3D / Toolbar3D / Histogram3D / FileStrip3D** — the runtime-drawn 3D
  widget vocabulary (toolbar op catalog, drag slider, RGB histogram, `~/Pictures`
  filmstrip + native Swing `JFileChooser`).

## Roles

- **Architect** — A flagship **native-3D** app with a dual surface: the 3D
  desktop builds the `Frame3D`; the 2D desktop hosts `ImageStudioPanel`. Both
  share the engine (`EditorModel`/`JaiProcessor`) and the one `OpCatalog`, so
  an op added there appears on both surfaces. Keep the model/catalog AWT-free so
  it unit-tests headless; keep the 3D widgets (`Ui3D` family) reusable. JAI is an
  architecture-level dependency (needs the `sun.awt.image` export) — changing it
  touches `lg3d-core`'s `run` classpath **and** the incubator `test` task's
  `--add-exports`.
- **Engineer / Developer** — Obey the core UI/UX rulebook and the **live-texture
  rule**: one fixed-size `ImageComponent2D` with `ALLOW_IMAGE_WRITE` attached to a
  `Texture2D` **once, off-live**; every edit only repaints the `BufferedImage` and
  calls `.set()` in place — never re-attach. Use **power-of-two** textures. Upload
  texture pixels before attaching. Dev mode routes no keyboard focus to a `Frame3D`,
  so all input is click/drag driven. JAI I/O: use `javax.imageio.ImageIO` for
  PNG/JPEG; reserve the JAI codec for TIFF/BMP (JAI's JPEG encoder references the
  JDK-removed `com.sun.image.codec.jpeg`). Jogamp packages only.
- **QA** — Unit-test `EditorModel` (undo/redo, continuous edits) and `JaiProcessor`
  op math headless; `ImageStudioPanelTest` drives the Swing panel + real JAI ops
  headless (the incubator `test` task carries the `--add-exports
  java.desktop/sun.awt.image=ALL-UNNAMED` the vendored JAI needs). Verify the 3D UI
  with the in-JVM probe + internal screencapture
  (`lg3d-core/lgscreen-*.png`); a black host capture under Wayland is not a defect.
  Confirm the run classpath has the JAI jars + `--add-exports` (else `IllegalAccessError`
  on `sun.awt.image`).
- **Business Analyst** — A supported showcase utility (JAI image editor) proving the
  desktop can host a full native-3D creative tool. Production expectations apply.
- **Functional Analyst** — Spec user-visible function (open/edit/save images, ~27 ops,
  histogram, filmstrip) plus the core contract (Frame3D host, live-texture canvas,
  JAI classpath/export, descriptor in `lg3d-apps`).
- **Project Manager** — Commit scope `lg3d-incubator`; the descriptor lives in
  `lg3d-apps`, so an app PR usually spans two modules — say so. Done = build +
  `:lg3d-core:runtimeResources` (icon) + `./run-lg3d.sh` + capture/log evidence.
- **UI/UX (3D & 2D)** — **3D-dominant**: canvas, toolbar, slider, histogram and
  filmstrip are all runtime-drawn scene-graph widgets (no PNG assets), with native
  Swing `JFileChooser` for open/save. The 2D desktop gets the full editor as
  `ImageStudioPanel` (Swing tabs/grid/slider mirroring the same catalog and arming
  semantics). Follow the glassy vocabulary, depth ordering and click-driven-input
  rules from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook +
`docs/lg3d-native-apps.md` → root `AGENTS.md`. On conflict the higher file wins; fix
here in the same PR. Every PR states live vs dormant status, the descriptor location,
any `ext/`/classpath change, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump.
Stage only intended paths (never `git add -A`).
