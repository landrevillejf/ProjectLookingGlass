# Archive Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** utility (multi-format archive browser) |
| Entry point | `Archive.main` → `TitledSwingWindow.show(...)` hosting `ArchivePanel` |
| Surface | **SwingNode-in-Frame3D** (640x420); the same panel is reused as a 2D MDI frame |
| Start-menu name / group | Archive / **Utilities** |
| Command | `java org.jdesktop.lg3d.apps.archive.Archive` (accepts an optional archive-file arg) |
| Descriptor | `src/config/archive.lgcfg` → `config/demo` |
| Icon | `lg3d-core/src/resources/images/icon/archive.png` (in-tool `PackageBox` glyph, orange tile) |
| Build | `./gradlew :lg3d-apps:build` |

**Components:** `ArchivePanel` (Swing UI) + `ArchiveManager`, a single AWT-free
engine built on Apache Commons Compress that lists/extracts/creates archives of
every common open format (path-traversal hardened). It is headless-testable
(`ArchiveManagerTest`).

## Roles

- **Architect** — Production archive utility hosted through
  `TitledSwingWindow`/`SwingNode`; keep the panel plain Swing so it drives both
  desktops, and keep the archive engines AWT-free so they stay unit-testable and
  reusable. Registered in `Desktop2DAppRegistry.PANEL_APPS` for the 2D desktop.
- **Engineer / Developer** — Follow the core UI/UX rulebook: Metal LAF via
  `installHostedLookAndFeel`, EDT hops from lg3d listeners, blocking archive I/O
  on a `SwingWorker` (never the EDT), `dispose()` on discard. `JFileChooser`
  pickers are captured into the 3D scene by `SwingNodeWindowCapture`. Archive
  formats go through `ArchiveManager` (Commons Compress) — add new read formats
  by extending its magic-byte/stream dispatch, and new write formats by extending
  `create`'s filename dispatch + `wrapCompressor`. Extraction must keep the
  canonical-path traversal guard. Commons Compress + XZ are declared in
  `lg3d-apps/build.gradle` **and** wired onto the hand-assembled `:lg3d-core` run
  classpath + `releaseBundle` (they are not inherited). Jogamp packages only;
  Lombok `@Slf4j` for logging.
- **QA** — Unit-test `ArchiveManager` headless (multi-format round-trips + the
  traversal guards, see `ArchiveManagerTest`). Verify the hosted window with the
  in-JVM probe + internal screencapture; a black host capture under Wayland is not
  a defect. Read the log for `EventProcessor` warnings first.
- **Business Analyst** — A shipped Utilities tool: browse, list, extract and
  create archives of any common format without leaving the desktop. Production
  standards apply.
- **Functional Analyst** — Spec user-visible function (choose an archive, list
  entries, extract to a folder, create an archive whose format follows the output
  name) plus the contract with core (SwingNode surface, optional archive-file
  argument, descriptor fields).
- **Project Manager** — Commit scope `lg3d-apps`. Done = build +
  `./run-lg3d.sh` + capture/log evidence in the PR. Branch → PR against `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame + transparency
  ordering. 2D: the same Swing toolbar/entry-list panel in an MDI internal frame.
  Verify the file/dir pickers and overlay ordering over a maximized window.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Commit
scope `lg3d-apps`; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump; stage only intended paths (never `git add -A`).

## Known limitations

- Creation is limited to ZIP, TAR, TAR.GZ, TAR.BZ2 and TAR.XZ (the format is
  chosen from the output file name). 7-Zip, CPIO and AR are read-only, and the
  single-file compressor streams (`.gz`/`.bz2`/`.xz`) are read/extract only.
- RAR is proprietary and Zstandard / Brotli need extra optional native codecs, so
  those formats are deliberately out of scope.
- No per-entry selective extraction or drag-and-drop; the whole archive is
  extracted to the chosen folder.
