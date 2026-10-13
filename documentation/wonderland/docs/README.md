# Project Wonderland — the ported collaboration platform

Welcome to the documentation for the **Project Wonderland** modernization inside
Project Looking Glass. Open Wonderland was Sun Microsystems' 3D collaborative
workspace; like the desktop itself, it is being brought back onto a current
toolchain — Gradle, JDK 21, and the repo-wide `org.jogamp.*` Java 3D / vecmath
migration — with **no** functionality silently lost.

This port proceeds in **milestones** (M0 … M7), each landed as a module-scoped
PR with its own headless test suite and a JaCoCo coverage floor. The honest rule
that governs every page here: a milestone reports what it **verified** and what
it **did not**; anything that cannot be exercised headless is documented as
deferred, never claimed as working.

## What is in scope

Wonderland ships as a set of Gradle modules that mirror the original's layering,
each kept as pure/testable as the original allowed:

| Gradle module | Owns | Can run headless? |
|---------------|------|-------------------|
| `wonderland-common` | the data model: cell states, wire messages, module metadata, WFS types, the `com.jme` shim | yes |
| `wonderland-client` | the cell / comms / view client logic | yes (renderer deferred to M6) |
| `wonderland-server` | the session/host server — **rebuilt on Spring Boot**, no GlassFish | yes |
| `wonderland-modules` | the plugin layer (`ServiceLoader`) + the feature modules + the asset/COLLADA pipeline | yes |

## Milestone map

See **[Milestones](MILESTONES.md)** for the full parity table. In one line each:

- **M0–M1** — `wonderland-common` data model + the wire-protocol codec.
- **M2a–M2f** — the client cell layer, cache, connection/session, and view/avatar
  movement, all headless.
- **M3a–M3b** — the Spring Boot session server over a loopback, then real
  port-bound sockets with the three-connections-one-session fan-out.
- **M4** — the `ServiceLoader` plugin loader and the first five feature modules
  (**[Plugin Loader](PLUGIN_LOADER.md)**).
- **M5** — the COLLADA / deployed-model / asset pipeline that **reads** a bundled
  world's 3D models (**[Asset Pipeline](ASSET_PIPELINE.md)**).
- **M6 / M7** — desktop + scene-graph integration, and the high-risk features —
  proposed, **not started**.

## Read next

- **[Architecture](ARCHITECTURE.md)** — the subsystem inventory and the target
  module layout, with the dependency direction the port must respect.
- **[Unrecoverable Tech](UNRECOVERABLE_TECH.md)** — the standing rebuild-vs-port
  decision list (GlassFish, the NetBeans module system, jME, JAXB, vecmath).
- **[Milestones](MILESTONES.md)** — per-milestone parity status, with the test
  counts and coverage floors each one shipped.

The living source of truth for the migration plan is
[`docs/wonderland-migration.md`](https://github.com/landrevillejf/ProjectLookingGlass/blob/main/docs/wonderland-migration.md)
in the repository; these pages publish that plan for readers, and the
milestone table there is kept in step with this one.
