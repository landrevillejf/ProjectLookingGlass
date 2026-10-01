# Backup & Restore Tool

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** daily-driver utility (file/folder backup and restore) |
| Entry point | `Backup.main` → `TitledSwingWindow.show(...)`; `BackupClient.main` runs the same panel standalone |
| Surface | **SwingNode-in-Frame3D** (hosts `BackupPanel` on a `SwingNode` quad); the same panel is reused in the 2D desktop |
| Start-menu name / group | Backup / **System** |
| Command | `java org.jdesktop.lg3d.apps.backup.Backup` |
| Descriptor | `src/config/backup.lgcfg` → `config/demo` |
| Format | Standard **ZIP** archives via the JDK's built-in `java.util.zip` — no third-party dependency; any unzip tool reads a backup and the tool restores any standard ZIP |
| Persistence | Jackson JSON under `~/.lg3d/backup` (saved profiles) via `BackupStore`; override dir with `-Dlg3d.backup.dir` |
| Security | **Zip-Slip hardened**: every entry is resolved against the destination and rejected if it escapes (absolute names, `../` traversal), so a hostile archive cannot overwrite files outside the chosen folder. No secret is stored — a profile records only paths and options |
| Reliability | A backup streams into a sibling `.part` file and is **atomically moved** onto the target only once complete, so an interrupted run never leaves a truncated `.zip`; unreadable files are skipped with a warning rather than aborting |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **Backup** — thin 3D entry point; installs the hosted look and feel and shows
  `BackupPanel` in a `TitledSwingWindow`, wiring `setOnClose` to
  `frame.changeEnabled(false)`.
- **BackupClient** — standalone `JFrame` (`DISPOSE_ON_CLOSE`) for running the tool
  outside the desktop; never calls `System.exit`.
- **BackupPanel** — the one Swing UI (no-arg constructor, no Java 3D): a WEST
  profile list (New / Duplicate / Delete), a CENTER editor (name, archive base
  name, destination chooser, source list with Add File / Add Folder / Remove,
  compression level, exclude patterns, include-hidden and timestamp options), and
  a SOUTH action/progress/log strip (Back Up Now, Restore, Browse Archive,
  Cancel). All I/O runs on a `SwingWorker`; `JFileChooser`s are created lazily so
  the panel builds headless.
- **BackupEngine** — the AWT-free worker: `backup` (walk sources, prune excludes,
  stream a ZIP), `restore` (Zip-Slip-guarded extraction) and `list` (browse an
  archive). Long loops honour an optional cancel signal; progress is reported per
  entry through `BackupProgressListener`. This is the single tested seam where all
  the security/reliability logic lives.
- **BackupProfile / BackupResult / ArchiveEntryInfo** — model beans: the saved
  configuration (Jackson-serialisable), the immutable outcome of a run (counts,
  bytes, duration, warnings), and a listed archive entry.
- **BackupProgressListener** — the progress callback contract (`NULL` no-op
  default) the panel adapts to a progress bar via `SwingWorker.publish`.
- **BackupStore** — defensive JSON persistence (a corrupt/missing file yields an
  empty list, never throws).

## Roles

- **Architect** — Everything lives in this package: the model beans, the
  AWT-free `BackupEngine`, `BackupStore` (Jackson I/O), `BackupPanel` (the one
  Swing UI) and the two thin entry points. The same panel drives both desktops:
  3D via `TitledSwingWindow`, 2D via `Desktop2DAppRegistry.PANEL_APPS` keyed on
  `Backup`. Jackson + SLF4J are already `lg3d-apps` compile deps and are on the
  hand-assembled `:lg3d-core:run` / `releaseBundle` classpath, so the in-JVM
  launch resolves. **No new third-party dependency and no native library are
  added** — ZIP is pure `java.util.zip`. Keep all filesystem/security logic in the
  engine (testable without a display), never in the panel.
- **Engineer / Developer** — Keep archive I/O in `BackupEngine`; the panel only
  calls `backup`/`restore`/`list` and marshals progress onto the EDT via a
  `SwingWorker`. **Never weaken the Zip-Slip guard** — the
  `resolved.startsWith(dest)` check on every entry is the security boundary; a new
  extraction path must go through it. Preserve the atomic `.part`-then-move write
  and the directory-entry trailing `/` (a ZIP dir entry without `/` restores as a
  0-byte file that collides with its own children). Guard every
  `JFileChooser`/`JOptionPane` path so it is only reached from a user action, never
  the constructor, so headless tests can build the panel. Never call
  `System.exit`. Jogamp packages only where 3D is touched (none here); obey the
  core UI/UX rulebook.
- **QA** — `BackupEngineTest`, `BackupProfileTest`, `BackupStoreTest` and
  `BackupPanelTest` run headless (50 tests): the engine suite drives the
  backup→restore round-trip, glob excludes, hidden-file opt-in, empty-directory
  and nested-structure preservation, compression-level sizing, cancellation,
  archive listing, progress monotonicity, and **both Zip-Slip vectors** (a `../`
  traversal entry and an absolute entry are rejected and never written); the
  profile/store suites assert bean invariants, clamping, deep copy, JSON
  round-trips to a temp dir and corrupt-file resilience; the panel suite asserts
  profile seed/add/delete, source de-duplication, editor population/write-back and
  persistence. For the 3D host use the in-JVM probe + internal screencapture
  (`lg3d-core/lgscreen-*.png`); a black capture under Wayland is not a defect.
- **Business Analyst** — A daily-driver data-protection utility: back up chosen
  folders/files to portable ZIP archives and restore them, with reusable profiles,
  excludes and scheduling-friendly naming, in both desktops. Value = trustworthy,
  self-contained backups with no external tool or cloud dependency, and a restore
  path that is safe against malicious archives.
- **Functional Analyst** — Spec this app as the *backup contract*: archive a set
  of sources into a ZIP (preserving structure), restore an archive into a chosen
  folder without ever escaping it, list an archive's contents, and persist
  reusable profiles — with the archive format and the traversal defence hidden
  behind `BackupEngine`. Restore is specified as "extract only within the
  destination", never as a blind unpack.
- **Project Manager** — Commit scope `lg3d-apps`; the registration also touches
  `lg3d-core` (`Desktop2DAppRegistry` panel mapping + test) and the icon in
  `lg3d-core` resources, plus the `lg3d-art` icon tool — call that out. Done =
  build + headless tests + `./run-lg3d.sh` capture/log evidence. Branch → PR
  against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame; the panel is the
  SwingNode content. 2D: the identical `BackupPanel` in an MDI internal frame.
  Conventional profiles-list-plus-editor chrome with a persistent progress bar and
  log, never a click-cycling 3D idiom; keep both surfaces pixel-identical. A
  rejected unsafe entry or a skipped unreadable file must surface in the log so it
  reads as a deliberate safeguard, not a silent failure.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the surface (SwingNode host + 2D MDI), the `BackupEngine` seam,
the ZIP format and the Zip-Slip defence, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; feature branch off `main`; PR against `main` via `gh`. Stage
only intended paths (never `git add -A`); exclude runtime screenshots and stray
downloads.
