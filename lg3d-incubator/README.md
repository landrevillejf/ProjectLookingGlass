# lg3d-incubator

A **grab-bag of independent experimental Project Looking Glass applications** —
the "incubator" where community and prototype apps lived.

> The `src/` tree is the original Project Looking Glass source dump, migrated to
> build on a modern toolchain. See the [root README](../README.md) for the full
> port overview.

## Build status

Part of the Gradle build (JDK 21 toolchain). The legacy Ant build was a
meta-target that ran a per-app `subant` over `src/build-scripts/build-*.xml` with
`failonerror="false"`: each app was compiled and jarred on its own and any app
that failed was silently skipped. This port compiles the whole `src/classes` tree
as one source set against `lg3d-core` plus every bundled `ext` jar.

```bash
./gradlew :lg3d-incubator:build   # jar -> build-gradle/libs/lg3d-incubator-1.0.1-dev.jar
```

The jar is placed on the desktop's classpath by the `lg3d-core:run` task. It also
supplies the **background manager** (`org.jdesktop.lg3d.apps.bgmanager`) whose
`BgConfig.xml` and per-background directories are assembled into the runtime
`resources/Backgrounds/` tree by the `lg3d-core:runtimeResources` task.

## Excluded apps

A handful of apps cannot be compiled here — the legacy `failonerror="false"`
build never produced jars for them either. They are excluded as whole
self-contained apps.

**Third-party libraries absent from the repository** (and not on Maven Central
under a compatible coordinate):

| App | Missing dependency |
| --- | --- |
| `nu/koidelab/**` (Cosmo) | Jini/JavaSpaces (`net.jini.*`), JGL (`com.objectspace.jgl`), SATIN |
| `apps/archviz3d/**` | XMLBeans-generated schema docs (`org.candc`, `org.reqarch3D`, `org.module`, `org.apache.xmlbeans`), JavaLog |
| `apps/intel3d/**` | Jini (`net.jini.*`) |
| `apps/browser/**` | ICEsoft ICEbrowser (`com.icesoft.*`), BeanShell (`bsh`) |
| `apps/browser3d/**` | Jini (`net.jini.*`) |
| `apps/wilkoaim3d/**` | the absent `com.wilko` AIM lib (and API drift, below) |

**Sources that predate the core API snapshot in this repository** (pre-existing
failures, unrelated to the JDK 21 / Jogamp migration):

| App | API drift |
| --- | --- |
| `apps/luncher/**`, `apps/nlc/**` | `AppLaunchAction` / `Pseudo3DShortcut` |
| `apps/orgchart/**` | `FuzzyEdgePanel.setSize(float,float)` |
| `apps/jmf23D/**` | vecmath `Color3f(awt.Color)` constructor |

Everything else builds against the Jogamp Java 3D API migrated across the tree.

## Dependencies

- `lg3d-core` (project dependency).
- Bundled third-party jars under `ext/` (axis, jai, jmf, jxta, log4j, prefuse,
  svgSalamander, nanoxml, jl, jpedal, mail/activation, bouncycastle, …).
