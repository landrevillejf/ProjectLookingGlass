# Wonderland migration: JDK 21 + Jogamp port

This document tracks the ongoing port of **OpenWonderland** (the
`wonderland/Wonderland.jar` installer in the repo root, ~400 MB, Java 5
bytecode, class-file major version **49**) onto the same JDK 21 + Jogamp
foundation that the rest of ProjectLookingGlass uses. It is the standing
reference for what has been ported, what is left, and what will never be
ported because the underlying technology is unrecoverable and must be
rebuilt instead.

The plan this document supports lives in
[the milestone table below](#per-milestone-parity-status): **M0** is the
first PR, **M1** – **M7** are proposed, not started. Nothing beyond M0
proceeds without a fresh go-ahead.

---

## Reality check (evidence, not opinion)

- `wonderland/Wonderland.jar` is **not** a source archive: it is a 400 MB
  installer for the shipped application, and there is **no source** in the
  tree. It is `.gitignore`d and stays out of the repository.
- Application surface, measured from the nested jars:
  - `wonderland-client.jar`  ~565 classes (`jme` renderer, `cell`, `comms`,
    `assetmgr`, `hud`, `login`, `softphone`, `input`, `scenemanager`)
  - `wonderland-common.jar`  ~182 classes (the data model, cell states,
    messages, module metadata, WFS types)
  - `wonderland-wfs.jar`     ~44 classes (JAX-RS-annotated wire types)
  - 71 NetBeans-style module jars (feature plugins)
- Bundled third-party server stack (all pre-JDK 8, all unreachable today):
  **GlassFish Embedded 3.0** (~26,206 classes), **Sun Game Server (SGS)**,
  **MINA**, **winp** (JNI), **stun**.
- Only **three** external Java packages are referenced by Wonderland's own
  classes: `javax.vecmath`, `java.awt`, `javax.swing`. The Java 3D scene
  graph (`javax.media.j3d`, `com.sun.j3d`) and `com.jme` (jMonkeyEngine 1.x)
  were **fetched over Java Web Start / JNLP at launch** and are therefore
  **not in the bundle at all**.
- The `javax.vecmath` package was migrated repo-wide to
  `org.jogamp.vecmath` and `javax.media.j3d` to `org.jogamp.java3d` as part
  of the earlier Looking Glass migration; that migration is enforced by the
  `legacy-j3d.mnd` gate and does not permit `import javax.vecmath.*` or
  `import javax.media.j3d.*` in new code.
- Decompiler available and used: IntelliJ-bundled Fernflower
  (`java-decompiler.jar`, `ConsoleDecompiler`). Everything under
  `wonderland-common/src/main/java/org/jdesktop/wonderland/**` was produced
  this way and is then hand-adapted to JDK 21.

Scope is team-scale and many months. **No milestone claims global "100%
parity"**. Parity is measured per-milestone by the tests in each Gradle
module plus the in-JVM probe described in `lg3d-core/AGENTS.md`; anything
that cannot be exercised headless is reported as untested, not as working.

---

## Subsystem inventory (Wonderland.jar as extracted)

The full inventory of Wonderland's own bytecode, grouped by the Gradle
module that will end up owning each. Files counts are the shipped (nested)
class-file counts, not the number of source files in the port.

| Subsystem | Location (inside Wonderland.jar) | Classes | Target Gradle module | Notes |
|-----------|---------------------------------|---------|----------------------|-------|
| **Data model** | `wonderland-common.jar!/org/jdesktop/wonderland/common/**` | 182 | `wonderland-common` | Cell state, messages, module metadata, WFS types. Only depends on `javax.vecmath` (→ `org.jogamp.vecmath`) and JAXB. Ported in M0. |
| **jMonkeyEngine 1.x** | **NOT BUNDLED** — was JNLP | (~50 used) | `wonderland-common` (in-tree shim) | `com.jme.math`, `com.jme.bounding`, `com.jme.renderer`, `com.jme.scene.state` — reimplemented as a minimal shim on top of `org.jogamp.vecmath`, driven by what the data model actually calls. |
| **Client: cell renderer / comms / asset manager** | `wonderland-client.jar!/org/jdesktop/wonderland/client/**` | ~565 | `wonderland-client` | `cell`, `cellrenderer`, `comms`, `jme`, `input`, `hud`, `login`, `assetmgr`, `scenemanager`, `softphone`. Depends on the jME shim, `lg3d-core`, and Jogamp Java 3D. Ported M1/M2/M5/M7. |
| **Session/host server** | `wonderland-common.jar!/wonderland-server.jar` (GlassFish-based) | 26,206 (GlassFish) + SGS + MINA | `wonderland-server` (rebuilt) | **Unrecoverable**. Rebuilt from scratch in M3 as a plain JDK TCP / WebSocket session server that speaks the M1 wire protocol. No port of GlassFish/SGS/MINA. |
| **WFS (Web Frame Sharing) wire types** | `wonderland-wfs.jar!/org/jdesktop/wonderland/wfs/**` | ~44 | `wonderland-common` (data types), `wonderland-client` (JAX-RS calls) | JAX-RS-annotated; the *types* (CellDescriptor, CellList, CellPath, WorldRoot, WorldRootList, WFSRecordingList) landed in M0 (no JAX-RS dependency in the annotations Wonderland used). The JAX-RS *calls* land in M3. |
| **Feature modules** (NetBeans) | 71 nested module jars | varies | `wonderland-modules` | NetBeans Platform module layer is **unrecoverable** (no NBM toolchain, no `org.openide`). Reimplemented in M4 as a plain `ServiceLoader` / classpath plugin loader. Low-risk modules port first. |
| **Bundled worlds** | 7 `wonderland-*-world.jar` | data | loaded at M5 | Collada + texture assets. Loaded via the M5 asset pipeline (`colladaloader` / `artimport` / `assetmgr`). |
| **VoIP / softphone** | `client/softphone/**`, `voicebridge`, `stun`, `winp` | varies | `wonderland-modules` (M7, may descope) | stun (dead library), winp (JNI, retired). Rework or explicit descope with written reason. |
| **Legacy Java 3D applets + darkstar** | `darkstar`, `sas`, `sasxremwin`, `xremwin` | varies | **dropped** | Their applet / 2D-window embedding is covered by the already-migrated Jogamp stack + `SwingNode` (see [docs/swingnode.md](swingnode.md)). Dropped in M0 decision list, no reimplementation planned. |

---

## Target module layout

Follows the repo's dependency rule (see `AGENTS.md` at the root):
`lg3d-core` is the SDK, nothing imports *up* into it, and pure layers stay
testable without a display. Each Wonderland target is its own Gradle
project so the pure data-model layer keeps running headless in CI.

```
wonderland-common/     data model only; vecmath + JAXB + Reflections; no lg3d dep
wonderland-client/     cell / comms / render; depends on wonderland-common + Jogamp + lg3d-core
wonderland-server/     session / host; depends on wonderland-common; rebuilt, no GlassFish
wonderland-modules/    feature plugins; depends on wonderland-client; ServiceLoader-based
```

Every shipped Wonderland module:

- inherits `java-library` + `jacoco` + `checkstyle` + `cyclonedx` from the
  root [build.gradle](../build.gradle) `subprojects` block;
- declares a `coverageFloors` entry (raised per PR as tests accumulate);
- must not `import javax.vecmath.*`, `javax.media.j3d.*`, `com.sun.j3d.*`
  or `sun.*` — enforced by the pre-existing `legacy-j3d.mnd` gate;
- runs on the JDK 21 toolchain pinned by the root `subprojects.java` block
  (JDK 21.0.12+1 in `.jdks/`).

---

## Per-milestone parity status

**Legend:** ✅ landed & tested · 🟨 landed & compiling, tests partial · ⏳
not started · ❌ will not be ported (decision below)

| Milestone | Scope | Status | Evidence |
|-----------|-------|--------|----------|
| **M0** | Scaffold + `wonderland-common` data model + jME shim + JAXB wiring | 🟨 landed, tests seed | `./gradlew :wonderland-common:build` green; 77 unit tests across `com.jme.{math,bounding}`, `Math3DUtils`, `CellTransform`, `ChecksumList` (JAXB round-trip), `ScannedClassLoader`. JaCoCo floor [0.15, 0.15]. |
| **M1** | `client/cell` + `client/comms` message types, every codec round-trip | ⏳ | next PR |
| **M2** | `client/jme` + `cellrenderer` on Jogamp `org.jogamp.java3d` | ⏳ | probe-verified in-JVM (see `lg3d-core/AGENTS.md`) |
| **M3** | `wonderland-server`: rebuilt session server speaking M1's protocol | ⏳ | single-JVM loopback (M3a) then real sockets (M3b) |
| **M4** | Plugin/module loader + first low-risk modules (`text-chat`, `imageviewer`, `user-list`, `stickynote`, `whiteboard`) | ⏳ | ServiceLoader |
| **M5** | `colladaloader` / `artimport` / `assetmgr`; load the 7 bundled worlds | ⏳ | |
| **M6** | 2D + 3D desktop integration (`Frame3D`, `SwingNode` MDI mirror of the update-manager/db-manager pattern; `.lgcfg` start-menu descriptors) | ⏳ | |
| **M7** | High-risk features: `voicebridge` / `softphone` (VoIP), `avatar*`, `animation*`, `videoplayer`, `pdfviewer` | ⏳ | may descope per-feature with a written reason |

No milestone claims global 100% parity. Each milestone's PR reports what it
verified and what it did not, and never silently drops a test path.

---

## Unrecoverable tech: rebuild vs. port decisions

This is the standing decision list. Every entry here is a *veto-at-confirm*
item — the plan was approved with these in place, but any of them can be
reversed before its milestone lands.

| Bundled tech | Fate | Reasoning | Replacement strategy |
|--------------|------|-----------|----------------------|
| **Java Web Start / JNLP launcher** | ❌ rebuilt | JNLP removed in JDK 9; the `.jnlp` file fetched jME + Java 3D at launch. | Plain `main()` launched via `run-wonderland.sh` and a `:lg3d-core:run`-style classpath task. |
| **`javax.vecmath`** | ❌ rebuilt | Removed from JDK; house rule (`legacy-j3d.mnd`) forbids the import. | `org.jogamp.vecmath` — already the repo-wide vecmath since the LG3D migration. Wired in `wonderland-common/build.gradle`. |
| **`javax.media.j3d` + `com.sun.j3d`** | ❌ rebuilt | Never bundled in Wonderland.jar (was fetched by JNLP); removed from the JDK; house rule forbids the import. | `org.jogamp.java3d` — already the repo-wide Java 3D since the LG3D migration. |
| **`com.jme.math.{Vector3f,Quaternion,Matrix3f,Matrix4f,FastMath}`** | ❌ rebuilt | jME 1.x was fetched via JNLP at launch and is **not** in Wonderland.jar — there is no bytecode to port. | Minimal in-tree shim under `com.jme.math` implementing exactly the surface the data model uses, on top of `org.jogamp.vecmath` (for `Matrix4d`, `Quat4d`, `Vector3d`) and plain float math otherwise. |
| **`com.jme.bounding.{BoundingVolume,BoundingBox,BoundingSphere}`** | ❌ rebuilt | Same as above. | In-tree shim; the data model only uses these as *data* (centre / extents / radius, `contains`, `transform`, `clone`), never the jME collision pipeline. |
| **`com.jme.renderer.ColorRGBA`, `com.jme.scene.state.MaterialState`** | ❌ rebuilt | Same as above. | Plain value holders. |
| **`javax.xml.bind.*` (JAXB)** | ❌ rebuilt | Removed from the JDK in Java 11. | `jakarta.xml.bind:jakarta.xml.bind-api:2.3.3` (which still uses the `javax.xml.bind` package namespace) + `org.glassfish.jaxb:jaxb-runtime:2.3.9` on the runtime path. Verified by `ChecksumListTest`. |
| **`sun.misc.Service.providers(...)`** | ❌ rebuilt | Internal, removed in JDK 9. | `java.util.ServiceLoader.load(clazz, TCCL)` inside the rebuilt `ScannedClassLoader.getAll(...)`. |
| **scannotation** | ❌ rebuilt | Dead library; cannot read modern class files. | `org.reflections:reflections:0.10.2` in `ScannedClassLoader.getClasses/getInstances`, scoped to `org.jdesktop.wonderland` on the default path so the scan stays cheap. |
| **`@SuppressWarnings` + raw `Iterator` in `ScannedClassLoader`** | ⚠ preserved deliberately | `CellServerStateFactory` assigns `getInstances(...)` to `Iterator<CellState>` without a cast. Returning a parameterised `Iterator<Object>` would break the call sites. | `Iterator` raw return + `@SuppressWarnings("unchecked")` inside the loader. Documented in the class javadoc. |
| **GlassFish Embedded 3.0 (26,206 classes)** | ❌ rebuilt | Retired upstream; drags in a whole application server. | `wonderland-server` (M3) starts as a plain `java.net`/`java.nio` length-prefixed TCP (or WebSocket) server, single-user loopback first. |
| **Sun Game Server (SGS)** | ❌ rebuilt | Retired upstream; the session/host layer is the piece SGS was used for. | Folded into `wonderland-server` M3. |
| **Apache MINA** | ❌ rebuilt | Only used by SGS. | Plain NIO in `wonderland-server`, or a WebSocket library if M3b needs it. |
| **winp (native JNI)** | ❌ rebuilt | Retired; only used to enumerate processes. | `java.lang.ProcessHandle` (JDK 9+). |
| **stun** | ⏳ deferred | VoIP / softphone (M7). | Replaced by `ice4j` if M7 keeps VoIP, otherwise dropped with a written reason. |
| **darkstar + `sas`/`sasxremwin`/`xremwin`** | ❌ dropped | Their scene / applet embedding functions are covered by the already-migrated Jogamp stack plus `SwingNode` (see [docs/swingnode.md](swingnode.md)). | No reimplementation; the migration path is the SwingNode pattern used by the update-manager / db-manager modules. |
| **NetBeans Platform module system** (71 nested `modules/*.jar`, `org.openide`) | ❌ rebuilt | NBM toolchain gone; no upstream source in the bundle. | M4 reimplements as a plain classpath / `ServiceLoader` plugin loader; port low-risk modules first. |

**Deferred but not rejected** (may still be dropped at their milestone with
a written reason):

- `wonderland-common/src/main/java/org/jdesktop/wonderland/common/NetworkAddress.java`
  — only used by the GlassFish server stack; deferred to **M3**.
- `wonderland-common/src/main/java/com/sun/.../ComposerVector3f.java`
  — only referenced by `avatar`/`animation` code paths; deferred to **M7**.

---

## M0 specifics (this PR)

What actually landed in this first PR:

### New Gradle projects (registered in [settings.gradle](../settings.gradle))

- `wonderland-common` – source set compiles, tests, jar.
- `wonderland-client` – scaffold only (`build.gradle` + `package-info.java`).
- `wonderland-server` – scaffold only.
- `wonderland-modules` – scaffold only.

### `wonderland-common/src/main/java` source set (156 files)

- **146** files decompiled via Fernflower from `wonderland-common.jar` +
  `wonderland-wfs.jar`, then hand-migrated to JDK 21. The migration is:
  `sed 's/javax\.vecmath/org.jogamp.vecmath/g'` for the vecmath rename,
  plus the small number of hand edits listed under "hand fixes" below.
- **10** files are the in-tree `com.jme.*` shim, authored from scratch (see
  the javadoc on each class for the exact surface the ported data model
  needs).
- Within the 146, the 6 files under `org/jdesktop/wonderland/common/wfs/`
  (`CellDescriptor`, `CellList`, `CellPath`, `WFSRecordingList`,
  `WorldRoot`, `WorldRootList`) come from `wonderland-wfs.jar`. Their
  JAX-RS annotations (`javax.ws.rs.*`) resolved against
  `javax.ws.rs:javax.ws.rs-api` transitively in the original build, but
  they do not actually use JAX-RS client/server code paths in the data
  model, so no explicit dependency was added here.
- Excluded from M0 (deferred per decision list):
  `NetworkAddress` → M3, `ComposerVector3f` → M7.

### Hand fixes on top of the decompilation

- 11 raw-collection for-each loops (decompilation erased the generics):
  added `(java.util.Set<Map.Entry>)` / `(java.util.Set<String>)` /
  `(java.util.Collection<T>)` casts on the receiver. Sites:
  `CellPhysicsPropertiesComponentServerState` (2),
  `CellServerState` (3), `ChecksumList` (3, superseded by parameterising
  the two public fields), `ModuleJarWriter` (2), `ModuleInfo` (2).
- Four JAXB `XmlAdapter` subclasses (`JarURIAdapter`,
  `Vector3fAdapter`, `QuaternionAdapter`, `BoundingVolumeAdapter`): added
  the missing `<ValueType, BoundType>` parameters so the compiler stops
  refusing the generic `marshal`/`unmarshal` overrides.
- `Vector3f.clone()` / `Quaternion.clone()` return the **concrete** type
  (covariant override), because `CellTransform` assigns the result to a
  `Quaternion` / `Vector3f` field without a cast.
- `Quaternion.mult(q, result)` and `Vector3f.cross(v1, v2)` write into
  saved locals before touching `this`, because the callers alias `result =
  this` (jME 1.x had the same shape).
- `ScannedClassLoader` rewritten end-to-end on top of `Reflections` +
  `ServiceLoader` (see the class javadoc); returns raw `Iterator` on the
  two SPI methods to keep the existing call sites in `CellServerStateFactory`
  source-compatible.
- `ChecksumList` public fields parameterised to `LinkedList<Checksum>` /
  `Map<String, Checksum>`; its `@XmlElements` annotation given the explicit
  `type = Checksum.class` that the decompiler could not recover once the
  generics were erased. This is the change that lets the JAXB round-trip
  test actually marshal the list on JDK 21.

### Tests (`wonderland-common/src/test/java`)

- `com.jme.math.Vector3fTest` – 13 cases: statics, mutating arithmetic,
  `dot`, `cross` (right-hand rule), `length`, `normalize` (including the
  zero-vector corner), `distance`, `angleBetween`, `equals`/`hashCode`.
- `com.jme.math.QuaternionTest` – 13 cases: identity default, copy
  constructor, covariant `clone()`, `multLocal` rotation of unit axes,
  `fromAngleAxis` → `toAngleAxis` round-trip, `mult` composition,
  `normalize`, `toAngles`.
- `com.jme.math.FastMathTest` – 6 cases: `acos` / `asin` clamp their
  arguments so a rounding-overshoot returns the boundary angle rather
  than `NaN`.
- `com.jme.bounding.BoundingVolumeTest` – 10 cases: box / sphere centre,
  extents / radius, `contains`, `clone(result)`, and the
  scale → rotate → translate pipeline.
- `org.jdesktop.wonderland.common.Math3DUtilsTest` – 10 cases: the five
  `encloses(...)` overloads, `epsilonEquals` at float / `Vector3f` /
  `Quaternion`, `pointLineDistance`, `toEuler`.
- `org.jdesktop.wonderland.common.cell.CellTransformTest` – 15 cases: the
  pure-shim path (`transform(Vector3f)`, `sub`, `clone`), the vecmath
  path (`mul`, `invert`), `epsilonEquals`, `getLookAt`,
  `transform(BoundingVolume)`, `toString`.
- `org.jdesktop.wonderland.common.checksums.ChecksumListTest` – 4 cases:
  end-to-end JAXB round-trip (`encode` → XML → `decode` → lookups) and
  the `putChecksum` / `getChecksumMap` / `toHexString` helpers.
- `org.jdesktop.wonderland.common.utils.ScannedClassLoaderTest` – 6 cases:
  stable singleton, drainable iterators on empty scans, ServiceLoader +
  annotation-scan union in `getAll`, `ClassScanningError` cause wrapping.

Total: 77 tests, all green under `./gradlew :wonderland-common:test`.

### JaCoCo coverage floor

Measured after M0's tests: **17.56% line**, **17.90% branch**. Floor set
to `[line: 0.150, branch: 0.150]` in the root
[build.gradle](../build.gradle) `coverageFloors` map. Raise as M1's
protocol round-trip tests exercise the remaining 140+ ported data-model
classes.

---

## Building / running

Same toolchain as the rest of the repo (JDK 21 pinned via Gradle toolchain
resolver). From the repo root:

```sh
# headless build of the ported data model
JAVA_HOME=/path/to/jdk-21 ./gradlew :wonderland-common:build

# just the tests
JAVA_HOME=/path/to/jdk-21 ./gradlew :wonderland-common:test

# coverage verification
JAVA_HOME=/path/to/jdk-21 ./gradlew :wonderland-common:jacocoTestCoverageVerification
```

The `wonderland/` installer directory is `.gitignore`d and never committed.
Every Wonderland module uses `build-gradle/` as its output directory (see
the root `subprojects` block), so `wonderland-common/build-gradle/` holds
the JaCoCo HTML / XML reports and Checkstyle output locally.

---

## Git flow

- Feature branch off `main`, Conventional Commit per module.
- Open PR with `gh`, review, squash-merge with `--delete-branch` on both
  sides.
- **No release / tag ever without explicit approval.**
- **M0 does not unlock M1**. Each milestone PR must be re-confirmed before
  the next one starts.
