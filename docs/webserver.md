# Web Server (`lg3d-webserver`) + Web Server Console

The *Webserver* item is delivered as **two cooperating pieces** that never share a
JVM:

1. **`lg3d-webserver`** — a self-contained **Spring Boot (embedded Tomcat)** web
   server that exposes a **management REST API** and **deploys + supervises
   containerized Spring Boot applications** by shelling out to the system `docker`
   CLI, with a daemon-free local `java -jar` backend as its sibling. It builds as
   an executable **`bootJar`**.
2. **Web Server Console** — an `lg3d-apps` start-menu application
   (`org.jdesktop.lg3d.apps.webserver`), a plain **Swing** panel that **forks the
   server `bootJar` as a child process** (or attaches to one already running) and
   drives it over the management REST API with the JDK `HttpClient` + Jackson. It
   is hosted in both the 3D (`SwingNode`) and 2D (MDI) desktops.

The four locked design decisions: **both pieces ship together** · the Spring-carrying
server is **isolated in a new module** · the console reaches it as a **child process
over HTTP/CLI** · and the server **accepts containerized Spring Boot apps** (driven
through the system `docker` CLI, **no `docker-java`**, matching the desktop's Docker
Manager).

> **The one rule that explains everything else:** *Spring never enters the desktop
> JVM.* `lg3d-apps` does **not** depend on `lg3d-webserver`; the console only needs
> the `bootJar` **path** (a `-Dwebserver.jar` system property) to fork it and the
> server **URL** to talk to it. Like `wonderland-server`, `lg3d-webserver` is
> **deliberately absent from the desktop classpath**.

---

## 1. Architecture

```
+----------------------------- desktop JVM (no Spring) -----------------------------+
| lg3d-apps: org.jdesktop.lg3d.apps.webserver                                        |
|   WebServerConsole (3D) / WebServerPanel (2D shim) / WebServerConsolePanel (Swing) |
|   ManagementClient (JDK HttpClient + Jackson)   ServerProcessSupervisor (fork)     |
+-------------------|------------------------------------------|---------------------+
                    | HTTP (management REST API)               | java -jar (child proc)
                    v                                          v
+--------------------------- lg3d-webserver bootJar (own JVM) ----------------------+
| WebServerApplication (embedded Tomcat)                                             |
|   ServerManagementController -> health / info / metrics / apps CRUD / logs / lifecycle |
|   DeploymentRegistry (@Service)  ->  DeploymentStore (JSON in ~/.lg3d/webserver/)  |
|   DeploymentBackend: DockerDeploymentBackend (docker CLI) | JarProcessBackend       |
|   ReverseProxyFilter   /{contextPath}/** -> the deployed app's published port       |
|   ManagementAuthGate   optional bearer token (off by default on loopback)          |
+-------------------|----------------------------------------------------------------+
                    | docker run / ps / logs / stop / rm / stats (ProcessBuilder)
                    v   containerized Spring Boot apps (published ports)
```

Both halves are ordinary Gradle modules in the single repository build (see
[`settings.gradle`](../settings.gradle)); the console half lives inside the existing
`lg3d-apps` module, so it needs no module of its own.

---

## 2. The server: `lg3d-webserver`

Root package `org.jdesktop.lg3d.webserver.*`, in three layers. The `deploy` layer
holds **no Spring *web* type** and every external interaction sits behind an
**injectable seam**, which is why CI needs **no Docker daemon, no X display and no
real fork**.

| Layer | Contents |
| --- | --- |
| `config` | `WebServerProperties` (`@ConfigurationProperties("webserver")`) — the bound knobs below. |
| `deploy` | Domain records/enums (`Deployment`, `DeploymentRequest`, `AppState`, `DeploymentType`, `CommandResult`); the seams (`ProcessLauncher` + `RealProcessLauncher`, `ChildProcessStarter` + `RealChildProcessStarter`, `DockerCli` + `ProcessDockerCli`); the backends (`DeploymentBackend`, `DockerDeploymentBackend`, `JarProcessBackend`); `DeploymentStore` (Jackson JSON persistence); and `DeploymentRegistry` — the orchestrator that allocates the id/name/context-path/host-port, runs the lifecycle and reconciles recorded state against the live backend. |
| `web` | `ServerManagementController` (the `/api` surface), `ReverseProxyFilter` (the `/{contextPath}/**` relay), `ManagementAuthGate` (the optional bearer token) and `ApiExceptionHandler` (typed failure → clean status). |

