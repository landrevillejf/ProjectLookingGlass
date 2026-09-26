# AGENTS.md

## Repository Overview

Project Looking Glass (lg3d) is a modernization port of the 2006-era Sun Microsystems 3D desktop. The codebase has been migrated from Ant + Java 1.5 to Gradle 8.14 + JDK 21, with Java 3D migrated from Sun's `javax.media.j3d` to Jogamp's `org.jogamp.java3d` 1.7.2.

**Project coordinates:** `org.jdesktop.lg3d:1.22.0-dev`

## Build System

### Required Toolchain
- **JDK 21** - Pinned via Gradle toolchain. Gradle 8.14 cannot run on Java 25+.
- **Gradle 8.14** - Wrapper included ([./gradlew](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/gradlew:0:0-0:0)).

### Verified Commands

```bash
# Build all modules
./gradlew build

# Build specific module
./gradlew :lg3d-core:build

# Launch desktop in development mode (requires X display)
./gradlew :lg3d-core:run
# Or use the launcher script:
./run-lg3d.sh              # basic launch
./run-lg3d.sh -b           # with 3D background model
./run-lg3d.sh -c           # clean first
./run-lg3d.sh -r           # reassemble runtime resources

# Assemble runtime resources tree (icons, wallpapers, bgmanager configs)
./gradlew :lg3d-core:runtimeResources
```

