# Changelog

All notable changes to Update are documented here.

## [1.9.0] — 2026-09-24

### Added

- **Software Update** — a
  self-contained Swing update pipeline adapted from an external module and
  integrated as a *Utilities* start-menu app. The module checks a release
  endpoint for a newer version, downloads and verifies it (SHA-256 plus optional
  OpenPGP detached signature via Bouncycastle), backs up the current install,
  installs, and offers rollback / downgrade / scheduling, a channel model
  (stable / beta / nightly), a system-tray notifier, a changelog viewer and a
  settings form. The adaptation is deliberately minimal: the original
  `com.protonmail.landrevillejf.swingide.update` package, Lombok and slf4j are
  kept, and only swing-ide-specific identifiers and user-facing strings were
  re-pointed at Project Looking Glass (config dir `~/.lg3d/`, `LG3D_UPDATE_TOKEN`,
  `lg3d.version`, the release `version.json` URL, "Project Looking Glass"
  wording). The removed `:ide-core` dependency is replaced by a local synchronous
  exact-type `EventBus`; Jackson, Bouncycastle, Lombok, Mockito and AssertJ were
  added to the version catalog, and a build-time `generateVersionFile` task feeds
  `ApplicationVersion`. On the desktop, `UpdateManagerPanel` (a plain `JPanel`)
  embeds the module's settings form behind an `UpdatePresenter` and is hosted on a
  `SwingNode` inside a `Frame3D` via `TitledSwingWindow` in 3D and, through a new
  `Desktop2DAppRegistry` panel mapping, as an MDI internal frame in the 2D/Swing
  desktop; `updatemanager.lgcfg` registers the menu item, and the
  `update-manager` jar plus its runtime deps are added to the hand-assembled
  `:lg3d-core:run` classpath. Covered by the module's headless JUnit 5 suite, a
  `Desktop2DAppRegistryTest` classification case and a new headless
  `UpdateManagerPanelTest`. A new `.github/workflows/release.yml` publishes the
  `version.json` manifest (and the signed `lg3d-<version>.zip` bundle) to the
  GitHub Releases `latest` redirect that `update.url` targets, so a live check
  now resolves instead of degrading to `UpdateServerUnavailableException`.
