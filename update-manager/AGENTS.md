# update-manager

## Overview

A self-contained Swing **update pipeline** for the desktop: check a release
endpoint for a newer version, download and verify it (SHA-256 and optional
OpenPGP detached signature), back up the current install, install, and offer
rollback / downgrade / scheduling. It also carries the channel model
(stable / beta / nightly), a system-tray notifier, a changelog viewer and the
settings UI.

The module was adapted from an external "Swing IDE" project. The adaptation is
deliberately **minimal**: the original package
`com.protonmail.landrevillejf.swingide.update` (plus its `changelog`, `channels`,
`downgrade`, `events`, `notifications`, `privilege`, `rollback`, `scheduling`,
`security` and `ui` sub-packages), **Lombok** (`@Slf4j`, `@Value`) and **slf4j**
logging are kept as-is. Only swing-ide-specific *identifiers and user-facing
strings* were re-pointed at Project Looking Glass.

## lg3d adaptation

- **Local `EventBus`** — the external `:ide-core` dependency is gone, so
  `com.protonmail.landrevillejf.swingide.core.bus.EventBus` is reproduced
  in-module: a synchronous, exact-type publish/subscribe bus
  (`subscribe` / `unsubscribe` / `publish` / `shutdown`) backed by a
  `ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Consumer<?>>>`. `publish`
  dispatches on the calling thread to consumers registered for
  `event.getClass()`. This satisfies `UpdateService`, `UpdateChecker` and
  `UpdatePresenter` and their tests.
- **Configuration** (`src/main/resources/update-config.properties`) —
  `update.url` points at
  `https://github.com/landrevillejf/ProjectLookingGlass/releases/latest/download/version.json`;
  the user override file lives under `~/.lg3d/update-config.properties`.
- **Runtime identifiers** — user config dir `~/.swing-ide/` → `~/.lg3d/`;
  system property `swingide.config.dir` → `lg3d.config.dir`; auth env var
  `SWINGIDE_UPDATE_TOKEN` → `LG3D_UPDATE_TOKEN` (still falling back to
  `GITHUB_TOKEN`); version property `swingide.version` → `lg3d.version`;
  backup prefix `swing-ide-backup-` → `lg3d-backup-`; temp prefix
  `swingide-update-` → `lg3d-update-`.
- **User-facing strings** — "Swing IDE" / "SwingIDE" → "Project Looking Glass"
  (window titles, notifications, changelog heading, up-to-date message).
  Technical identifiers that are never shown to the user were left untouched.
- **Dependencies** added to the version catalog and `build.gradle`: Jackson
  (`jackson-databind`, for the `version.json` manifest), Bouncycastle OpenPGP
  (`bcpg-jdk18on` + `bcprov-jdk18on`, for signature verification), slf4j
  (`slf4j-api` + `slf4j-nop`), Lombok (`compileOnly` + `annotationProcessor`),
  and for tests JUnit 5, Mockito and AssertJ.
- **`version.properties`** is generated at build time by the `generateVersionFile`
  task into `build-gradle/generated/version/` (`app.version`, `build.timestamp`)
  and merged into the jar by `processResources`, so `ApplicationVersion` reports
  the running lg3d version.

## Desktop integration

The module is **not** wired into the display server directly; it is surfaced as
the **"Software Update"** start-menu app (Utilities group), exactly like the Help
Center:

- `lg3d-demo-apps` → `org.jdesktop.lg3d.apps.update.UpdateManagerPanel` (a plain
  `JPanel` with a no-arg constructor) embeds `UpdateSettingsPanel`, drives an
  `UpdatePresenter` and builds an `UpdateService` via `createDefault()`. It never
  throws out of its constructor: if the service cannot be created it degrades to a
  readable "unavailable" pane, and it gates the periodic network check
  (`service.initialize()`) behind `!GraphicsEnvironment.isHeadless()`.
- `UpdateManager` is the 3D-desktop wrapper: `TitledSwingWindow.show(...)` hosts
  the panel on a `SwingNode` inside a `Frame3D`.
- `lg3d-demo-apps/src/config/updatemanager.lgcfg` registers the start-menu item
  (`command = java org.jdesktop.lg3d.apps.update.UpdateManager`).
- `Desktop2DAppRegistry.PANEL_APPS` maps the command to `UpdateManagerPanel`, so
  the 2D/Swing desktop hosts the *same* panel as an MDI internal frame.
- The `:lg3d-core:run` classpath is hand-assembled from packaged jars, so it
  explicitly adds the `update-manager` jar plus a detached `updateManagerLibs`
  configuration (jackson-databind, slf4j-api/nop, bcpg/bcprov-jdk18on).

## Release publishing (`.github/workflows/release.yml`)

Live checks read the `version.json` asset on the GitHub Releases `latest`
redirect (the `update.url` default). `.github/workflows/release.yml` publishes
it: on a published Release (or `workflow_dispatch` for a tag) it assembles
`lg3d-<version>.zip` via `:lg3d-core:releaseBundle`, computes its size + SHA-256,
optionally signs it (armored detached PGP `.zip.asc`, the format
`UpdateSignatureVerifier` reads), generates `version.json` in the
`UpdateRepository.parseUpdateInfo` schema, and uploads `version.json`,
`changelog.md` and the bundle as Release assets. Before this workflow existed a
real check failed gracefully with `UpdateServerUnavailableException`.

Signing is secret-driven and never committed: set `RELEASE_SIGNING_KEY`
(base64-encoded armored private key) + `RELEASE_SIGNING_PASSPHRASE` (and
optionally `RELEASE_SIGNING_KEY_ID`) to sign; the public key is then published as
`public-key.asc` so it can be bundled as the client keyring
(`update.signature.public.key.resource`) to turn on `update.signature.enabled`.
Without those secrets the release is published unsigned (`version.json` omits
`signatureUrl` / `signingKeyId`) and the default checksum-only verification
applies.

**Remaining gap:** `UpdateInstaller` replaces a *single* running JAR
(`JarLocator.getCurrentJarPath()`) — the single-jar model this module was ported
from. lg3d is a multi-jar desktop launched from a classpath, so the in-app
*apply* step is not yet wired to the bundle; `update.auto.download` /
`update.auto.install` default to `false`, and the check → download → verify path
is what the published metadata currently exercises.

## Build & test

All Gradle invocations must run on the JDK 21 toolchain (Gradle 8.14 cannot run
on Java 25+):

```bash
export JAVA_HOME=/home/fedora/.jdks/jdk-21.0.12.1+1
./gradlew :update-manager:test          # 308 tests, headless
./gradlew :update-manager:build         # jar + checkstyle (report-only) + jacoco
```

## Conventions & gotchas

- Tests run with `java.awt.headless=true`; the notifier and dialogs are
  headless-safe (`resolveSystemTray()` returns null without a display).
- The `test` task pins `lg3d.version=0.0.0` as a system property.
  `ApplicationVersion.current()` honours that forced property first, which keeps
  the integration-test fixtures (versions `0.5.2` / `1.0.0`) *newer* than the
  running version; without it the generated `version.properties` would report
  `1.9.0-dev` and the "update available" assertions would fail.
- Lombok is a compile-time-only dependency; it is not needed at runtime and is
  not placed on the desktop run classpath.
- PIT mutation testing is configured report-only (`mutationThreshold = 0`,
  `avoidCallsTo = ['java.awt', 'javax.swing']`) for parity with the other modules.
