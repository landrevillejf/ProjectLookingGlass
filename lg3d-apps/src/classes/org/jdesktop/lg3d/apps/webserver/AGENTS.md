# Web Server Console (`org.jdesktop.lg3d.apps.webserver`)

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md)
> · the server it drives: [`lg3d-webserver`](../../../../../../../../lg3d-webserver/AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** developer utility (management console for `lg3d-webserver`) |
| Entry point | `WebServerConsole.main` → `TitledSwingWindow.show(...)` |
| Surface | **SwingNode-in-Frame3D** (hosts `WebServerConsolePanel` on a `SwingNode` quad); the 2D desktop wraps the same panel in `WebServerPanel` |
| Start-menu name / group | Web Server Console / **Developers** |
| Command | `java org.jdesktop.lg3d.apps.webserver.WebServerConsole` |
| Descriptor | `src/config/webserver.lgcfg` → `config/demo` |
| Talks to | `lg3d-webserver` over HTTP (management REST API) — **no compile dependency** |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **WebServerConsole** — thin 3D entry point; installs the hosted look and feel
  and shows `WebServerConsolePanel` in a `TitledSwingWindow`
  (`WIDTH_PX` × `HEIGHT_PX`, 1024 × 768).
- **WebServerConsolePanel** — the main plain `JPanel` (no-arg constructor, no Java
  3D): a toolbar (Connect / Start local / Stop / Refresh), a status header, and
  three tabs — **Overview** (server info + JVM metrics), **Applications**
  (deployed-app table + an inline Deploy form + Start / Stop / Undeploy / Logs)
  and **Logs** (a tail of the selected app). Every HTTP call runs on a background
  executor and lands back on the EDT; a server that is down or a bootJar that is
  missing degrades to a readable status line, never a throw or a modal dialog.
- **WebServerPanel** — the thin 2D host shim the registry maps to: wraps
  `WebServerConsolePanel` in `try { } catch (RuntimeException | LinkageError)`,
  falls back to an "unavailable" pane, exposes `getMainPanel()` + `dispose()`.
- **ManagementClient** — the AWT-free REST client: JDK `java.net.http.HttpClient`
  + Jackson over the server's `/api/server/*` and `/api/apps` surface, with an
  injectable `Transport` seam so the tests drive it against an in-JVM
  `com.sun.net.httpserver` fake. Non-2xx answers become a `ManagementException`
  carrying the server's own `{"error": ...}` sentence.
- **ServerProcessSupervisor** — forks `java -jar <bootJar> --server.port=<p>` as a
  child (`ProcessBuilder` + `ProcessHandle`), resolving the jar from
  `-Dwebserver.jar` → `<lg.appcodebase>/libs/lg3d-webserver-*-app.jar` →
  `<root>/lg3d-webserver/build-gradle/libs/*-app.jar` → "unavailable". Split for
  testability behind an injectable `Spawner` seam, exactly like the Wonderland
  server launcher; it never throws.
- **dto/\*** — Jackson-bound records (`HealthView`, `ServerInfoView`,
  `MetricsView`, `AppView`, `LogsView`, `DeployRequest`) that restate the server's
  wire shape client-side. They are deliberately **not** the server's own records:
  the console never imports a server class, so Spring never enters the desktop JVM.

## Roles

- **Architect** — This app is the *client* half of a two-process design. The
  Spring-carrying server lives in its own `lg3d-webserver` module and is built as
  an executable `bootJar` that is **deliberately absent from the desktop
  classpath** (the `wonderland-server` precedent). `lg3d-apps` does **not** depend
  on `lg3d-webserver`; the console only needs the bootJar *path* to fork it and
  the server *URL* to drive it. The single external coupling is Jackson (already
  an `lg3d-apps` dependency) and the JDK HTTP client. There is no Java 3D here.
- **Engineer / Developer** — Keep the `WebServerConsolePanel` constructors
  **non-throwing** and keep every network/process call **off the EDT** (the
  background executor) and **non-throwing** (degrade to the status line). Never
  add a server-module import or put the bootJar on the desktop classpath — that
  would pull Spring into the desktop JVM and defeat the whole design. Follow the
  core UI/UX rulebook for the SwingNode host: hosted LAF via
  `installHostedLookAndFeel`, no modal dialogs escaping the capture (the Deploy
  form is inline, not a `JOptionPane`), EDT hops for every UI update, `dispose()`
  on discard (it stops a child this console forked and shuts the executor down).
- **QA** — `WebServerConsolePanelTest` (headless) asserts the no-arg and
  project-root constructors build without throwing, the tabs lay out, and
  `dispose()`/`refresh()` do not throw with no server up; `ManagementClientTest`
  drives the real transport against an in-JVM `com.sun.net.httpserver` fake plus
  the pure parse/error-mapping methods; `ServerProcessSupervisorTest` proves the
  jar-resolution order and start/stop through a fake `Spawner` (no real fork);
  the `dto` round-trip tests pin the wire shape. `java.awt.headless=true` keeps it
  CI-safe with no display, no server and no docker daemon. Live behaviour needs a
  running server: `./gradlew :lg3d-webserver:bootRun`, then Connect; verify the
  hosted window renders and a local Start/Stop forks and reaps the child, with an
  in-JVM probe + internal screencapture (`lg3d-core/lgscreen-*.png`). A black host
  capture under Wayland is not a defect.
- **Business Analyst** — A developer utility: run and supervise the Looking Glass
  web server and the containerized (or jar) Spring Boot apps it deploys, from
  inside the desktop, without a separate tool or a terminal. Value = a trustworthy
  console that is honest about what the server can actually do right now (docker
  reachable or not, app running or failed) and never fakes a "connected" state.
- **Functional Analyst** — Spec this app as the *management-console contract*:
  which endpoints it drives, which lifecycle verbs it exposes (deploy / start /
  stop / undeploy / logs), which start-menu slot (Developers group), and the
  degradation rules (server down, bootJar missing, deploy refused). The console
  surfaces the server's own readable error sentence; it never swallows it into a
  bare status code and never invents success.
- **Project Manager** — Commit scope `lg3d-apps` for the console; adding it also
  touches `lg3d-core` (the `Desktop2DAppRegistry` panel mapping, the start-menu
  icon, and the `:run`/`releaseBundle` `-Dwebserver.jar` wiring) — call that out.
  Done = build + headless tests + `./run-lg3d.sh` showing **Web Server Console**
  under *Developers* in both desktops + capture/log evidence against a `bootRun`
  server. Branch → PR against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame + transparency
  ordering. 2D: the `WebServerPanel` shim hosting the very same
  `WebServerConsolePanel` as an MDI internal frame — keep it identical across both
  surfaces. Status feedback is a single line under the toolbar; long operations
  run in the background and update it on completion, so the UI never blocks.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`, with the server's own contract in `lg3d-webserver/AGENTS.md`.
On conflict the higher file wins; fix here in the same PR. Every PR states the
surface (SwingNode host), the no-Spring-on-the-desktop-classpath constraint, and
the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump. Stage only intended paths (never `git add -A`).
