# PayloadMan (API Testing Tool) Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** launcher for an external developer tool |
| 2D entry point | `Desktop2DAppRegistry.SWING_FRAME_APPS` → `PayloadMan.main` (forks the child process; runs beside the desktop) |
| 3D entry point | `PayloadMan.main` via `AppLaunchAction` (same child-forking main) |
| Standalone | `java -jar libs/payloadman.jar` (the external fat jar itself) |
| Surface | **External child process (Topology C)** — the tool's own `JFrame` appears as an ordinary top-level window: composited over the 3D scene, beside the 2D/Swing desktop |
| Start-menu name / group | PayloadMan / **Developers** |
| Command | `java org.jdesktop.lg3d.apps.payloadman.PayloadMan` |
| Descriptor | `src/config/payloadman.lgcfg` → `config/demo` |
| Payload | External **tests-suite** project fat jar (`libs/payloadman.jar`, ~17 MB, git-ignored; built by its own Gradle `fatJar` task and fetched on demand via `:fetchPayloadManJar`) |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **PayloadMan** — the whole in-repo footprint: a `final` launcher class whose
  `main` resolves the fat jar, forks `"<java.home>/bin/java" -jar <jar>` with
  `DISPLAY` pointed at the lg3d display, drains the child's merged output on a
  daemon thread so its pipe never fills, and returns. Nothing of the tool is
  ever loaded into the desktop JVM.
- **Jar resolution** (no shell expansion — lg3d splits the command on whitespace
  and execs it directly): `payloadman.jar` system property (absolute, set by
  `:lg3d-core:run` and the release `lg3d.sh`) → `<lg.appcodebase>/libs/` →
  working-directory `libs/` → `../libs/`. When absent: log + (headed only)
  readable "unavailable" dialog, never a throw.

## Why an external child process

PayloadMan is a Swing HTTP/API workbench whose fat jar bundles unrelocated
third-party libraries (Jackson, SnakeYAML, slf4j, logback) plus the swing-ide
plugin-API jars, any of which could clash with the desktop classpath, and it
registers a JVM shutdown hook that flushes its own state on exit. Running it as a
separate JVM makes both unreachable from the desktop — the same isolation the IDE
(`apps.swingide`) and OpenAPI Contract Editor (`apps.openapieditor`) launchers
rely on. Its `MainWindow` is a `JFrame` (`DISPOSE_ON_CLOSE`), not a `JPanel`, so
the in-JVM host-shim panel topology does not apply.

## Roles

- **Architect** — Keep the launcher dependency-free (JDK + `JOptionPane` only)
  and the fat jar **off** every desktop classpath; the only wiring is the
  `payloadman.jar` system property in `lg3d-core/build.gradle` (run task,
  release `lg3d.sh`) and the jar's inclusion in `releaseBundle`. The external
  project owns its own build; in-repo we only vendor the jar path contract.
- **Engineer / Developer** — Never add tool classes to `lg3d-apps` source
  sets; changes to PayloadMan itself happen in the external tests-suite
  project, then a rebuilt fat jar (`./gradlew fatJar` →
  `build/libs/tests-suite-plugin-*-all.jar`) is copied/fetched into
  `libs/payloadman.jar`. Keep `resolveJar`/`buildCommand`/`resolveDisplay` pure
  and package-private so the headless test suite can pin them.
- **QA** — `PayloadManTest` (headless JUnit 5) pins the jar-path precedence,
  child-command shape and display selection without ever spawning a process;
  `Desktop2DAppRegistryTest` asserts the command classifies as `SWING_FRAME`.
  Manual evidence: launch from the start menu on both desktops and confirm the
  tool window appears on the lg3d display (`lgscreen-*.png` capture).
- **Business Analyst** — Brings a full API-testing workbench (HTTP request
  builder, collections, environments, auth, history, cookie jar, collection
  runner, cURL export) into the desktop's *Developers* menu with zero classpath
  risk; the "customer" is the developer persona that already gets the IDE, Git
  GUI, OpenAPI Contract Editor and Database Manager entries.
- **Functional Analyst** — The launcher contract: resolve the jar from the
  documented precedence chain, fork it on the lg3d display, keep the child's
  output flowing, and degrade to a readable message when the jar is missing.
  Tool behaviour itself is the external project's contract, not ours.
- **Project Manager** — Commit scope `lg3d-apps`; the registration also touches
  `lg3d-core` (`Desktop2DAppRegistry` + run/releaseBundle wiring), the root
  `build.gradle` (`:fetchPayloadManJar`), `.gitignore`,
  `lg3d-art/tools/GenerateAppIcons` and the icon resource — call those out.
  Done = build + headless tests + start-menu launch evidence. Branch → PR
  against `main`; never commit to `main`.
- **UI/UX (3D & 2D)** — The desktop-side surface is just the start-menu tile
  (`payloadman.png`: deep-orange glass tile with a paper-plane "send" glyph,
  generated by `GenerateAppIcons`). The tool window itself is a conventional
  Swing `JFrame` owned by the child process — do not attempt to capture or
  re-parent it into a `SwingNode`/`Frame3D`.

## Known caveats

- `libs/payloadman.jar` is **not committed** (~17 MB); without it the launcher
  shows the "unavailable" dialog. Build the external project's `fatJar` and copy
  it in, or run `:fetchPayloadManJar -PpayloadManJarUrl=<url>`.
- The child JVM is separate, so tool windows do not participate in lg3d window
  management effects (transparency ordering, 3D window animations) — they are
  ordinary native windows, exactly like the IDE's.
- The tests-suite build pins a Gradle 9 wrapper that will not run on Java 25+;
  build its fat jar with `JAVA_HOME` pointed at JDK 21.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook →
root `AGENTS.md`. On conflict the higher file wins; fix here in the same PR.
Every PR states the Topology C isolation (child process, jar off the desktop
classpath) and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version
bump. Stage only intended paths (never `git add -A`).
