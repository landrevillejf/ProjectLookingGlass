# LFS Host Contract — `--iso` Composited Desktop (process-isolated MDI apps)

A binding addendum to [`lfs-x11-contract.md`](lfs-x11-contract.md) for the agent
that builds and provisions the **Linux From Scratch (LFS)** host. It specifies how
Project Looking Glass (lg3d) MUST be launched in **`--iso`** mode — the *combined
launch* that runs the conventional 2D Swing desktop **and** the X11 window
manager + compositor **in one JVM**, so isolated child-process applications are
composited into MDI windows and an app crash can never take the desktop down
(QNX-style process isolation).

> **Audience.** The system-provisioning agent (and any human reviewing its work).
> **Relationship to the base contract.** Everything in
> [`lfs-x11-contract.md`](lfs-x11-contract.md) §3 (Requirements) and §4
> (Prohibitions) applies unchanged — `--iso` is a *strict superset* of the
> compositor requirement, not an alternative to it. This document only adds the
> `--iso`-specific launch, evidence and pitfalls.
> **Keywords.** MUST / MUST NOT / SHOULD follow RFC 2119.

---

## 1. What `--iso` actually does (verified)

`run-lg3d.sh --iso` passes `-PcompositedDesktop` to `:lg3d-core:run`; pairing it
with `-x` (`--compositor`) passes `-Pcompositor` as well. Together they set, in
[`lg3d-core/build.gradle`](../lg3d-core/build.gradle):

| Property / flag | Set by | Value | Effect |
|---|---|---|---|
| `lg.configurl` | `-Pcompositor` | `lgconfig_1p_x_composite.xml` | loads `WinSysAWT` + `X11IntegrationModule` |
| `lg.fws.mode` | `-PcompositedDesktop` | `2d` | boots the conventional Swing 2D desktop |
| `lg3d.x11.compositor` | both | `true` | starts `X11WindowManager` + `X11Compositor` |
| `lg3d.x11.composite2d` | `-PcompositedDesktop` | `true` | opt-in to host native X11 clients as MDI windows |
| `--add-exports java.desktop/sun.awt=ALL-UNNAMED` | both | — | lets the compositor read its own window's native peer |

At runtime, in this order:

1. [`X11IntegrationModule.initialize()`](../lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/nativewindow/x11/X11IntegrationModule.java)
   reads `lg.lgserverdisplay` (**defaults to `:0` when unset**), claims
   `SubstructureRedirect` via `X11WindowManager`, then — because
   `lg3d.x11.compositor=true` — layers `X11Compositor`
   (`CompositeRedirectSubwindows`), exempts lg3d's own window, and **publishes an
   `X11CompositorSession`**.
2. [`Desktop2D.show()`](../lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/desktop2d/Desktop2D.java)
   reads `lg3d.x11.composite2d` and calls
   [`CompositedDesktopWiring.shouldInstall(optIn, sessionLive, headless)`](../lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/desktop2d/CompositedDesktopWiring.java).
   With the session live and a real display, it installs the bridge that hosts
   every redirected **native X11 client as an MDI `Desktop2DWindow`** inside the
   2D desktop.

The isolation guarantee follows from the topology: each such client is a
**separate OS process** and a **separate X client** of the display lg3d owns. A
`SIGSEGV`/OOM in an app kills that X client, not the desktop JVM — the WM merely
sees the window unmap.

## 2. Prerequisites (normative — inherits the base contract)

The host MUST satisfy [`lfs-x11-contract.md`](lfs-x11-contract.md) §3 in full:

- **Bare Xorg on `:0`**, no display manager, **no other WM/compositor**, lg3d is
  the X session (§3.1). Wayland/XWayland MUST NOT be the session (§4) — `--iso`
  needs a single redirectable X root exactly like `-x` does.
- The six X extensions — **Composite, DAMAGE, XFIXES, XTEST, SHAPE, MIT-SHM**
  (§3.2).
- **Mesa GLX + DRI3** hardware GL (§3.3). `--iso` still boots the Java 3D/JOGL
  pipeline (see the pitfall in §5.3), so software-only GL is not a target.
