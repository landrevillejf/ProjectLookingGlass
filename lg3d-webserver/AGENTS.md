# lg3d-webserver

> Role-aware guide for everyone working on **lg3d-webserver**. The root
> [`../AGENTS.md`](../AGENTS.md) governs the build system, module map and commit
> conventions. This file adds module-specific guidance and a shared **per-role
> view** so all roles stay coherent.

## Module at a glance

| Item | Value |
| --- | --- |
| Purpose | A self-contained **Spring Boot (embedded Tomcat) web server** that exposes a management REST API and **deploys + supervises containerized Spring Boot applications** by shelling out to the system `docker` CLI, with a local `java -jar` backend as the daemon-free sibling. |
| Root package | `org.jdesktop.lg3d.webserver.*` — `config` (bound properties), `deploy` (registry, backends, the Docker/process seams, JSON store, domain records), `web` (REST controller, reverse-proxy + auth filters, exception advice). |
| Depends on | Spring Boot 3.5.6 (`spring-boot-starter-web` + `-validation`), Jackson, slf4j. Tests use JUnit 5, `@SpringBootTest`, MockMvc. **No `lg3d-core` dependency; Spring never enters the desktop JVM.** |
| Surfaced as | An **executable `bootJar`** that the `lg3d-apps` **"Web Server Console"** forks as a **child process** and drives over HTTP. Like `wonderland-server`, it is **deliberately absent from the desktop classpath**. It can also run containerized via its own `Dockerfile` / `docker-compose.yml`. |
| Build / test | `./gradlew :lg3d-webserver:build :lg3d-webserver:bootJar` · `:lg3d-webserver:test` (headless) · `:lg3d-webserver:bootRun` serves `GET /api/server/health` · `:lg3d-webserver:pitest` (report-only). |
| Config | `src/main/resources/application.properties`; deployment state persists as JSON under `~/.lg3d/webserver/deployments.json`, overridable with `webserver.deploy-dir` or the `lg3d.webserver.dir` system property (`DeploymentStore.DIR_PROPERTY`) for test isolation. |

## How the roles work together

This module is a **headless Spring Boot service with no Swing/Java 3D**. The
Architect guards the deployment seams (Docker CLI, child process) and the
"degrade, never 500" contract; Engineers keep the `deploy` layer free of Spring
web types and keep every external call injectable; QA runs the headless suite with
fake seams — **never a real Docker daemon, never a real fork**; the Analysts keep
the management-API and no-credential contracts explicit; the PM tracks the
desktop-integration dependency (the `lg3d-apps` console + `lg3d-core`
child-process wiring). Everyone works from this file plus the root `AGENTS.md`.

## Architect

- Preserve the **layering**: `config` (bound knobs) → `deploy` (domain records +
  registry + backends + seams + store) → `web` (HTTP). The `deploy` package holds
  no Spring *web* type; `DeploymentException` is deliberately Spring-free so the
  controller advice can map it. Lower layers never import the controller.
- Keep every external interaction behind an **injectable seam**: `ProcessLauncher`
  (run-to-completion: the `docker` CLI), `ChildProcessStarter` (long-lived: a
  forked `java -jar`), and `DockerCli`. Production wires the real impls; tests
  inject fakes. This is why CI needs **no Docker daemon and no X display**.
- **Docker is driven only through the system `docker` CLI** (`ProcessBuilder`),
  matching the desktop's Docker Manager — **no `docker-java`**. The
  `DockerDeploymentBackend` owns the exact argv (which the tests assert); the seam
  stays thin.
- The registry — not the caller — decides the **id, derived name, context path and
  host port** so two deploys can never collide on a container name or a published
  port. Keep that authority centralized.

## Engineer / Developer

- **Never 500, never leak a stack trace.** A missing daemon, a failed pull, a
  non-zero exit or an unbindable port becomes a `CommandResult.failure` /
  `DeploymentException` carrying a sentence fit to show a human. The controller
  advice maps it: `IllegalArgumentException` → 400, `NoSuchElementException` → 404,
  `DeploymentException` → 503, bean-validation → 400. With no daemon the server
  still **boots** and `/api/server/health` reports `dockerAvailable=false`.
- **No credentials are stored or logged.** The management token is config-only
  (`webserver.management-token`, blank = open on loopback), compared in constant
  time, and never written to a log or a response body.
- A Docker deploy is `docker run -d --name <id> -p <port>:<port>
  -e SERVER_PORT=<port> ...`, so the host port, the published container port and
  the app's own `server.port` coincide and the reverse proxy can reach it on
  `localhost:<port>`. The container **name is the deployment id** and the stable
  handle for every later `start`/`stop`/`logs`/`rm`/`inspect`.
