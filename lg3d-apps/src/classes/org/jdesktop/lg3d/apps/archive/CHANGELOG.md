# Changelog

All notable changes to Archive are documented here.

## [1.64.0] — 2026-10-07

### Added

- **Archive: a multi-format archive browser for the desktop** (`lg3d-apps`
  `org.jdesktop.lg3d.apps.archive`) — a new Utilities start-menu app that opens
  an archive, lists its entries, extracts it to a chosen folder and creates a new
  archive from a directory. The Swing `ArchivePanel` is hosted on a `SwingNode`
  inside a `Frame3D` via `TitledSwingWindow` in the 3D desktop and, registered in
  `Desktop2DAppRegistry.PANEL_APPS`, opens as an MDI internal frame in the 2D/Swing
  desktop — one panel, both desktops. Archive I/O runs off the EDT on a
  `SwingWorker` and is delegated to the AWT-free, path-traversal-hardened
  `ArchiveManager`, built on Apache Commons Compress so it handles **every common
  open format** rather than just ZIP: it reads ZIP, 7-Zip, TAR, TAR wrapped in any
  auto-detected compressor (`.tar.gz`/`.tgz`, `.tar.bz2`, `.tar.xz`, Z, LZMA),
  CPIO, AR and lone single-file compressor streams (`.gz`/`.bz2`/`.xz`), and
  creates ZIP, TAR, TAR.GZ, TAR.BZ2 and TAR.XZ (the format follows the output file
  name). RAR (proprietary) and Zstandard/Brotli (extra native codecs) are
  deliberately out of scope. Ships an in-tool `PackageBox` start-menu icon and headless `ArchiveManagerTest`
  multi-format round-trip + Zip-Slip / tar-slip guard coverage.