- **JDK 21** with `JAVA_HOME` set (§3.5). `run-lg3d.sh` honours a caller-provided
  `JAVA_HOME`; its hard-coded default path will not exist on the LFS host.

## 3. Launch (normative — the exact command)

lg3d MUST be started as the X session by `xinit` (or equivalent), which starts
Xorg on `:0`, exports `DISPLAY=:0`, and launches the client:

```ini
# /etc/systemd/system/lg3d-compositor.service
[Unit]
Description=Project Looking Glass as the X11 session (--iso composited 2D desktop)
After=systemd-user-sessions.service

[Service]
Environment=JAVA_HOME=/opt/jdk-21
ExecStart=/usr/bin/xinit /opt/lg3d/run-lg3d.sh --iso -x -- /usr/bin/Xorg :0 vt1 -nolisten tcp
Restart=on-failure

[Install]
WantedBy=graphical.target
```

- `/opt/lg3d` MUST be a full lg3d **source checkout** (with `gradlew`), because
  `run-lg3d.sh` wraps `./gradlew :lg3d-core:run`. This is the same assumption the
  base contract §3.6 and the README's *Deployment target (Linux From Scratch)*
  section already make.
- **Both `--iso` and `-x` MUST be present.** See §5.1 — `--iso` without `-x`
  silently disables the entire feature.
- Do **NOT** pass `--nested <display>` on the LFS host. `--nested` exists only for
  a Wayland *dev* host to point lg3d at a display it does not own the VT of, and
  it overrides `DISPLAY`. On LFS, `xinit` already owns `:0` and
  `lg.lgserverdisplay` correctly defaults to `:0`.

Any equivalent that (1) starts a bare Xorg on `:0` with lg3d as sole WM and
(2) runs `run-lg3d.sh --iso -x` against it is acceptable.

## 4. Acceptance criteria (how the agent proves `--iso` is live)

All of [`lfs-x11-contract.md`](lfs-x11-contract.md) §5 MUST hold, **plus**:

1. **Extension probe.** `./gradlew :lg3d-core:verifyX11Extensions` ends with
   `All required X extensions are available. Stage 0 verified.` and exits `0`.
2. **Sole-WM claim.** lg3d boots with **no `BadAccess`** on the root-window
   `SubstructureRedirect` claim (proves `:0` is bare Xorg with no competing WM).
3. **Session published.** With `lg.x11` logging at `FINE`, the log carries
   `Published X11CompositorSession for 2D-desktop hosting`. Its absence means the
   compositor never started (almost always §5.1 or §5.2).
4. **End-to-end isolation.** Launch an external X client from the desktop (e.g.
   `xterm`, `xeyes`): it appears **as an MDI window inside the 2D desktop** (not
   as a bare X window on the root). Then `kill -9` that client's PID: the desktop
   **survives**, and the MDI window closes. This is the whole point of `--iso` and
   MUST be demonstrated, not assumed.
5. **Generic panel-host isolation (Phase 2).** A panel app registered as a
   `CHILD_PROCESS` target is forked through the generic host
   ([`PanelHostApp`](../lg3d-apps/src/classes/org/jdesktop/lg3d/apps/host/PanelHostApp.java),
   `--panel <panelClass>`), inheriting the desktop's `lg.*` configuration as `-D`
   arguments so the child resolves resources exactly as in-JVM. Under `--iso` its
   frame is composited into an MDI window. `kill -9` the child's PID: the desktop
   **survives** and the supervisor offers a **bounded** restart (two fast-crash
   retries, then it gives up) rather than looping forever. Because a flip of a
   working in-JVM panel is a live-only decision, an app is registered `CHILD_PROCESS`
   only after this criterion is demonstrated for it on the LFS host.
6. **Graceful reaping (Phase 3).** With an isolated child open, log out of the
   desktop (`Log Out` / `exit()`): the child JVM is **terminated with the desktop**
   — `ps` afterwards shows no orphaned `java … PanelHostApp`/`WebBrowserApp`
   process. The terminated child is deliberately not restarted and raises no
   "app stopped" toast; the desktop going away is a teardown, not a crash.

Record the output of (1)–(6) as compliance evidence.

