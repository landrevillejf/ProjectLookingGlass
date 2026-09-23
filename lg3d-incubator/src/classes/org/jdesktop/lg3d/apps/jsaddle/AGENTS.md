# jsaddle (JPedal PDF Viewer)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Dormant prototype** (2006-era; compiles only if the JPedal jars are present) |
| Entry point | `jsaddle.Main` |
| Surface | Hybrid: a Swing `FileChooser` (`javax.swing.JPanel`) plus 3D thumbnail actions (`Thumbnail3DTimeFrameAction`, `ThumbnailFilmLikeAction`) |
| Start-menu name / group | jsaddle (JPedal-based PDF Viewer) / **Media** — descriptor `jsaddle.lgcfg` is in `lg3d-incubator/src/config` → `config/` (**not scanned**) |
| Command | `java org.jdesktop.lg3d.apps.jsaddle.Main` |
| Runtime blocker | Needs the commercial **JPedal** PDF library (`jpedalSTD.jar`, `cid.jar`, `bcprov-jdk14.jar`) — not in the repo, not on Maven Central |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A PDF viewer built on the proprietary JPedal library. Dormant and
  **dependency-blocked** (JPedal is commercial and absent). Do not attempt to build or
  revive it without first supplying a PDF backend (JPedal or a replacement such as
  PDFBox).
- **Engineer / Developer** — If ever revived: render pages into a live texture (single
  `ImageComponent2D`, `.set()` per page, never re-attach; POT sizes) and keep the Swing
  file chooser on the EDT. Obey the core UI/UX rulebook. Jogamp packages only.
- **QA** — Classify as **dependency-blocked**: it cannot run without JPedal. Do not
  file "won't open PDFs" as a regression; record the missing-library blocker.
- **Business Analyst** — Historical prototype; no product value while JPedal is
  unavailable/licensed.
- **Functional Analyst** — Document the missing-library blocker explicitly so a future
  port re-triages with a real PDF backend.
- **Project Manager** — Commit scope `lg3d-incubator`. Dormant/blocked — do not
  schedule without resolving the JPedal dependency.
- **UI/UX (3D & 2D)** — Hybrid **2D** Swing file chooser + **3D** page/thumbnail view.
  Follow the glassy vocabulary and depth ordering from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
