# Changelog

All notable changes to Webserver are documented here.

## [Unreleased]

### Added

- **The Web Server Console — run and supervise that server and its deployed apps from inside the desktop** — The frontend half: a new `lg3d-apps` start-menu app
  (`org.jdesktop.lg3d.apps.webserver`, **Developers** group) that **forks the
  `lg3d-webserver` `bootJar` as a child process** (or attaches to one already
  running) and drives it over the management REST API with the JDK `HttpClient` +
  Jackson — the `WonderlandWorldPanel` / `swing-ide` precedent. `lg3d-apps` does
  **not** depend on `lg3d-webserver`: the console needs only the `bootJar` **path** to fork it and the server **URL** to talk to it, so **Spring
  never enters the desktop JVM**. `WebServerConsole` is the thin 3D entry point (a
  `TitledSwingWindow` hosting the panel on a `SwingNode`, 1024×768);
  `WebServerConsolePanel` is the main plain `JPanel` — a toolbar (Connect / Start
  local / Stop / Refresh), a status header and three tabs (**Overview** server info +
  JVM metrics, **Applications** a deployed-app table + an inline Deploy form +
  Start/Stop/Undeploy/Logs, **Logs** a tail) — with every HTTP/process call on a
  background executor landing back on the EDT and constructors that **never throw**;
  `WebServerPanel` is the 2D host shim the registry maps to. `ManagementClient` is the
  AWT-free REST client (an injectable `Transport` seam; a non-2xx becomes a
  `ManagementException` carrying the server's own `{"error": …}` sentence) and
  `ServerProcessSupervisor` forks `java -jar <bootJar> --server.port=<p>` behind an
  injectable `Spawner`, resolving the jar from `-Dwebserver.jar` →
  `<lg.appcodebase>/libs/lg3d-webserver-*-app.jar` →
  `lg3d-webserver/build-gradle/libs/*-app.jar` → “unavailable” (the panel degrades to
  a readable status line, never a throw or a modal). The `dto/*` records mirror the server's wire shape client-side so the console never
  imports a server class. `lg3d-core` wiring: `Desktop2DAppRegistry` maps the command
  to the hosted `WebServerPanel` for both desktops, a new
  `webserver.png` start-menu icon (drawn by `lg3d-art`'s `GenerateAppIcons`), and
  `:run` / `releaseBundle` pass `-Dwebserver.jar` — the release bundle shipping the
  `bootJar` under `server/` (**not** the `lib/*` desktop-classpath wildcard). **43 new
  headless cases** (the panel's non-throwing construction + graceful degradation +
  `dispose`/`refresh`; `ManagementClient` against an in-JVM `com.sun.net.httpserver`
  fake; `ServerProcessSupervisor` jar-resolution + start/stop through a fake
  `Spawner`; `dto` round-trips) plus a
  `Desktop2DAppRegistryTest.webServerConsoleIsHostedPanel` mapping case. A new design
  note `docs/webserver.md` documents the two-process architecture. Live behaviour
  (connect, fork/stop a local child, deploy a containerized app) needs a running
  server, so it is verified with the in-JVM probe + internal screencapture rather than
  asserted here.
