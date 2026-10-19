# Changelog

All notable changes to Filemanager are documented here.

## [1.53.1] — 2026-10-06

### Added

- **Advanced File Manager operations: compression, burning, network sharing
  and volume mounting** — the production File Manager gains four advanced capabilities, each backed by
  an AWT-free, headless-testable engine so the Swing panel stays thin.
  **Compression** via `ArchiveOperations`, a pure-`java.util.zip` engine that
  creates / extracts / lists archives (zip, jar, war, ear, apk, cbz, zipx),
  writes atomically through a `.part` temp then move, and hardens extraction
  against Zip-Slip path traversal (both `../` and absolute entries are refused).
  **Burning** reuses the existing `MediaWriterEngine` rather than duplicating destructive
  device-write logic — a multi-item selection is staged into a temp folder and
  burned as a data disc, degrading gracefully to *Create ISO image only* when no
  optical drive is present. **Network sharing** via `ShareOperations`, a JDK
  `com.sun.net.httpserver` folder share (no external daemon, no root) that
  serves a browsable directory listing and file downloads on the LAN,
  percent-encodes hrefs (RFC 3986) and refuses path traversal with a 4xx; the
  active share is stopped when the panel closes. **Volume mounting** via
  `VolumeOperations`, which detects mountable volumes by parsing `lsblk -b -P`
  and mounts / unmounts through `udisksctl` (polkit, no password for removable
  media) with a `pkexec` fallback. The panel wires these into the toolbar
  (**Share**, **Mounts**) and the right-click context menu (**Compress…**,
  **Extract**, **Burn to Disc…**, **Share This Folder** / **Stop Sharing**,
  **Mounts / Volumes…**), running each operation off the EDT on a `SwingWorker`
  with a progress dialog. Covered by 32 headless JUnit 5 tests .