- Recorded state is kept honest by reconciling any `RUNNING` app against the
  backend's live answer, both lazily (`list`/`get`) and on a timer
  (`webserver.reconcile-interval-ms`). A JAR child is tracked in memory only, so
  after a server restart it is reported stopped rather than orphaned.
- Point the state directory at a temp folder in tests via
  `DeploymentStore.DIR_PROPERTY` / `webserver.deploy-dir`; never read or write the
  developer's real `~/.lg3d/webserver` from a test.

## Child-process contract (desktop integration)

- The desktop **never loads Spring**. The `lg3d-apps` **"Web Server Console"**
  forks this module's `bootJar` as a child process (`java -jar`, resolved from the
  `-Dwebserver.jar` system property) and talks to it over the management REST API
  with the JDK `HttpClient` + Jackson — the `WonderlandWorldPanel` /
  `swing-ide` precedent.
- This module is therefore **deliberately absent from the desktop classpath**
  (the `wonderland-server` rule). `lg3d-core`'s hand-assembled `:run` /
  `releaseBundle` classpaths must **not** add this module's jar as a library — they
  only pass the **bootJar path** via `-Dwebserver.jar` so the console can fork it.
- A change to the management-API shape (a new/renamed field on `AppView`,
  `HealthView`, `ServerInfoView`, `MetricsView`, `LogsView`) must update the
  console's mirror DTOs in `lg3d-apps` **in the same PR**.

## QA

- The suite is **headless** (`java.awt.headless=true`, set by the module's test
  task). `@SpringBootTest(webEnvironment=RANDOM_PORT)` + MockMvc cover every
  management endpoint; the reverse proxy is proven against an in-JVM
  `com.sun.net.httpserver.HttpServer` stub.
- **Never require a real Docker daemon or fork a real JVM.** Inject a
  `FakeDockerCli` / fake `ProcessLauncher` to assert exact argv + stdout parsing,
  and a fake `ChildProcessStarter` to prove jar deploy/track/stop. Assert the
  **no-daemon degradation path** explicitly (health `dockerAvailable=false`; a
  Docker deploy returns a clean 4xx/503 with a readable body, never a 500).
- Isolate persistence with `@TempDir` + `webserver.deploy-dir` and assert a JSON
  round-trip. PIT is report-only (`mutationThreshold = 0`); a conservative
  `lg3d-webserver` coverage floor is pinned in the root `build.gradle`.

## Business Analyst

- Value: a **bundled web server + deployment console** — run and supervise
  containerized Spring Boot apps from the desktop, browse them under
  `/<contextPath>/...` on one port, and read health/logs/metrics — without
  installing or wiring a separate server.
- The **safety posture** is deliberate: no daemon is a graceful
  `dockerAvailable=false` (not a crash), the API is loopback-open by default but
  can require a bearer token, and no secret is ever persisted or logged.

## Functional Analyst

- Specify behaviour in terms of **user-visible function** (deploy an image or a
  jar, list, start, stop, undeploy, tail logs, read health/info/metrics, reach an
  app through the proxy) and the **failure contract** (bad input → 400, unknown id
  → 404, runtime failure → 503, always a readable body).
- Keep the **two backends** explicit: Docker (primary, containerized apps) and JAR
  (daemon-free local child). Document the read-only-grid-style scope boundaries —
  e.g. the proxy is a minimal request/response relay (no WebSocket upgrade, no
  chunked streaming) — as functional scope, not defects.

## Project Manager

- Commit scope is **`webserver`** (or `agents` for this file). Branch → commit →
  push → PR against `main`; never commit to `main`.
- This module is coupled to the desktop integration in `lg3d-apps` (the console)
  and `lg3d-core` (the child-process `-Dwebserver.jar` wiring + the 2D registry
  entry). An API-shape change must update the console DTOs in the same PR.
- Done = `:lg3d-webserver:test` green + `build` (bootJar + checkstyle report-only
  + jacoco floor) + `bootRun` serving `/api/server/health`.

## Communication & coherence

- Single source of truth: this file (module) and the root
  [`../AGENTS.md`](../AGENTS.md) (build/commits). Conflict → root wins, fix here
  in the same PR.
- Every PR states: the layer touched (config / deploy / web), whether the
  management-API shape changed (and the console DTOs were updated), whether a
  seam or the failure/degradation contract changed, and the headless-test
  evidence (including the no-daemon path).

