# lg3d-apps

The **production-grade desktop applications** shipped with Project Looking Glass
(plus a few tutorial/sample apps). Formerly named `lg3d-demo-apps`.

> The `src/` tree is the original Project Looking Glass apps source dump,
> migrated to build on a modern toolchain. See the
> [root README](../README.md) for the full port overview.

## Build status

Part of the Gradle build (JDK 21 toolchain). Ported from the 2006-era Ant build
(`build.xml`); pure Java, compiled against `lg3d-core` (which exposes the Jogamp
Java 3D API transitively).

```bash
./gradlew :lg3d-apps:build   # jar -> build-gradle/libs/lg3d-apps-1.9.0-dev.jar
```

The jar is placed on the desktop's classpath by the `lg3d-core:run` task, so
these apps appear on the running lg3d desktop.

## Packaging notes

- Sources live under `src/classes`.
- Resources sit alongside the sources (per-app `resources/` trees, shader
  `.frag`/`.vert` files, icons) and are bundled into the jar.
- The desktop app descriptors under `src/config` are bundled into `config/demo`,
  as the legacy build did.

## Dependencies

- `lg3d-core` (project dependency) — provides the SDK and the Jogamp Java 3D API.

## Calculator

`org.jdesktop.lg3d.apps.calculator` — an advanced scientific calculator whose
Swing panel is hosted on a `SwingNode` inside a `Frame3D` via
`TitledSwingWindow` (see [`docs/swingnode.md`](../docs/swingnode.md)): a
headless recursive-descent expression engine (parentheses, `^`, factorial,
`%`, `mod`, DEG/RAD trigonometry, `ln`/`log`/`sqrt`/`abs`, `pi`/`e`/`Ans`), a
memory register (MC/MR/M+/M-/MS), a live result preview over an editable
expression field, and a clickable history list. The panel uses a null layout
with explicit bounds, as `SwingNode` paints hosted panels offscreen without a
layout pass. Start-menu descriptor: `src/config/calculator.lgcfg` (Utilities
group); the icon keypad glyph is drawn by
`lg3d-art/tools/GenerateAppIcons.java`.

## Media Writer

`org.jdesktop.lg3d.apps.mediawriter` — a full-featured disc and USB imaging
tool whose Swing panel is hosted on a `SwingNode` inside a `Frame3D` via
`TitledSwingWindow` (see [`docs/swingnode.md`](../docs/swingnode.md)). A
headless `MediaWriterEngine` drives the real Linux media tools across five
modes: burn an ISO to CD/DVD (`growisofs`/`wodim`/`xorriso`, speed selection),
write a raw image or ISO to a USB key (`dd`, optional `isohybrid` bootable
fix-up), clone a disc/device, format a removable key (`wipefs`/`parted`/
`mkfs.*`: vfat/exfat/ntfs/ext4/ext2, optional msdos partition table and volume
label), and build a data disc from a folder (`xorriso -as mkisofs -r -J`,
optionally burned directly). Devices are enumerated by parsing `lsblk -b -P`;
images are probed for ISO-9660/hybrid magic. Safety guards: internal disks are
never writable targets, mounted filesystems are unmounted first, destructive
tools are elevated with `pkexec`, every write requires an explicit inline
confirmation, and an optional SHA-256 verify re-reads the media. Progress,
cancellation and a command log stream into the panel; file picking and
confirmation use in-panel overlays because modal dialogs escape the `SwingNode`
offscreen capture. Like the Calculator, the panel uses a null layout with
explicit bounds. Start-menu descriptor: `src/config/mediawriter.lgcfg`
(Utilities group); the icon disc glyph is drawn by
`lg3d-art/tools/GenerateAppIcons.java`.
