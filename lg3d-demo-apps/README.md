# lg3d-demo-apps

The **sample and demo applications** shipped with Project Looking Glass.

> The `src/` tree is the original Project Looking Glass demo-apps source dump,
> migrated to build on a modern toolchain. See the
> [root README](../README.md) for the full port overview.

## Build status

Part of the Gradle build (JDK 21 toolchain). Ported from the 2006-era Ant build
(`build.xml`); pure Java, compiled against `lg3d-core` (which exposes the Jogamp
Java 3D API transitively).

```bash
./gradlew :lg3d-demo-apps:build   # jar -> build-gradle/libs/lg3d-demo-apps-1.0.1-dev.jar
```

The jar is placed on the desktop's classpath by the `lg3d-core:run` task, so
these apps appear on the running lg3d desktop.

## Packaging notes

- Sources live under `src/classes`.
- Resources sit alongside the sources (per-app `resources/` trees, shader
  `.frag`/`.vert` files, icons) and are bundled into the jar.
- The desktop app descriptors under `src/config` are bundled into `config/demo`,
  as the legacy build did.

## Dependencies

- `lg3d-core` (project dependency) — provides the SDK and the Jogamp Java 3D API.
