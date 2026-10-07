# Archive Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** utility (ZIP / TAR archive browser) |
| Entry point | `Archive.main` → `TitledSwingWindow.show(...)` hosting `ArchivePanel` |
| Surface | **SwingNode-in-Frame3D** (640x420); the same panel is reused as a 2D MDI frame |
| Start-menu name / group | Archive / **Utilities** |
| Command | `java org.jdesktop.lg3d.apps.archive.Archive` (accepts an optional archive-file arg) |
| Descriptor | `src/config/archive.lgcfg` → `config/demo` |
| Icon | `lg3d-core/src/resources/images/icon/archive.png` (in-tool `PackageBox` glyph, orange tile) |
| Build | `./gradlew :lg3d-apps:build` |

**Components:** `ArchivePanel` (Swing UI) + `ZipManager` (`java.util.zip`
list/extract/create, Zip-Slip hardened) + `TarManager` (dependency-free POSIX
tar reader, path-traversal guarded). Both engines are AWT-free and
headless-testable (`ArchiveManagersTest`).

## Roles

- **Architect** — Production archive utility hosted through
  `TitledSwingWindow`/`SwingNode`; keep the panel plain Swing so it drives both
  desktops, and keep the archive engines AWT-free so they stay unit-testable and
  reusable. Registered in `Desktop2DAppRegistry.PANEL_APPS` for the 2D desktop.
- **Engineer / Developer** — Follow the core UI/UX rulebook: Metal LAF via
  `installHostedLookAndFeel`, EDT hops from lg3d listeners, blocking archive I/O
  on a `SwingWorker` (never the EDT), `dispose()` on discard. `JFileChooser`
  pickers are captured into the 3D scene by `SwingNodeWindowCapture`. Extraction
  and creation must keep the Zip-Slip / path-traversal guards. Jogamp packages
  only; Lombok `@Slf4j` for logging (both are already on the module classpath).
- **QA** — Unit-test `ZipManager`/`TarManager` headless (round-trip + the
  traversal guards, see `ArchiveManagersTest`). Verify the hosted window with the
  in-JVM probe + internal screencapture; a black host capture under Wayland is not
  a defect. Read the log for `EventProcessor` warnings first.
- **Business Analyst** — A shipped Utilities tool: browse, list, extract and
  create ZIP/TAR archives without leaving the desktop. Production standards apply.
- **Functional Analyst** — Spec user-visible function (choose an archive, list
  entries, extract to a folder, create a ZIP) plus the contract with core
  (SwingNode surface, optional archive-file argument, descriptor fields).
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

- TAR support is read-only (list + extract regular files and directories); TAR
  creation is not implemented — "Create ZIP..." produces ZIP archives only.
- No gzip/bzip2/xz (`.tar.gz`, `.tgz`) or 7z/rar support; only uncompressed tar
  and ZIP.
- No per-entry selective extraction or drag-and-drop; the whole archive is
  extracted to the chosen folder.
