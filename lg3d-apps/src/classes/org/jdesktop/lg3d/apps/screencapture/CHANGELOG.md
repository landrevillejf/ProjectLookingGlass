# Changelog

All notable changes to Screencapture are documented here.

## [1.53.1] — 2026-10-06

### Changed

- **Screen Snapshot now saves to `~/Documents/Screenshots` by default** — the screen-capture
  config frame seeded its save location with `System.getProperty("user.dir")`,
  the process working directory, which inside the desktop is the `lg3d-core`
  source tree — so snapshots landed as stray `lgscreen-*.png` files in the
  checkout instead of somewhere the user would look for them. The default is now
  `Screenshots` inside the user's `Documents` folder ,
  resolved by the pure, headless-testable `defaultScreenshotDirectory()` helper
  and shown (still fully overridable) in the frame's location field. Both capture
  paths — the 2D/Swing `captureDesktopToPng` PNG writer and the 3D
  `ScreenCaptureEvent` handled by `ScreenCaptureBehavior` — now get a destination
  that is created on demand before the first write, so a fresh profile
  with no `Screenshots` folder still captures instead of silently failing.