### 2.1 Management REST API

| Method & path | Purpose |
| --- | --- |
| `GET /api/server/health` | `{ status, dockerAvailable, jarAvailable, deployments, running }` — boots and reports `dockerAvailable=false` when no daemon answers. |
| `GET /api/server/info` | `{ version, port, javaVersion, startTime, metrics }` — the port is read from the live web server, so it is correct under a random test port. |
| `GET /api/server/metrics` | Heap / threads / load / uptime via `ManagementFactory`. |
| `GET /api/apps` | List deployments (reconciled against the backend). |
| `POST /api/apps` | Deploy (Docker image or local jar) → `201`. |
| `GET /api/apps/{id}` | One deployment. |
| `GET /api/apps/{id}/logs?tail=N` | Tail the app's logs. |
| `POST /api/apps/{id}/start` · `POST /api/apps/{id}/stop` | Lifecycle. |
| `DELETE /api/apps/{id}` | Undeploy → `204`. |
| `/{contextPath}/**` | Reverse-proxied to the running app's published port. |

### 2.2 Deployment backends

- **Docker (primary).** `DockerDeploymentBackend` owns the exact `docker` argv —
  `docker run -d --name <id> -p <port>:<port> -e SERVER_PORT=<port> …` — so the
  host port, the published container port and the app's own `server.port` coincide
  and the reverse proxy can reach it on `localhost:<port>`. The **container name is
  the deployment id** and the stable handle for every later `start` / `stop` /
  `logs` / `rm` / `inspect`. Docker is driven **only** through the system CLI
  (`ProcessBuilder`), never `docker-java`.
- **Local jar (daemon-free sibling).** `JarProcessBackend` forks a `java -jar`
  child through the `ChildProcessStarter` seam, tracked in memory only, so after a
  server restart it is reported *stopped* rather than orphaned.

### 2.3 Reverse proxy, auth & persistence

- **`ReverseProxyFilter`** publishes each running app under its own
  `/{contextPath}/**` prefix on the server's port. It is a **minimal relay**: no
  WebSocket upgrade, no chunked streaming, no connection pooling — enough to browse
  and exercise a containerized Spring Boot app, and deliberately not more.