## 5. Pitfalls (each one has bitten a real launch)

### 5.1 `--iso` alone loads the wrong config — the feature is silently off
`lg.configurl` is chosen **only** by `-Pcompositor`
([`lg3d-core/build.gradle`](../lg3d-core/build.gradle): `compositor ?
'lgconfig_1p_x_composite.xml' : 'lgconfig_1p_nox.xml'`). `--iso` alone adds only
`-PcompositedDesktop`, so lg3d loads `lgconfig_1p_nox.xml`, `X11IntegrationModule`
never runs, no `X11CompositorSession` is published, `shouldInstall` sees
`sessionLive=false`, and you get an ordinary in-JVM 2D desktop with **no process
isolation and no error**. The fix is always the paired `-x`.

### 5.2 A locked or non-answering display hangs startup forever
`Main.main` calls `DesktopMode.probe()` unconditionally, which initialises
Java 3D/JOGL and forces an AWT X11 connection with **no timeout**. If `DISPLAY`
points at a display number that is locked by another server or whose socket is
stale, the JVM blocks indefinitely in `sun.awt.X11GraphicsEnvironment.initDisplay`
— it prints the startup banner (ending at the `Def. Locale …` lines) and then
never proceeds. Before launching, MUST confirm the target display actually
answers: `DISPLAY=:0 xdpyinfo | head`. On the LFS host `xinit` guarantees this;
the failure mode is a dev-host artefact of targeting a display some other server
already holds.

### 5.3 Own-window discovery failure (black screen)
The compositor reads its own window's native peer via `sun.awt` (hence the
`--add-exports`). If discovery fails on the target, pin it deterministically with
`-Dlg3d.x11.ownwindowid=<id>` (decimal or `0x` hex), per
[`GlBringUpPlanner`](../lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/nativewindow/x11/GlBringUpPlanner.java).

## 6. Known limitation (release bundle)

The release bundle's `lg3d.sh` (generated by `:lg3d-core:releaseLauncher`)
hard-codes `-Dlg.fws.mode=dev`, defaults `LG_CONFIG=lgconfig_1p_nox.xml`, and has
**no `--iso` flag** — so `--iso` is currently launchable **only** through the
source `run-lg3d.sh` + Gradle path in §3. A bundle-only host cannot express
`--iso` until `lg3d.sh` grows an equivalent switch. Provision the checkout per §3
for now; treat the bundle switch as future work.

## 7. References

- [`lfs-x11-contract.md`](lfs-x11-contract.md) — the base X11-only host contract
  (bare Xorg, extensions, Mesa/GLX, JDK 21, acceptance, in-place update).
- [`../run-lg3d.sh`](../run-lg3d.sh) — the launcher; `--iso` and `-x`/`--compositor`
  flags and the exact Gradle properties they map to.
- [`../lg3d-core/build.gradle`](../lg3d-core/build.gradle) — source of truth for
  the `compositor` / `compositedDesktop` property → system-property mapping and
  the `lgconfig` selection.
- [`../lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/nativewindow/x11/X11IntegrationModule.java`](../lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/nativewindow/x11/X11IntegrationModule.java)
  — WM/compositor boot and `X11CompositorSession.publish`.
- [`../lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/desktop2d/CompositedDesktopWiring.java`](../lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/desktop2d/CompositedDesktopWiring.java)
  — the `lg3d.x11.composite2d` opt-in and the native-window → MDI hosting wiring.
- [`../lg3d-apps/src/classes/org/jdesktop/lg3d/apps/host/PanelHostApp.java`](../lg3d-apps/src/classes/org/jdesktop/lg3d/apps/host/PanelHostApp.java)
  — the generic isolated-panel host child main (Phase 2): builds any leaf Swing
  panel reflectively in its own JVM; the scalable half of process isolation.
- [`../lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/desktop2d/AppProcessSupervisor.java`](../lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/desktop2d/AppProcessSupervisor.java)
  — the bounded restart policy and `shutdown()` reaping (Phase 3) that
  `Desktop2D.exit()` calls for every forked child.
- [`../README.md`](../README.md) — *X11 compositor mode* and *Deployment target
  (Linux From Scratch)*.
