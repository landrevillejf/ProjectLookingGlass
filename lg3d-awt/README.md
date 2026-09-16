# lg3d-awt

An **optional custom AWT Toolkit / peer implementation** ("jawt") that let
Project Looking Glass render native 3D top-level windows and integrate 3D
lighting/shading with ordinary AWT components.

> The `src/` tree is the original Project Looking Glass source dump, kept for
> reference. See the [root README](../README.md) for the full port overview.

## Build status: **excluded from the Gradle build**

This module is intentionally **not** part of the modern build (see
[`settings.gradle`](../settings.gradle)). Its sources remain in-tree for
reference only.

Reason: the toolkit is built directly on JDK internals that no longer exist in a
usable form on a modern JDK.

- 46 of its 79 classes implement the `java.awt.peer.*` SPI, which changed
  substantially after JDK 5.
- 9 classes use JDK-internal `sun.awt.*` types (`SunToolkit`, `AppContext`,
  `CausedFocusEvent`, `image.OffScreenImage`, `X11.XToolkit`,
  `windows.WToolkit`) that are unexported in the JDK 21 `java.desktop` module.

A faithful port would mean reimplementing a JDK-internal subsystem plus matching
`--add-exports`/`--add-opens` at runtime — out of scope for this revival.

## Why the desktop still runs without it

The toolkit is only activated when the system property `lg.use3dtoolkit=true`
(default **false**) and is loaded dynamically via `java.awt.Toolkit`, so
`lg3d-core` has **no compile-time dependency** on it. The desktop runs in dev
mode (`lg.fws.mode=dev`) using the standard AWT/Swing toolkit instead.