- **`ManagementAuthGate`** is an optional bearer-token filter, enabled only when
  `webserver.management-token` is non-blank (blank = open on loopback for
  development, exactly like `wonderland-server`'s login gate). The token is
  config-only, compared in constant time, and **never stored, logged or echoed**.
  The reverse-proxied app paths are not gated.
- **`DeploymentStore`** persists the deployment table as JSON under
  `~/.lg3d/webserver/deployments.json`. Reads are forgiving and writes best-effort,
  so an unwritable location degrades instead of failing. Tests point it at a
  `@TempDir` via `webserver.deploy-dir` or the `lg3d.webserver.dir` system property
  (`DeploymentStore.DIR_PROPERTY`) and never touch the developer's real profile.

### 2.4 Failure & degradation contract

**Never 500, never leak a stack trace.** A missing daemon, a failed pull, a
non-zero exit or an unbindable port becomes a `CommandResult.failure` /
`DeploymentException` carrying a sentence fit to show a human; `ApiExceptionHandler`
maps it: `IllegalArgumentException` → **400**, `NoSuchElementException` → **404**,
`DeploymentException` → **503**, bean-validation → **400**. With no daemon the
server still **boots**, `/api/server/health` reports `dockerAvailable=false`, and a
Docker deploy returns a clean 4xx/503 with a readable body.

---

## 3. The console: `org.jdesktop.lg3d.apps.webserver`

A **plain Swing** app in `lg3d-apps` — no Java 3D, no Spring, no compile dependency
on the server. Its only external couplings are Jackson (already an `lg3d-apps`
dependency) and the JDK HTTP client.

| Component | Role |
| --- | --- |
| `WebServerConsole` | Thin 3D entry point: installs the hosted look and feel and shows the panel in a `TitledSwingWindow` (1024 × 768). |
| `WebServerConsolePanel` | The main `JPanel` (no-arg, **non-throwing**): a toolbar (Connect / Start local / Stop / Refresh), a status header, and three tabs — **Overview** (server info + JVM metrics), **Applications** (deployed-app table + an inline Deploy form + Start / Stop / Undeploy / Logs) and **Logs** (a tail of the selected app). Every HTTP call runs on a background executor and lands back on the EDT. |
| `WebServerPanel` | The thin 2D host shim the registry maps to: wraps `WebServerConsolePanel` in `try { } catch (RuntimeException \| LinkageError)`, falls back to an "unavailable" pane, exposes `getMainPanel()` + `dispose()`. |
| `ManagementClient` | The AWT-free REST client (JDK `HttpClient` + Jackson) over `/api/server/*` and `/api/apps`, with an injectable `Transport` seam. A non-2xx answer becomes a `ManagementException` carrying the server's own `{"error": …}` sentence. |
| `ServerProcessSupervisor` | Forks `java -jar <bootJar> --server.port=<p>` (`ProcessBuilder` + `ProcessHandle`) behind an injectable `Spawner` seam; start / stop / isRunning; **never throws**. |
| `dto/*` | Jackson records (`HealthView`, `ServerInfoView`, `MetricsView`, `AppView`, `LogsView`, `DeployRequest`) that restate the server's wire shape client-side. They are deliberately **not** the server's own records, so the console never imports a server class. |

A server that is down or a `bootJar` that is missing **degrades to a readable status
line** — never a throw, never a modal dialog, never a faked "connected" state.

### 3.1 Discovering and forking the `bootJar`

`ServerProcessSupervisor` resolves the jar in this order, then gives up gracefully:

1. the `-Dwebserver.jar` system property (set by `:lg3d-core:run` and the release
   launcher);
2. `<lg.appcodebase>/libs/lg3d-webserver-*-app.jar`;
3. `<repo>/lg3d-webserver/build-gradle/libs/*-app.jar`;
4. otherwise **"unavailable"** — the panel says so instead of throwing.

### 3.2 Desktop wiring (`lg3d-core`)

- **2D registry** — `Desktop2DAppRegistry` maps the command
  `java org.jdesktop.lg3d.apps.webserver.WebServerConsole` to the hosted
  `WebServerPanel` (`Kind.PANEL`), so it opens as an MDI internal frame in 2D and on
  a `SwingNode` in 3D.
- **Start menu** — `lg3d-apps/src/config/webserver.lgcfg` registers **Web Server
  Console** in the **Developers** group with the icon
  `resource:///resources/images/icon/webserver.png`.
- **Child-process path** — `:lg3d-core:run` passes
  `-Dwebserver.jar=<bootJar>`, and `releaseBundle` ships the `bootJar` under the
  bundle's `server/` directory (deliberately **not** `lib/`, whose `lib/*` wildcard
  *is* the desktop classpath) with the launcher passing
  `-Dwebserver.jar="${HERE}/server/lg3d-webserver.jar"`. The module jar is never
  added as a desktop library.

---

## 4. Configuration reference

`lg3d-webserver/src/main/resources/application.properties` (every knob is
overridable with `bootRun --args='--webserver.…'`):

| Property | Default | Meaning |
| --- | --- | --- |
| `server.port` | `8080` | The management/proxy listen port (`SERVER_PORT` / `-Dserver.port`). |
| `webserver.management-token` | *(blank)* | Blank = `/api/**` open on loopback; set = require `Authorization: Bearer <token>` (401 otherwise). |
| `webserver.deploy-dir` | *(blank → `~/.lg3d/webserver`)* | Where `deployments.json` is persisted. |
| `webserver.docker-enabled` | `true` | Offer the Docker backend at all; still degrades to `dockerAvailable=false` with no daemon. |
| `webserver.proxy-enabled` | `true` | Publish running apps under `/{contextPath}/**`. |
| `webserver.public-host` | `localhost` | Cosmetic host used in a reported app URL (the proxy always connects on `localhost`). |
| `webserver.port-range.start` / `.end` | `9001` / `9099` | Inclusive host-port range auto-assigned to a deployment that names no port. |
| `webserver.reconcile-interval-ms` | `15000` | How often recorded state is reconciled against the live backend (also applied lazily on list/get). |

