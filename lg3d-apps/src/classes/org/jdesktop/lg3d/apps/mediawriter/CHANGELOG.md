# Changelog

All notable changes to Mediawriter are documented here.

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

## [1.9.0] — 2026-09-24

### Added

- **Media Writer** — a
  full-featured disc and USB imaging tool whose Swing `JPanel` is hosted on a
  `SwingNode` inside a `Frame3D` via `TitledSwingWindow`. A headless engine drives the **real** Linux media tools — `growisofs` /
  `wodim` / `xorriso` for optical burns, `dd` for USB imaging and cloning,
  `wipefs` / `parted` / `mkfs.*` for formatting — across five modes: **burn an
  ISO to CD/DVD** (speed selection, `-dvd-compat`), **write a raw image or ISO
  to a USB key** (with `isohybrid` master-boot-record fix-up and optional
  bootable-partition handling), **clone a disc/device**, **format a removable
  key** (vfat/exfat/ntfs/ext4/ext2, optional msdos partition table, volume
  label) and **build a data disc** from a folder (`xorriso -as mkisofs`,
  Rock Ridge + Joliet, optionally burned straight to a drive). Devices are
  enumerated by parsing `lsblk -b -P` (optical / USB / internal-disk
  classification, mount points, media state from `/proc/sys/dev/cdrom/info`);
  images are probed for ISO-9660 and hybrid-magic before writing. Safety: an
  internal disk is never a writable target, mounted filesystems are unmounted
  first, destructive tools run under `pkexec` when not root, every write is
  gated by an explicit inline confirmation, and an optional **SHA-256 verify**
  re-reads the written media. The panel adds cancellation, live progress
  (parsed from `dd`/`growisofs` output) and a scrolling command log. Because
  `SwingNode` captures only its own panel, file picking and confirmation use
  **in-panel overlays** instead of modal dialogs. Registered in the
  **Utilities** start-menu group ; its 48x48 icon gets a
  disc glyph drawn inside `GenerateAppIcons.java`, the bundled glyph set
  carrying nothing disc shaped.