### Build Output Locations
- Jars: `<module>/build-gradle/libs/`
- Generated sources: [lg3d-core/build-gradle/generated-src/](cci:9://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/build-gradle/generated-src:0:0-0:0)
- Runtime resources: `lg3d-core/build-gradle/runtime-resources/`

**Critical:** Gradle output uses `build-gradle/` (not `build/`) to avoid clobbering legacy per-module build scripts that remain in the tree.

## Module Structure

### Built Modules (in Gradle)
- `lg3d-escher` - Pure-Java X11 protocol library
- `lg3d-core` - Scene-graph / windowing / display-server SDK and desktop
- `lg3d-apps` - Production-grade desktop applications (plus a few samples/tutorials); formerly `lg3d-demo-apps`
- `lg3d-incubator` - Experimental applications (some excluded due to missing dependencies)

### Excluded from Build
- `lg3d-awt` - Custom AWT Toolkit/peer implementation (depends on JDK-internal `sun.awt.*` APIs removed after JDK 6)
- `lg3d-x11` - Native X11 foundation window system (not a Java module)
- [lg3d-docs](cci:9://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-docs:0:0-0:0) - Historical documentation (HTML/PDF)
- `lg3d-art` - Assets (wallpapers, splash art, models, GDM theme) - consumed at runtime

## Files That Must Not Be Edited Manually

### Generated Files
- `**/build-gradle/**` - All Gradle build output
- `lg3d-core/build-gradle/generated-src/**` - Generated [LgBuildInfo.java](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/build-tools/LgBuildInfo.java:0:0-0:0)

### Template Files
- [lg3d-core/build-tools/LgBuildInfo.java](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/build-tools/LgBuildInfo.java:0:0-0:0) - Contains `@TOKEN@` placeholders substituted at build time. Edit the template, not the generated output.

### Legacy Build Artifacts
- Legacy per-module `build`/`clean` scripts (e.g., [lg3d-escher/antlr-2.7.4/Makefile](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-escher/antlr-2.7.4/Makefile:0:0-0:0)) - Left in-tree for reference but not used by Gradle build.

### Historical Documentation
- `lg3d-docs/**` - Historical 2006-era documentation. Do not update.

## Java 3D Migration Rules

All source files have been migrated from the legacy Sun Java 3D packages to Jogamp:

- `javax.media.j3d.*` → `org.jogamp.java3d.*`
- `javax.vecmath.*` → `org.jogamp.vecmath.*`
- `com.sun.j3d.*` → `org.jogamp.java3d.*` (where applicable)

**When adding new code:** Use the Jogamp packages (`org.jogamp.java3d`, `org.jogamp.vecmath`), not the legacy `javax.*` packages.

## Test coverage
- 100% is mandatory with jacoco
- 0 surviving mutants is mandatory with PIT

> **Current status:** JaCoCo is now wired into the Gradle build (report-only —
> each `test` task finalizes `jacocoTestReport`) and CI runs the tests explicitly
> and publishes the coverage/JUnit reports, but PIT is still not wired and **no
> coverage or mutation gate is enforced**, so the 100% / 0-mutant targets above
> are not yet automated. The gate is intentionally deferred: the ported codebase
> is ~200k lines at ~1% core coverage, so enforcing it now would red-line every
> build. Until the gates are enforceable, scene-graph / rendering changes (which
> need a live 3D desktop) are verified with the in-JVM probe + internal
> screencapture described in
> [`lg3d-core/AGENTS.md`](lg3d-core/AGENTS.md) (*Verifying UI changes*); report
> that evidence in the PR instead of claiming untested success.

## In-Tree Replacements

The following bundled jars were dropped (binary-incompatible with Jogamp) and reimplemented in `lg3d-core/src/contrib/java`:

- `j3d-contrib-utils` → Reimplemented classes: `Math3D`, `TreeScan`/`NodeChangeProcessor` traverser, `TransparencyOrderedGroup`/`TransparencyOrderController`, `J3fLoader`
- `satin-v2.3` → `SatinGestureModule` rewritten as geometric stroke classifier

**Compatibility shims** (also in `src/contrib/java`) allow pre-existing `.j3f` files to deserialize under Jogamp:
- `javax.media.j3d.AmbientLight`
- `com.sun.j3d.utils.scenegraph.io.state.javax.media.j3d.AmbientLightState`

## Code Exclusions

The following code paths are intentionally excluded from the build (see [lg3d-core/build.gradle](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/build.gradle:0:0-0:0)):

- `org.jdesktop.lg3d.awt.*` / `awtpeer.*` - Custom AWT peer toolkit
- `sun.awt.*` - JDK-internal X11 shims
- `org.jdesktop.lg3d.displayserver.fws.x11.*` - Native X11 integration
- `org.jdesktop.lg3d.apps.x11integration.*` - X11 integration apps
- `org.jdesktop.lg3d.sg/internal/rmi/**` - Unused RMI scene-graph transport
- `org.jdesktop.lg3d.wg/internal/rmi/**` - Unused RMI scene-graph transport
- `org.jdesktop.lg3d.wg/.../j3dnodes/Ode*.java` - ODE physics (superseded by in-tree spring-damper)

**Do not attempt to restore or build these code paths** without understanding the JDK 21 compatibility constraints.

## Incubator Exclusions

The following incubator apps are excluded due to missing third-party libraries (not in repository, not on Maven Central):

- `nu/koidelab/**` (Cosmo) - Missing Jini/JavaSpaces, JGL, SATIN
- `apps/archviz3d/**` - Missing XMLBeans-generated schema docs, JavaLog
- `apps/intel3d/**` - Missing Jini
- `apps/browser/**` - Missing ICEsoft ICEbrowser, BeanShell
- `apps/browser3d/**` - Missing Jini

One app is excluded for deep core-API drift (**not** a missing library):

- `apps/wilkoaim3d/**` - targets a 2004-era core utility vocabulary that no
  longer exists (`Frame3DToFrontEvent`, `ComponentMover`, `ResilientRotateAction`,
  `NaturalMotionComponent3D/Container3D`, `ColorAlphaChangeAction`), the obsolete
  2-arg event-adapter constructors and `setTexture(String)`. Its `com.wilko`
  `jaimlib.jar` **is** bundled in `ext/` and on the classpath, so "missing AIM lib"
  was never the real blocker; the fatal one is that its AOL AIM TOC backend was
  discontinued by AOL in Dec 2017, so it could never log in even if rewritten.

> **Ported, not excluded.** `apps/luncher/**`, `apps/nlc/**`, `apps/orgchart/**`
> and `apps/jmf23D/**` once failed to compile against the current core API
> (AppLaunchAction / Pseudo3DShortcut / `FuzzyEdgePanel.setSize` / vecmath
> `Color3f(awt.Color)` drift). That drift was small and self-contained, so all four
> were **ported to the current API and now build**; their start-menu descriptors
> live in `lg3d-apps/src/config`. Always verify the live exclusion set against
> [`lg3d-incubator/build.gradle`](lg3d-incubator/build.gradle) rather than trusting
> this prose — it has been stale before.

## Runtime Resources

LG3D resolves artwork through the classpath under a top-level `resources/` prefix (e.g., `resources/images/icon/firefox-icon.png`). The `:lg3d-core:runtimeResources` task assembles this tree from:

1. `lg3d-art/src/resources` - Full wallpaper/splash/model collection
2. `lg3d-core/src/resources` - Icons, buttons, default wallpapers
3. `lg3d-incubator/src/classes/org/jdesktop/lg3d/apps/bgmanager` - BgConfig.xml and per-background directories

**When adding assets:** Place them in the appropriate source directory. The runtime resources task will assemble them automatically.

## Development Mode

The desktop runs in development mode (`lg.fws.mode=dev`) using the standard AWT/Swing toolkit in a window under the host window system. This requires:

- An X display (`DISPLAY` environment variable, defaults to `:0`)
- JDK 21 toolchain (auto-detected by [run-lg3d.sh](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/run-lg3d.sh:0:0-0:0) or pinned via Gradle)

**System properties for `:lg3d-core:run`:**
- `lg.fws.mode=dev` - Development mode
- `lg.etcdir` - Points to `lg3d-core/src/etc/`
- `lg.resourcedir` - Points to `lg3d-core/src/resources/`
- `lg.configurl` - Points to `src/etc/lg3d/lgconfig_1p_nox.xml`
- `lg.displayconfigurl` - Display configuration (default: `j3d1x1-nbfs` for full-screen, pass `-Pwindowed` for `j3d1x1` windowed)
- `lg.3dbackground=true` - Opt-in for 3D model background (pass `-Pbackground3d`)

## Testing

JUnit 5 test infrastructure exists and runs headless:
- `lg3d-core/src/test/java` and `lg3d-widgets/src/test/java` - active JUnit 5
  suites (74 tests) wired into the Gradle `test` task via `useJUnitPlatform()`.
- [lg3d-core/tests/junit/](cci:9://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/tests/junit:0:0-0:0) - legacy JUnit tree, not integrated into Gradle.
- `./gradlew build` runs the tests through the `check` task; each `test` task
  finalizes `jacocoTestReport` (report-only, no enforced threshold).

**CI workflow** ([.github/workflows/build.yml](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/.github/workflows/build.yml:0:0-0:0)):
- Runs `./gradlew test --continue` (explicit, headless test step)
- Runs `./gradlew build` (compile + jar; re-checks the now up-to-date tests)
- Runs `./gradlew :lg3d-core:runtimeResources` to validate resource assembly
- Uploads the JUnit + JaCoCo reports and the module jars as artifacts

## Dependencies

### External Dependencies
- Java 3D (Jogamp): `org.jogamp.java3d:{java3d-core,java3d-utils,vecmath}:1.7.2`
- Jogamp natives (platform-specific): `gluegen-rt:2.6.0`, `jogl-all:2.6.0`, `joal:2.6.0` with classifier (e.g., `natives-linux-amd64`)

### Internal Dependencies
- `lg3d-core` depends on `lg3d-escher`
- `lg3d-apps` depends on `lg3d-core`
- `lg3d-incubator` depends on `lg3d-core` plus bundled jars in `ext/`

## Compiler Configuration

- **Encoding:** UTF-8 (except `lg3d-escher` which uses ISO-8859-1)
- **Warnings:** Disabled (`-nowarn`) due to noisy legacy sources
- **Toolchain:** JDK 21 (pinned)
- **Jar compression:** Disabled by default (`zip64 = true`)

## Git Conventions

- No git submodules — this is a single repository (the CI workflow no longer claims otherwise)
- Branches: `master`, `main` (CI triggers on both)
- Concurrency: Newer push supersedes in-flight run
- **Single repository.** `lg3d-core`, `lg3d-apps`, `lg3d-incubator`,
  `lg3d-widgets`, `lpm-console`, `CHANGELOG.md` and `README.md` all live in
  **one** repo (verified: no `.gitmodules`, no `160000` gitlink entries).
- **Stage explicitly.** Never `git add -A` / `git add .`: the working tree holds
  untracked runtime artifacts (`lg3d-core/lgscreen-*.png`, stray downloads) that
  must stay out of commits. List the intended paths.
- **Flow:** feature branch off `main` → module-scoped Conventional Commit →
  push → PR against `main` via the `gh` CLI. Run this automatically; only pause
  for genuinely destructive/irreversible operations.
- **Version bumps are a separate chore PR** (`chore: bump version to X.Y.0-dev`)
  branched off `main` that edits exactly the four version references (see
  *Development Process*). Feature PRs add their `CHANGELOG.md` bullets under the
  current `[Unreleased]` header and do **not** touch the version, so the two
  merge cleanly in either order (merge the feature first).

## Commits Attributions / pull request

- When attributing the commits and p/r, use a standard git ‘Signed-off-by:’ trailer with the repo committer identity instead of agent attribution, create feature branch, and wait for approval before merging.

## Completion Report Format

When completing a task, report:

1. **Files modified** - List of files changed with brief description
2. **Build verification** - Whether `./gradlew build` succeeds
3. **Runtime verification** - Whether [./run-lg3d.sh](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/run-lg3d.sh:0:0-0:0) launches successfully (if applicable)
4. **Resource assembly** - Whether `./gradlew :lg3d-core:runtimeResources` succeeds (if assets changed)
5. **Module impact** - Which modules are affected
6. **Backward compatibility** - Any breaking changes to existing functionality

## Nested AGENTS.md Files

Every built module has its own role-aware `AGENTS.md`. The full index and the
shared role model live in the *Module AGENTS.md index* section below.

## Instructions Better Suited to Other Mechanisms

### Skills
- How to create new LG3D applications
- Scene-graph programming patterns
- 3D component development

### CI/CD
- Automated testing integration
- Deployment automation

### Permissions
- Access control for modifying core SDK vs. incubator apps
- Release process automation

## Questions Requiring Human Confirmation

1. **Should automated testing be added?** The repository has minimal test infrastructure and no test execution in CI.
2. **Should linters/formatters be configured?** No code quality tools (checkstyle, spotbugs, pmd, etc.) are currently configured.
3. **Should the incubator exclusions be documented differently?** The current approach excludes whole apps; a finer-grained approach might be desirable.
4. **Should there be a migration guide for adding new apps to incubator?** The current process is ad-hoc.
5. **Should the legacy build scripts be removed?** They are currently not used but left in-tree for reference.
```
---

## Evidence for Repository-Specific Instructions

### Build Commands
- **Evidence:**
  - [README.md](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/README.md:0:0-0:0) lines 56-80: Documents `./gradlew build` and [./run-lg3d.sh](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/run-lg3d.sh:0:0-0:0) usage
  - [build.gradle](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/build.gradle:0:0-0:0) lines 3-5: Root build configuration
  - [run-lg3d.sh](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/run-lg3d.sh:0:0-0:0) lines 1-113: Launcher script implementation
  - [.github/workflows/build.yml](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/.github/workflows/build.yml:0:0-0:0) lines 56-62: CI executes `./gradlew build` and `:lg3d-core:runtimeResources`

### Build Output Locations
- **Evidence:**
  - [build.gradle](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/build.gradle:0:0-0:0) line 17: `layout.buildDirectory.set(file("$projectDir/build-gradle"))`
  - [README.md](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/README.md:0:0-0:0) lines 36-38: Explains `build-gradle/` convention
  - [.gitignore](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/.gitignore:0:0-0:0) lines 8-11: Ignores `build-gradle/`

### Generated Files
- **Evidence:**
  - [lg3d-core/build.gradle](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/build.gradle:0:0-0:0) lines 40-65: `generateBuildInfo` task generates [LgBuildInfo.java](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/build-tools/LgBuildInfo.java:0:0-0:0) with token substitution
  - [lg3d-core/build-tools/LgBuildInfo.java](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/build-tools/LgBuildInfo.java:0:0-0:0) lines 27, 29-37: Template with `@TOKEN@` placeholders

### Java 3D Migration
- **Evidence:**
  - [README.md](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/README.md:0:0-0:0) lines 102-113: Documents package renaming from `javax.media.j3d` to `org.jogamp.java3d`
  - [lg3d-core/build.gradle](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/build.gradle:0:0-0:0) lines 115-128: Dependencies on Jogamp Java 3D 1.7.2
  - [CHANGELOG.md](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/CHANGELOG.md:0:0-0:0) lines 46-56: Details the migration

### In-Tree Replacements
- **Evidence:**
  - [README.md](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/README.md:0:0-0:0) lines 119-136: Explains dropped jars and in-tree replacements
  - [lg3d-core/README.md](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/README.md:0:0-0:0) lines 46-61: Lists reimplemented classes in `src/contrib/java`
  - [lg3d-core/build.gradle](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/build.gradle:0:0-0:0) lines 67-78: Source set includes `src/contrib/java`

### Code Exclusions
- **Evidence:**
  - [settings.gradle](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/settings.gradle:0:0-0:0) lines 8-21: Explains why `lg3d-awt` is excluded
  - [lg3d-core/build.gradle](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/build.gradle:0:0-0:0) lines 80-92: Source set excludes AWT toolkit, X11 integration, RMI, ODE physics
  - [README.md](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/README.md:0:0-0:0) lines 151-163: Lists intentionally excluded components

### Incubator Exclusions
- **Evidence:**
  - [lg3d-incubator/build.gradle](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-incubator/build.gradle:0:0-0:0) lines 1-39: Detailed comments on excluded apps and missing dependencies
  - [lg3d-incubator/README.md](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-incubator/README.md:0:0-0:0) lines 27-53: Tables of excluded apps with reasons

### Runtime Resources
- **Evidence:**
  - [lg3d-core/build.gradle](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/build.gradle:0:0-0:0) lines 150-189: `runtimeResources` task assembles resources tree
  - [README.md](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/README.md:0:0-0:0) lines 138-149: Explains resources/ classpath prefix requirement

### Development Mode
- **Evidence:**
  - [lg3d-core/build.gradle](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/build.gradle:0:0-0:0) lines 191-251: `run` task configuration with system properties
  - [run-lg3d.sh](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/run-lg3d.sh:0:0-0:0) lines 19-45: JDK 21 detection and DISPLAY setup
  - [README.md](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/README.md:0:0-0:0) lines 88-91: Explains dev mode uses standard AWT/Swing

### Module Structure
- **Evidence:**
  - [settings.gradle](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/settings.gradle:0:0-0:0) lines 1-6: Lists included modules
  - [README.md](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/README.md:0:0-0:0) lines 20-34: Module table with roles

### Compiler Configuration
- **Evidence:**
  - [build.gradle](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/build.gradle:0:0-0:0) lines 19-29: Toolchain, encoding, and warning configuration
  - [lg3d-escher/build.gradle](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-escher/build.gradle:0:0-0:0) lines 24-29: ISO-8859-1 encoding for Escher

### CI Workflow
- **Evidence:**
  - [.github/workflows/build.yml](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/.github/workflows/build.yml:0:0-0:0) lines 1-75: Complete CI configuration

### Dependencies
- **Evidence:**
  - [lg3d-core/build.gradle](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/build.gradle:0:0-0:0) lines 115-148: Java 3D and native dependencies
  - [lg3d-incubator/build.gradle](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-incubator/build.gradle:0:0-0:0) lines 80-86: Bundled ext jars

### Git Conventions
- **Evidence:**
  - [.github/workflows/build.yml](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/.github/workflows/build.yml:0:0-0:0) lines 7-11: Triggers on master/main branches
  - [.github/workflows/build.yml](cci:7://file:///home/fedora/Documents/ProjectLookingGlass/.github/workflows/build.yml:0:0-0:0) lines 18-20: Concurrency configuration
  - Git submodule check returned empty (no submodules)

### Testing
- **Evidence:**
  - [lg3d-core/tests/junit/](cci:9://file:///home/fedora/Documents/ProjectLookingGlass/lg3d-core/tests/junit:0:0-0:0) directory exists but contains minimal tests
  - No test execution in CI workflow

---

## Developer Documentation

UI/app-authoring guides live in [`docs/`](docs) (distinct from the historical,
do-not-edit `lg3d-docs/`):

- [`docs/lg3d-native-apps.md`](docs/lg3d-native-apps.md) - building native 3D
  apps: `Frame3D`/`Component3D`, layout, the glassy widget vocabulary, event
  adapters + actions, transparency ordering, the live-graph texture rule, and
  Start-Menu (`.lgcfg`) registration.
- [`docs/lg3d-native-apps-advanced.md`](docs/lg3d-native-apps-advanced.md) -
  advanced/full-featured native apps: the complete component reference (every
  `utils.shape` widget, `utils.action`, `utils.eventadapter`, animation classes,
  all `Cursor3D` constants, `Toolkit3D` metrics, `ModelLoader`, scene-graph
  traversers), the MVC/observable-model pattern, custom `Component3D` controls,
  `SwingNode` hosting and a pitfall table. This is the exhaustive companion to
  the guide above and the reference the UI/UX rulebook defers to.
- [`docs/swingnode.md`](docs/swingnode.md) - `SwingNode`: rendering a Swing
  `JPanel` offscreen into a texture, input forwarding, custom renderers,
  lifecycle/`dispose()`, and when to use it vs pure-3D widgets.

## Module AGENTS.md index & shared role model

Each built module ships a **role-aware `AGENTS.md`**. They all defer to this root
file for build/exclusions/commit conventions, and to
[`lg3d-core/AGENTS.md`](lg3d-core/AGENTS.md) as the canonical desktop **UI/UX
rulebook**. When a module file and this root file conflict, the root file wins.

| Module | AGENTS.md | UI/UX surface |
| --- | --- | --- |
| `lg3d-escher` | [`lg3d-escher/AGENTS.md`](lg3d-escher/AGENTS.md) | none (X11 protocol library) |
| `lg3d-core` | [`lg3d-core/AGENTS.md`](lg3d-core/AGENTS.md) | **canonical rulebook** (3D `Frame3D` + 2D `SwingNode`) |
| `lg3d-apps` | [`lg3d-apps/AGENTS.md`](lg3d-apps/AGENTS.md) | 3D + 2D |
| `lg3d-incubator` | [`lg3d-incubator/AGENTS.md`](lg3d-incubator/AGENTS.md) | 3D (native) + 2D (Swing dialogs) |
| `lg3d-widgets` | [`lg3d-widgets/AGENTS.md`](lg3d-widgets/AGENTS.md) | 3D layer + 2D Swing cards |
| `lpm-console` | [`lpm-console/AGENTS.md`](lpm-console/AGENTS.md) | 2D Swing (composited X11 client) |
| `update-manager` | [`update-manager/AGENTS.md`](update-manager/AGENTS.md) | 2D Swing |
| `db-manager` | [`db-manager/AGENTS.md`](db-manager/AGENTS.md) | 2D Swing |

**Shared role model.** Every module `AGENTS.md` uses the same fixed template so
all roles read each other's guidance coherently:

1. **Module at a glance** — purpose, packages, dependencies, build/run commands.
2. **How the roles work together** — the handoff summary.
3. **Architect** — boundaries, dependency direction, contracts, exclusions.
4. **Engineer / Developer** — the concrete do/don't coding rules.
5. **QA** — how the change is verified (headless tests, in-JVM probe,
   screencapture, coverage/mutation status).
6. **Business Analyst** — the value and who the "customer" is.
7. **Functional Analyst** — the behavioural contract and living rationale.
8. **Project Manager** — commit scope, PR flow, definition of done.
9. **UI/UX (3D & 2D)** — present for every module with a user interface; defers
   to the `lg3d-core` rulebook. Marked *Not applicable* for `lg3d-escher`.
10. **Communication & coherence** and **Commit / PR** — the single-source-of-truth
    rule and the module-scoped Conventional Commit flow.

When adding a new built module, create its `AGENTS.md` from this template and add
a row to the table above in the same PR.

**Per-application `AGENTS.md`.** Inside the two app modules, *every* application
package also ships its own role-aware `AGENTS.md` (same fixed template, condensed)
next to its sources, so each app's status, entry point, window surface, start-menu
descriptor location, runtime blockers and per-role guidance are discoverable in
place. Nested-`AGENTS.md` semantics apply: a guide at a package root governs that
whole subtree (e.g. `games/AGENTS.md` covers chess/solitaire/sudoku/tictactoe;
`orgchart/AGENTS.md` covers the four orgchart UIs). The `Status` row is the first
thing to read — it distinguishes **Production** / **Production-grade** apps from
**Sample / Tutorial**, **Dormant prototype**, and **Excluded from build** code.

| App module | Per-app guides | Location |
| --- | --- | --- |
| `lg3d-apps` | 20 apps | `lg3d-apps/src/classes/org/jdesktop/lg3d/apps/<app>/AGENTS.md` |
| `lg3d-incubator` | 35 apps/libraries (incl. excluded + framework trees) | `lg3d-incubator/src/classes/.../<app>/AGENTS.md` |

> **Naming note.** This module was renamed from the legacy `lg3d-demo-apps`: the
> apps in it are **production-grade desktop software**, not throwaway demos. Only
> the `config/demo` runtime resource path and the `Demos` start-menu group remain
> historical names — keep them as-is so lg3d-core's descriptor discovery resolves.

When adding a new app, create its per-app `AGENTS.md` from the same template in the
same PR.

---

## Instructions Better Suited to Other Mechanisms

### Skills
- **LG3D application development tutorial** - How to create new 3D applications, scene-graph programming patterns
- **3D component development** - Creating custom Component3D subclasses, animation patterns
- **Gesture system usage** - Implementing custom gesture recognizers

### Hooks
- **Pre-commit hook** - Validate that new Java files use Jogamp packages, not legacy javax.media.j3d
- **Pre-push hook** - Run `./gradlew build` to ensure changes compile

### Permissions
- **Core SDK modifications** - Require additional review for changes to `lg3d-core/src/classes/`
- **Incubator app additions** - Different permissions for adding new apps vs. modifying existing ones

### CI
- **Automated testing** - Add test execution to CI workflow
- **Integration tests** - Run desktop in headless mode with Xvfb for automated smoke tests

## Commit Convention

Commit messages must follow this format:

```
<type>(<scope>): <short subject>

[optional body explaining why and how]

[optional footer with references]
```

**Allowed types:**

| Type | Usage |
|------|-------|
| `feat` | New feature or capability |
| `fix` | Bug fix |
| `docs` | Documentation only (README, AGENTS.md, comments) |
| `style` | Formatting, indentation, no functional change |
| `refactor` | Code rewrite without behavior change |
| `test` | Adding or modifying tests |
| `chore` | Maintenance tasks (deps, CI, config) |
| `perf` | Performance optimization |

**Common scopes** — use the Gradle module name for module-scoped changes:

- Modules: `common`, `ide-core`, `ide-utils`, `project-manager`, `code-editor`, `build-system`, `debugger`, `project-explorer`, `advanced-statusbar`, `image-drawing`, `ide-ui`, `app`
- `plugin-api`: the (build-excluded) plugin API module
- `plugins`: external plugin integration / `plugins/` artifacts
- `gradle`: build scripts, wrapper, `gradle.properties`, `build.gradle`
- `config`: static analysis (`config/checkstyle/`, `config/pmd/`)
- `ci`: GitHub Actions workflows (`.github/workflows/`)
- `deps`: dependency version bumps
- `docs`: `docs/` and top-level documentation
- `agents`: `AGENTS.md` and `agents/**` role guides

**Examples:**

```
feat(debugger): add conditional breakpoints
fix(ide-ui): keep editor tabs in sync on the EDT
test(code-editor): cover Kotlin highlighting
docs(agents): reference role guides from root
chore(deps): bump FlatLaf to 3.3
```

**Rules:**

- Subject must be in **imperative** mood (e.g., "add", "fix", "update").
- Subject must not exceed **50 characters**.
- Body (if present) must be separated from subject by a blank line, and limited to **72 characters per line**.
- Issue references must be in the footer (e.g., `Fixes #123`).

##  Development Process

- **Do not commit directly to `main`** — use feature branches and open PRs.
- **PRs must include**: a clear description, issue references, and test results.
- **Before merging**: ensure all tests pass and coverage remains at 100%.
- **Breaking changes** must be discussed in an issue before implementation.
- **Update changelog**: add a new entry for the change in `CHANGELOG.md`.
- **Update Version**: the dev version string lives in exactly four places that
  must stay in sync — root `build.gradle` (`allprojects.version`, authoritative),
  the `CHANGELOG.md` `[Unreleased]` header, `README.md` *Project coordinates*,
  and this file's *Project coordinates*. Bump it in a **separate chore PR** after
  the feature PR merges (feat → minor, fix-only → patch, keep the `-dev`
  suffix); leave the legacy `lg3d-core/build.properties` `rpmbuild.version`
  untouched (Ant/rpm, not wired into Gradle).

# General Best Practices

- **Keep it simple**: avoid unnecessary complexity.
- **Follow existing patterns**: if a module uses a certain approach, new code should do the same.
- **Do not introduce unnecessary external dependencies**; centralize versions in the root `build.gradle` / `gradle.properties`.
- **Test across platforms** (Windows, macOS, Linux) when a change affects UI, file paths, or the debugger.
- **Respect Swing threading**: UI work on the EDT; never block it.
- **Code coverage**: ensure that all new code is tested and that the coverage remains at 100%.

---

## Ideal Commit Example

```
fix(debugger): resume VM when breakpoint is hit

Breakpoints suspended only the current thread, so the rest of the VM
kept running and the UI showed a stale stack. The JDI session now uses
suspendPolicy SUSPEND_ALL and resumes via the EventQueue on continue.

Also guard the step actions against a null ThreadReference to avoid a
NullPointerException when the debuggee exits mid-step.

Fixes #142
```