---

## 5. Build, run & containerize

All Gradle invocations run on the **JDK 21** toolchain (Gradle 8.14 cannot run on
Java 25+):

```bash
export JAVA_HOME=/path/to/jdk21
./gradlew :lg3d-webserver:build :lg3d-webserver:bootJar   # compile + test + bootJar
./gradlew :lg3d-webserver:bootRun                         # serves GET /api/server/health on :8080
./gradlew :lg3d-apps:build :lg3d-core:build               # the console + desktop wiring
./run-lg3d.sh                                             # Web Server Console under Developers
```

Containerized (the build context is the repo root):

```bash
docker compose -f lg3d-webserver/docker-compose.yml up --build
curl http://localhost:8080/api/server/health
```

The module ships its own multi-stage [`Dockerfile`](../lg3d-webserver/Dockerfile)
and [`docker-compose.yml`](../lg3d-webserver/docker-compose.yml) so the server
itself can run as a container, publishing `8080`.

---

## 6. Testing strategy

Everything is **headless** and **CI-safe** — no Docker daemon, no X display, no real
fork:

- **Server** — `@SpringBootTest(webEnvironment=RANDOM_PORT)` + MockMvc cover every
  management endpoint; a `FakeDockerCli` / fake `ProcessLauncher` proves the exact
  `docker` argv + stdout parsing and the **no-daemon degradation path**; a fake
  `ChildProcessStarter` proves jar deploy/track/stop; the reverse proxy is proved
  against an in-JVM `com.sun.net.httpserver` stub; a `@TempDir` proves the JSON
  persistence round-trip. A conservative `lg3d-webserver` coverage floor is pinned
  in the root [`build.gradle`](../build.gradle); PIT is report-only.
- **Console** — `WebServerConsolePanelTest` asserts the constructors build without
  throwing and `dispose()`/`refresh()` degrade with no server; `ManagementClientTest`
  drives the real transport against an in-JVM fake; `ServerProcessSupervisorTest`
  proves the jar-resolution order and start/stop through a fake `Spawner`; the `dto`
  round-trip tests pin the wire shape.
- **Live/probe (not CI)** — `bootRun` + `./run-lg3d.sh`: the console connects,
  starts/stops a local child, and deploys a containerized app where a daemon exists;
  verified with the in-JVM probe + internal screencapture. A black host capture
  under Wayland is not a defect. Real Docker is exercised manually.

---

## 7. Scope boundaries (functional, not defects)

- The reverse proxy is a **minimal request/response relay** — no WebSocket upgrade,
  no chunked streaming, no pooling.
- A **JAR** child is tracked **in memory only**, so it is reported stopped after a
  server restart rather than adopted as an orphan.
- The management token is a **loopback development gate**, not a multi-tenant
  security boundary; the proxied app paths are not gated by it.
- Docker is the primary deploy target; the local jar backend exists for parity and
  daemon-free testing.

---

## 8. See also

- Module guide: [`lg3d-webserver/AGENTS.md`](../lg3d-webserver/AGENTS.md) — the
  server's per-role contract (layering, seams, the "never 500" rule, the
  child-process contract).
- Console guide:
  [`lg3d-apps/.../webserver/AGENTS.md`](../lg3d-apps/src/classes/org/jdesktop/lg3d/apps/webserver/AGENTS.md)
  — the per-app role guide for the Swing console.
- Root conventions: [`AGENTS.md`](../AGENTS.md) (build, module map, commits) and
  [`README.md`](../README.md) (repository layout).
- The Spring Boot catalog entry lives in
  [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) (`springBoot = 3.5.6`),
  shared with `wonderland-server`.
