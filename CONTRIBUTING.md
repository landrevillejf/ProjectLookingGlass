# Contributing to Project Looking Glass

Thanks for your interest in the lg3d modernization port — the 2006-era Sun
Project Looking Glass 3D desktop rebuilt for **Gradle 8.14 + JDK 21** with
**Jogamp Java 3D 1.7.2**. This guide covers everything needed to get a change
from a fresh clone to an open pull request.

For AI-agent-facing build rules, exclusions and UI conventions, see
[`AGENTS.md`](AGENTS.md) and [`lg3d-core/AGENTS.md`](lg3d-core/AGENTS.md). For
app-authoring guides, see [`docs/`](docs).

---

## 1. Prerequisites

- **JDK 21 (required).** The build uses a Gradle toolchain pinned to Java 21,
  and **Gradle 8.14 cannot run on Java 25+**. Point `JAVA_HOME` at a JDK 21
  install before invoking `./gradlew`, or use `./run-lg3d.sh`, which
  auto-detects/pins one. Running Gradle on a newer JDK will hang or fail.
- **An X display** to *run* the desktop (`DISPLAY`, defaults to `:0`). Building
  and running the unit tests are headless and need no display.
- **Linux x86-64** is the validated platform. Jogamp publishes natives for
  macOS/Windows and the build selects the right classifier automatically, but
  only Linux/amd64 is exercised in CI.

No manual jar installation is required: Java 3D and the JOGL/GlueGen/JOAL
natives are pulled from Maven Central on first build.

---

## 2. Build, run, test

```bash
./gradlew build                          # compile + test + jar every module
./gradlew :lg3d-core:run                 # launch the 3D desktop (needs DISPLAY)
./run-lg3d.sh                            # launcher: pins JDK 21, sets DISPLAY
./run-lg3d.sh -2                         # conventional Swing (2D) desktop
./gradlew test                           # run all unit tests explicitly
./gradlew :lg3d-core:runtimeResources    # assemble the runtime resources/ tree
```

Gradle writes to `<module>/build-gradle/` (never `build/`) so it does not
clobber the legacy per-module `build`/`clean` scripts left in the tree. Module
jars land in `<module>/build-gradle/libs/`.

### Tests and coverage

Unit tests are JUnit 5 and run headless (`java.awt.headless=true`); they cover
the Java-3D-free logic (desktop mode resolver, `.lgcfg` descriptor readers, app
registry, widget cards/layer/gallery). `./gradlew build` runs them via the
`check` task, so a failing test fails the build and CI.

Each `test` task finalizes **JaCoCo**, so coverage reports are produced under
`<module>/build-gradle/reports/jacoco/test/` (HTML + XML). Coverage is currently
**report-only** — there is no failing threshold. [`AGENTS.md`](AGENTS.md) sets
100% line/branch coverage and zero surviving PIT mutants as the eventual goal;
the ported codebase is far from that today (lg3d-core ~1%, lg3d-widgets ~40%
line coverage), so new code is expected to **raise** coverage, and changes to
already-tested code must keep its tests green. Scene-graph/rendering changes
that need a live 3D desktop are verified with the in-JVM probe + internal
screencapture described in [`lg3d-core/AGENTS.md`](lg3d-core/AGENTS.md); report
that evidence in the PR.

---

## 3. Repository layout

This is a **single repository** — despite older wording to the contrary, there
are no git submodules. Every module is tracked directly here.

| Module            | In build | Role |
| ----------------- | :------: | ---- |
| `lg3d-escher`     | ✅ | Pure-Java X11 protocol library (Escher). |
| `lg3d-core`       | ✅ | Scene-graph / windowing / display-server SDK and the desktop. |
| `lg3d-apps`  | ✅ | Production-grade desktop applications (plus a few samples); formerly `lg3d-demo-apps`. |
| `lg3d-incubator`  | ✅ | Experimental applications (some excluded — see its `build.gradle`). |
| `lg3d-widgets`    | ✅ | Desktop widget API/host and built-in widgets. |
| `lpm-console`     | ✅ | Standalone Swing front-end for the LPM package manager. |
| `lg3d-art`        | assets | Wallpapers, splash art, models, GDM theme (consumed at runtime). |
| `lg3d-awt`        | ❌ | Custom AWT Toolkit/peer impl — excluded (JDK-internal `sun.awt.*`). |
| `lg3d-x11`        | ❌ | Native X11 foundation window system — not a Java module. |
| `lg3d-docs`       | docs | Historical 2006-era documentation — **do not edit**. |

