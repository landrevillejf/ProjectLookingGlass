# Changelog

All notable changes to Calculator are documented here.

## [1.9.0] — 2026-09-24

### Added

- **Calculator** — an
  advanced scientific calculator whose Swing `JPanel` is hosted on a `SwingNode`
  inside a `Frame3D` via `TitledSwingWindow` (title bar, min/max/close, live
  taskbar thumbnail). A headless recursive-descent **expression engine**
  (`CalculatorEngine`: parentheses, `^` powers, factorial, `%`, `mod`, DEG/RAD
  trigonometry and inverses, `ln`/`log`/`sqrt`/`abs`, `pi`/`e`/`Ans`, memory
  register MC/MR/M+/M-/MS) drives an editable expression field with a **live
  result preview**, a six-column key pad and a clickable **history** list;
  keyboard input reaches the field through the `KeyEvent3D` forwarding. It is
  registered in the **Utilities** start-menu group and gets
  a 48x48 icon whose keypad glyph is drawn inside `GenerateAppIcons.java`, the
  bundled glyph set carrying nothing calculator shaped.
