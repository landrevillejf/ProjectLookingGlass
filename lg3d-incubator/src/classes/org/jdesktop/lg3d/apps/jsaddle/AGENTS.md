# jsaddle (JPedal PDF Viewer)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Live** (2006-era prototype revived: PDF backend rewritten from JPedal to Apache PDFBox for JDK 21) |
| Entry point | `jsaddle.Main` |
| Surface | Hybrid: a Swing `FileChooser` (`javax.swing.JPanel`) plus 3D thumbnail actions (`Thumbnail3DTimeFrameAction`, `ThumbnailFilmLikeAction`) |
| Start-menu name / group | PDF Viewer / **Media** — live descriptor `jsaddle.lgcfg` is in `lg3d-apps/src/config` → `config/demo` (**scanned**); the inert duplicate under `lg3d-incubator/src/config` → `config/` is never discovered |
| Command | `java org.jdesktop.lg3d.apps.jsaddle.Main` |
| PDF backend | **Apache PDFBox** (`org.apache.pdfbox:pdfbox`, Apache-2.0, Maven Central) — `PdfManager` renders each page with `Loader.loadPDF` + `PDFRenderer.renderImageWithDPI`. The commercial JPedal jars (`jpedalSTD`/`cid`/`bcprov-jdk14`) are no longer used. |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A PDF viewer whose backend is isolated in `PdfManager`, an
  adapter that returns each page as a `BufferedImage`. It was rewritten from the
  proprietary JPedal library to **Apache PDFBox** (Apache-2.0), so the app now
  builds and runs with an open-source backend. PDFBox is a Maven dependency of
  `lg3d-incubator` **and** must be resolved onto the hand-assembled
  `:lg3d-core:run` classpath (see the `pdfboxLibs` detached configuration in
  `lg3d-core/build.gradle`) or the in-JVM launch dies with `NoClassDefFoundError:
  org/apache/pdfbox/Loader`. The live start-menu descriptor lives in
  `lg3d-apps/src/config` (discovery only scans `config/demo`/`config/incubator`).
- **Engineer / Developer** — Keep the JPedal→PDFBox seam confined to `PdfManager`:
  the 3D viewer (`ViewerContainer`, `JSaddleManager`, `ThumbnailViewerContainer`)
  only consumes `BufferedImage`, so it must not learn about PDFBox. Page numbers
  exposed by `PdfManager` are 1-based; PDFBox is 0-based, so convert inside the
  adapter. Obey the core UI/UX rulebook and the live-texture discipline; Jogamp
  packages only.
- **QA** — No longer dependency-blocked: verify the backend headless (PDFBox
  renders a page to a non-null `BufferedImage` with no X display) and the 3D view
  with the in-JVM probe + internal screencapture. `PdfManager` is AWT-free apart
  from `BufferedImage`, so it is the natural unit-test seam.
- **Business Analyst** — A revived, genuinely useful desktop app: an open-source
  3D PDF viewer with page-flip animations and a thumbnail filmstrip.
- **Functional Analyst** — The exclusion/blocker rationale is now historical:
  JPedal was commercial and absent; PDFBox is the open-source replacement.
- **Project Manager** — Commit scope `lg3d-incubator` (the descriptor + icon touch
  `lg3d-apps`/`lg3d-core`, so the PR spans modules — call that out). Live.
- **UI/UX (3D & 2D)** — Hybrid **2D** Swing file chooser + **3D** page/thumbnail view.
  Follow the glassy vocabulary and depth ordering from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