Dependency versions are centralized in the Gradle version catalog
[`gradle/libs.versions.toml`](gradle/libs.versions.toml). Bump a version there,
not in the individual module `build.gradle` files.

---

## 4. Contribution workflow

1. **Branch off `main`.** Never commit directly to `main`.
2. **Make your change**, following the conventions in §5.
3. **Build and test** locally with JDK 21 (`./gradlew build`).
4. **Add a CHANGELOG entry** under the current `[Unreleased]` header
   ([`CHANGELOG.md`](CHANGELOG.md)). Do **not** bump the version string — that
   happens in a separate chore PR after the feature merges.
5. **Commit** with a module-scoped Conventional Commit message (§6). Stage only
   the intended paths — never `git add -A`/`git add .`. The working tree holds
   untracked runtime artifacts (`lg3d-core/lgscreen-*.png`, stray downloads)
   that must stay out of commits.
6. **Push** and **open a pull request against `main`** with a clear description,
   issue references, and your build/test (or probe) evidence.

---

## 5. Coding conventions

- **Use the Jogamp Java 3D packages** in all new code: `org.jogamp.java3d.*` and
  `org.jogamp.vecmath.*`. Never reintroduce the legacy `javax.media.j3d.*`,
  `javax.vecmath.*` or `com.sun.j3d.*` imports.
- **Do not hand-edit generated files** (`**/build-gradle/**`,
  `lg3d-core/build-gradle/generated-src/**`) or template files
  (`lg3d-core/build-tools/LgBuildInfo.java` — edit the `@TOKEN@` template, not
  its generated output).
- **Respect the intentional exclusions** (AWT peers, native X11, RMI transport,
  ODE physics). Do not restore them without understanding the JDK 21
  constraints documented in [`lg3d-core/build.gradle`](lg3d-core/build.gradle).
- **Follow existing patterns.** Keep it simple; match the surrounding code's
  structure, naming and comment density. Centralize any new external dependency
  version in the catalog rather than adding a new one-off.
- **Respect Swing threading:** UI work on the EDT; never block it.
- **Runtime assets** go in the appropriate source directory (`lg3d-art`,
  `lg3d-core/src/resources`, or the incubator bgmanager tree); the
  `:lg3d-core:runtimeResources` task assembles them automatically.

---

## 6. Commit messages

Conventional Commits, imperative mood, subject ≤ 50 characters:

```
<type>(<scope>): <short subject>

[optional body: why and how, wrapped at 72 chars]

[optional footer: Fixes #123]
```

**Types:** `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `perf`.

**Scopes:** use the Gradle module name for module-scoped changes
(`lg3d-core`, `lg3d-widgets`, `lg3d-incubator`, `lg3d-apps`, `lg3d-escher`,
`lpm-console`), or `gradle` (build scripts), `ci` (workflows), `deps`
(dependency bumps), `docs`, `agents`.

Examples:

```
feat(lg3d-widgets): add a calendar widget
fix(lg3d-core): keep the taskbar on the EDT during relayout
chore(gradle): centralize dependency versions in a catalog
ci: run unit tests and publish coverage reports
```

---

## 7. Versioning

The dev version string lives in exactly four places that must stay in sync:
root `build.gradle` (`allprojects.version`, authoritative), the `CHANGELOG.md`
`[Unreleased]` header, `README.md` *Project coordinates*, and `AGENTS.md`
*Project coordinates*. It is bumped only by a **separate chore PR**
(`chore: bump version to X.Y.0-dev`) after a feature PR merges — `feat` → minor,
fix-only → patch, keeping the `-dev` suffix. Feature PRs leave it untouched.

---

## 8. Reporting security issues

Please do **not** open a public issue for a security vulnerability. See
[`SECURITY.md`](SECURITY.md) for the private disclosure process.
