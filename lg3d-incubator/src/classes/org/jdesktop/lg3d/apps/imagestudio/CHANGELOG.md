# Changelog

All notable changes to Imagestudio are documented here.

## [1.9.0] — 2026-09-24

### Added

- **Image Studio** — a
  full-featured image editor with an **lg3d-native 3D UI** (no Swing editing
  surface), built on the **bundled Java Advanced Imaging API** in `lg3d-incubator/ext`. A `Frame3D` window (with the standard decoration)
  lays out a textured-quad image canvas (mouse-wheel zoom, reset view), a left
  toolbar of categorised operations — **Geometry** (scale, rotate, flip, crop,
  border, pixelate), **Color** (brightness, contrast, gamma, grayscale, sepia,
  invert, posterize, threshold), **Filter** (blur, sharpen, emboss, edge) and
  **Math** (add/subtract/multiply constant, absolute, and/or/xor, noise) — driven
  by a live 3D parameter slider, a log-scaled 256-bin RGB histogram, and a bottom
  filmstrip of `~/Pictures` thumbnails. Bounded undo/redo/reset; images open from
  the filmstrip or a native `JFileChooser`, and save back to the current path or
  export to `~/Pictures/lg3d-imagestudio/` (PNG/JPEG via `ImageIO`, TIFF/BMP via
  the JAI codec). Registered in the start menu (Utilities) by
  `lg3d-apps/src/config/imagestudio.lgcfg`. The `lg3d-core:run` task now
  puts the two genuine JAI jars on the desktop
  classpath and exports `java.desktop/sun.awt.image` so JAI's `RasterAccessor`
  fast path works under JDK 21.