## Commit / PR

- Conventional Commit scope **`webserver`** (or `agents` for this file).
  Imperative subject ≤ 50 chars; body wrapped at 72.
- Add a `CHANGELOG.md` bullet under `[Unreleased]`; do **not** bump the version.
- Stage only intended paths (never `git add -A`; exclude build output). Run
  branch → commit → push → PR against `main`.

---

## Overview

A self-contained **Spring Boot web server** for the desktop. It exposes a
management REST API (health/info/metrics + full CRUD and lifecycle for deployed
applications) and **deploys and supervises containerized Spring Boot apps** by
driving the system `docker` CLI, with a local `java -jar` child-process backend as
the daemon-free sibling. A minimal reverse proxy publishes each running app under
its own `/<contextPath>/**` prefix on the server's port.

It is a plain Java 21 / Spring Boot module with a Maven-standard layout
(`src/main/java`, `src/test/java`) and **no `lg3d-core` dependency**, mirroring the
`wonderland-server` standalone-module pattern. It ships as an **executable
`bootJar`** (classifier `app`) that the desktop console forks as a child process.

## Layers

- **`config`** — `WebServerProperties` (`@ConfigurationProperties("webserver")`):
  management token, deploy dir, docker/proxy toggles, public host, port range.
- **`deploy`** — domain records/enums (`Deployment`, `DeploymentRequest`,
  `AppState`, `DeploymentType`, `CommandResult`), the seams (`ProcessLauncher` +
  `RealProcessLauncher`, `ChildProcessStarter` + `RealChildProcessStarter`,
  `DockerCli` + `ProcessDockerCli`), the backends (`DeploymentBackend`,
  `DockerDeploymentBackend`, `JarProcessBackend`), `DeploymentStore` (Jackson JSON
  persistence) and `DeploymentRegistry` (the orchestrator: id/port allocation,
  lifecycle, reconciliation).
- **`web`** — `ServerManagementController` (the `/api` surface + its response
  records), `ReverseProxyFilter` (`/<contextPath>/**` relay), `ManagementAuthGate`
  (optional bearer token) and `ApiExceptionHandler` (typed failure → clean status).

## Management API

- `GET /api/server/health` — `{ status, dockerAvailable, jarAvailable,
  deployments, running }`.
- `GET /api/server/info` — `{ version, port, javaVersion, startTime, metrics }`
  (the port is read from the live web server, correct under a random test port).
- `GET /api/server/metrics` — heap/threads/load/uptime via `ManagementFactory`.
- `GET /api/apps` · `POST /api/apps` (deploy, 201) · `GET /api/apps/{id}` ·
  `GET /api/apps/{id}/logs?tail=N` · `POST /api/apps/{id}/start` ·
  `POST /api/apps/{id}/stop` · `DELETE /api/apps/{id}` (204).
- `/{contextPath}/**` — reverse-proxied to the running app's published port.

## Build & test

All Gradle invocations must run on the JDK 21 toolchain (Gradle 8.14 cannot run on
Java 25+):

```bash
export JAVA_HOME=/home/fedora/.jdks/jdk-21.0.12.1+1
./gradlew :lg3d-webserver:test       # headless; fake docker/process seams
./gradlew :lg3d-webserver:build      # bootJar + checkstyle (report-only) + jacoco
./gradlew :lg3d-webserver:bootRun    # serves GET /api/server/health on :8080
./gradlew :lg3d-webserver:pitest     # report-only mutation testing (on demand)
```

Containerized (build context is the repo root):

```bash
docker compose -f lg3d-webserver/docker-compose.yml up --build
curl http://localhost:8080/api/server/health
```

## Conventions & gotchas

- Tests run with `java.awt.headless=true` and **must not** require a real Docker
  daemon or fork a real JVM — inject the fakes.
- `DeploymentStore.DIR_PROPERTY` (`lg3d.webserver.dir`) overrides the state
  directory; tests set it (or `webserver.deploy-dir`) to a `@TempDir` so they never
  touch `~/.lg3d/webserver`.
- The reverse proxy is a **minimal relay**: no WebSocket upgrade, no chunked
  streaming, no connection pooling. It is enough to browse/exercise a
  containerized Spring Boot app; do not claim more.
- Do **not** add this module (or its bootJar) to the desktop classpath — that
  would pull Spring into the desktop JVM, defeating the child-process design. The
  console discovers the bootJar by **path** (`-Dwebserver.jar`) and forks it.
- PIT mutation testing is configured report-only (`mutationThreshold = 0`) for
  parity with the other modules.
