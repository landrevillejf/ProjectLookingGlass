# Photo Viewer

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** tagged photo gallery and viewer |
| Entry point | `PhotoViewer.main` → `TitledSwingWindow.show(...)`; `PhotoViewerClient.main` runs the same panel standalone |
| Surface | **SwingNode-in-Frame3D** (hosts `PhotoViewerPanel` on a `SwingNode` quad); the same panel is reused in the 2D desktop |
| Start-menu name / group | Photo Viewer / **Media** |
| Command | `java org.jdesktop.lg3d.apps.photoviewer.PhotoViewer` |
| Descriptor | `src/config/photoviewer.lgcfg` → `config/demo` |
| Format | **Fully native `ImageIO`** — no codec, no third-party library, no Java 3D. Thumbnails and previews are decoded on demand and cached; jpg/png/gif/bmp/webp/tiff are recognised |
| Persistence | Jackson JSON under `~/.lg3d/photoviewer` (the tagged library) via `PhotoViewerStore`; override dir with `-Dlg3d.photoviewer.dir` |
| Reliability | Pixels are loaded lazily and every decode is guarded, so a missing or corrupt image shows a placeholder rather than throwing; the library stores only paths, titles, tags and ratings — never image data |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **PhotoViewer** — thin 3D entry point; installs the hosted look and feel and
  shows `PhotoViewerPanel` in a `TitledSwingWindow`, wiring `setOnClose` to
  `frame.changeEnabled(false)`.
- **PhotoViewerClient** — standalone `JFrame` (`DISPOSE_ON_CLOSE`) for running the
  viewer outside the desktop; never calls `System.exit`.
- **PhotoViewerPanel** — the one Swing UI (no-arg constructor, no Java 3D): a WEST
  filter dock (tag combo, keyword, minimum rating), a CENTER split of a thumbnail
  gallery and a large preview with previous/next, and a SOUTH tag/rating editor.
  `JFileChooser`s are created lazily and thumbnails decode only when a cell is
  rendered, so the panel builds headless without touching a pixel.
- **PhotoLibrary** — the AWT-free model: de-duplicated insertion by path, lookup,
  removal, the union of all tags and a `filter(tag, keyword, minRating)` query.
  Pure, so all gallery logic is unit-testable headless.
- **PhotoItem** — a Jackson bean: path, title, tag set and a 0..5 star rating;
  tags trim/ignore blanks/de-duplicate and the rating clamps.
- **PhotoViewerStore** — defensive JSON persistence (a corrupt/missing file yields
  an empty library, never throws).

## Roles

- **Architect** — Everything lives in this package: the model (`PhotoLibrary` /
  `PhotoItem`), `PhotoViewerStore` (Jackson I/O), `PhotoViewerPanel` (the one
  Swing UI) and the two thin entry points. The same panel drives both desktops:
  3D via `TitledSwingWindow`, 2D via `Desktop2DAppRegistry.PANEL_APPS` keyed on
  `PhotoViewer`. **No new dependency is added** — decode is `javax.imageio`, JSON
  is the existing Jackson dep. Keep all query logic in `PhotoLibrary` (testable
  without a display), never in the panel.
- **Engineer / Developer** — Keep decode lazy and guarded: read pixels only in the
  renderer/preview, wrap every `ImageIO.read` so a missing file yields null and a
  placeholder, and cache thumbnails by path (evicting on remove/clear). Keep the
  filter/tag/rating logic in `PhotoLibrary`/`PhotoItem`; the panel only reflects
  it. Guard every `JFileChooser` path so it is only reached from a user action,
  never the constructor. Never call `System.exit`. Obey the core UI/UX rulebook.
- **QA** — `PhotoItemTest`, `PhotoLibraryTest`, `PhotoViewerStoreTest` and
  `PhotoViewerPanelTest` run headless (24 tests): the item suite asserts titling,
  tag de-duplication and rating clamps; the library suite asserts de-duplicated
  insertion, lookup/removal, tag union and every filter branch; the store suite
  asserts JSON round-trips and corrupt-file resilience; the panel suite asserts
  construction, library growth, selection, folder-scan extension filtering and
  reload — never decoding a real photo during build. For the 3D host use the
  in-JVM probe + internal screencapture (`lg3d-core/lgscreen-*.png`); a black
  capture under Wayland is not a defect.
- **Business Analyst** — A daily-driver photo gallery: scan a folder, browse
  thumbnails, filter by tag / keyword / rating and preview full size, in both
  desktops. Value = an organised, self-contained viewer with no cloud or database
  dependency.
- **Functional Analyst** — Spec this app as the *library contract*: a
  de-duplicated collection of tagged, rated photos that can be filtered and
  previewed — with the pixels loaded lazily and the query logic hidden behind
  `PhotoLibrary`. A filter is specified declaratively (tag AND keyword AND
  min-rating), never as panel-side ad-hoc code.
- **Project Manager** — Commit scope `lg3d-apps`; the registration also touches
  `lg3d-core` (`Desktop2DAppRegistry` panel mapping + test) and the icon in
  `lg3d-core` resources, plus the `lg3d-art` icon tool — call that out. Done =
  build + headless tests + `./run-lg3d.sh` capture/log evidence. Branch → PR
  against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame; the panel is the
  SwingNode content. 2D: the identical `PhotoViewerPanel` in an MDI internal
  frame. Conventional gallery chrome (filter dock + thumbnail grid + preview +
  tag/rating editor), never a click-cycling 3D idiom; keep both surfaces
  pixel-identical. A photo that cannot be decoded must show a named placeholder,
  and the current filter/selection must stay legible.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode host + 2D MDI), the `PhotoLibrary` seam,
the lazy-decode rule and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; feature branch off `main`; PR against `main` via `gh`. Stage
only intended paths (never `git add -A`); exclude runtime screenshots and stray
downloads.
