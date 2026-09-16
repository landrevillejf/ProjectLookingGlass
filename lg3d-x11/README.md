# lg3d-x11

The **native X11 foundation window system** for Project Looking Glass — a patched
X server plus the platform binaries and install scripts that let lg3d run as a
full-screen native window system (rather than as a window inside an existing
desktop).

> This is the original Project Looking Glass content, kept for reference. See the
> [root README](../README.md) for the full port overview.

## Contents

- `linux/i686`, `linux/x86_64`, `solaris/i86pc` — prebuilt native binaries.
- `scripts/` — `lg3d-x11-build`, `lg3d-x11-install` and their `README`.

## Build status: **not part of the Gradle build**

This module is **not** a Java module and is **not** included in
[`settings.gradle`](../settings.gradle). It ships platform-native X11 server
binaries and shell installers, none of which the modern build produces or needs.

The corresponding Java-side native integration in `lg3d-core`
(`displayserver/fws/x11`, `apps/x11integration`, and the `sun.awt.X11.*` shims)
is likewise excluded, because it is bound to JDK-internal APIs removed after
JDK 6.

## How the desktop runs instead

The port runs lg3d in **development mode** (`lg.fws.mode=dev`): the 3D desktop is
drawn into an ordinary window under the host window system using the standard
AWT/Swing foundation window system (`WinSysAWT`). No native X11 server, and no
`lg3d-x11` binaries, are required.
