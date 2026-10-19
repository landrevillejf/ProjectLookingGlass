# Changelog

All notable changes to Payloadman are documented here.

## [1.53.1] — 2026-10-06

### Added

- **PayloadMan API testing tool joins the Developers menu** — the external `tests-suite` project
  (PayloadMan), a production-grade desktop HTTP/API testing workbench (request
  builder, nested collections with JSON import/export, environments with
  `{{variable}}` resolution, bearer/basic/API-key auth, raw/form/multipart
  bodies, persistent history, cookie jar, collection runner, cURL export,
  proxy and TLS options), is now launchable from both lg3d desktops. Like the
  IDE and the OpenAPI Contract Editor it uses the **external child-process
  topology**: a dependency-free `PayloadMan` launcher resolves the tool's
  self-contained fat jar (`libs/payloadman.jar`, git-ignored, built by the
  external project's `fatJar` task and fetchable on demand via
  `:fetchPayloadManJar`) and forks it with `java -jar` on the lg3d display, so
  its own `JFrame` appears as an ordinary top-level window while its bundled
  Jackson/SnakeYAML/slf4j/logback and swing-ide plugin jars stay isolated from
  the desktop classpath. Registered in `Desktop2DAppRegistry.SWING_FRAME_APPS`
  (2D) and via `payloadman.lgcfg` in the *Developers* start-menu group (3D),
  with a purpose-drawn paper-plane `payloadman.png` icon and a `payloadman.jar`
  system property wired through `:lg3d-core:run`, the release `lg3d.sh` and
  `releaseBundle`.
