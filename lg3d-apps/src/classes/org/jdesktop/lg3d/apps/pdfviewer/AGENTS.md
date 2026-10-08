# PDF Viewer Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (document reader) |
| Entry point | `PdfViewer.main` → `TitledSwingWindow.show(...)` |
| Surface | **SwingNode-in-Frame3D** (hosts `PdfViewerPanel` on a `SwingNode` quad); the same panel is reused in the 2D desktop |
| Start-menu name / group | PDF Viewer / **Office** |
| Command | `java org.jdesktop.lg3d.apps.pdfviewer.PdfViewer` |
| Descriptor | `src/config/pdfviewer.lgcfg` → `config/demo` |
| PDF backend | **Apache PDFBox** (`org.apache.pdfbox:pdfbox`, Apache-2.0) — `PdfDocument` rasterises each page with `Loader.loadPDF` + `PDFRenderer.renderImageWithDPI` |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **PdfViewer** — thin 3D entry point; installs the hosted look and feel and
  shows `PdfViewerPanel` in a `TitledSwingWindow`.
- **PdfViewerPanel** — a plain `JPanel` (no-arg constructor, no Java 3D): a
  toolbar (Open, first / previous / page n of N / next / last, zoom out /
  percent / zoom in / fit width), a scrollable page canvas that centres the
  rendered page when it is smaller than the viewport, and a status line.
- **PdfDocument** — the AWT-free (bar `BufferedImage`) PDFBox adapter: open
  file / bytes / resource, 1-based paging, `renderPage(page, zoom)` at
  `72 * zoom` DPI, first-page size in points. The headless unit-test seam.

## Roles

- **Architect** — The whole feature lives in this package: `PdfDocument` (PDFBox
  seam), `PdfViewerPanel` (the one Swing UI) and `PdfViewer` (the 3D host). The
  same panel drives both desktops: 3D via `TitledSwingWindow`, 2D via
  `Desktop2DAppRegistry.PANEL_APPS` keyed on `PdfViewer`. PDFBox is an
  `lg3d-apps` dependency **and** must be resolved onto the hand-assembled
  `:lg3d-core:run` / `releaseBundle` classpath (the `pdfboxLibs` detached
  configuration in `lg3d-core/build.gradle`) or the in-JVM launch dies with
  `NoClassDefFoundError: org/apache/pdfbox/Loader`.
- **Engineer / Developer** — Keep the PDFBox seam confined to `PdfDocument`; the
  panel only consumes `BufferedImage`. Page numbers are 1-based here (PDFBox is
  0-based) — convert inside the adapter. Keep the panel free of Synth-only
  widgets (no combo boxes) and of modal dialogs in the constructor so it paints
  offscreen; `chooseFile()` is only reached from the Open button. Jogamp
  packages only; obey the core UI/UX rulebook.
- **QA** — `PdfDocumentTest` and `PdfViewerPanelTest` run headless: they build a
  throwaway multi-page PDF in memory with PDFBox (no bundled sample), then
  assert open / page-count / clamped paging / zoomed raster size and the
  panel's navigation, zoom and status readouts. For the 3D host use the in-JVM
  probe + internal screencapture (`lg3d-core/lgscreen-*.png`); a black capture
  under Wayland is not a defect.
- **Business Analyst** — A daily-driver utility: read, page through and zoom any
  PDF from the desktop without installing a separate reader. Value = a
  trustworthy, keyboard-and-mouse-friendly document view in both desktops.
- **Functional Analyst** — Spec this app as the *reader contract*: open a file,
  page first/prev/next/last and by number, zoom in/out/fit-width, and a status
  line naming the file, page x of y and the zoom. The backend (PDFBox) is an
  implementation detail behind `PdfDocument`.
- **Project Manager** — Commit scope `lg3d-apps`; the registration also touches
  `lg3d-core` (`Desktop2DAppRegistry` panel mapping + the run/releaseBundle
  classpath) and the icon in `lg3d-core` resources — call that out. Done =
  build + headless tests + `./run-lg3d.sh` capture/log evidence. Branch → PR
  against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame; the panel is the
  SwingNode content. 2D: the identical `PdfViewerPanel` in an MDI internal
  frame. Conventional reader chrome (toolbar + scroll + status), never a
  click-cycling 3D idiom; keep both surfaces pixel-identical.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode host + 2D MDI), the `PdfDocument` seam,
and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump. Stage only intended paths (never `git add -A`).
