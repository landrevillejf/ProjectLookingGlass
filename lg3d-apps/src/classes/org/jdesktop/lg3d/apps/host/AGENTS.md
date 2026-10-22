# Generic Isolated-Panel Host

> Role-aware per-package guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## Package at a glance

| Item | Value |
| --- | --- |
| Status | **Production** isolation infrastructure (not a user-facing app) |
| Contents | `PanelHostApp` — the generic child-process `main` that hosts *any* leaf Swing panel in its own JVM |
| Invoked by | `Desktop2D.newChildSupervisor` forking `Desktop2DAppRegistry.panelHostMain()` with `panelHostArgs(panelClass)` |
| Process model | One JVM per isolated panel; QNX-style crash isolation under the combined launch (`run-lg3d.sh --iso`) |
| Build | `./gradlew :lg3d-apps:build` |

## What it is, and why it lives here

Phase 1 of the desktop's QNX-style process isolation forked exactly one *bespoke*
child — the Web Browser, which needs JavaFX/WebKit hardening
(see [`../webbrowser/AGENTS.md`](../webbrowser/AGENTS.md)). `PanelHostApp` is the
**scalable** half: a generic child `main` that isolates an *ordinary* Swing panel
with **no per-app code at all**. It reflectively builds the panel named by
`--panel <panelClass>` through
[`Desktop2DAppRegistry.createPanelByClassName`](../../../../../../../../lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/desktop2d/Desktop2DAppRegistry.java)
and shows it in its own top-level `JFrame`. Because the JVM exists solely to host
that panel, closing the frame calls `System.exit(0)`, so the desktop's
`AppProcessSupervisor` sees a clean stop and no zombie lingers.

It lives in `lg3d-apps` (not `lg3d-core`) deliberately: `lg3d-core`'s coverage
floor is tight, and only the *pure* half of this class (`parse`, `titleOf`,
`humanize`) is headless-tested — `main` and the frame assembly need a display and
are probe-verified like every other `lg3d-apps` Swing entry point. `lg3d-core`
reaches it by **class-name string** (`Desktop2DAppRegistry.panelHostMain()`), so
there is no compile-time dependency from core onto apps.

## Command line

```
PanelHostApp --panel <panelClass> [--title <name>] [--path <file>]
```

- `--panel` (required) — the fully-qualified panel class to build; a missing or
  blank value makes `main` print a diagnostic and `halt(2)` (non-zero, without
  running shutdown hooks) so the supervisor reads a failure, never an empty frame.
- `--title` (optional) — the window title; when absent one is derived from the
  class name (`humanize`: drop the package and a trailing `Panel`, split camel
  case → `org.foo.CalculatorPanel` becomes `Calculator`).
- `--path` (optional) — an initial directory/file, passed to the panel's `Path`
  constructor when it has one (else the no-arg constructor is used).

A panel that cannot be constructed (absent class, failing constructor) `halt(3)`s
rather than showing an empty frame. A default uncaught-exception handler `halt(1)`s
so an unexpected fault is a non-zero exit the supervisor can offer a bounded
restart for — mirroring `WebBrowserApp`.

## Isolating a new panel app (the recipe)

Registering an app as an isolated child is a **one-line change in the registry**,
not a change to the launcher — but it is a **live-only decision**, never a blind
flip of a working in-JVM panel. In
`Desktop2DAppRegistry`'s static block, register the descriptor's command as a
`ChildTarget` whose main is `panelHostMain()` and whose args are
`panelHostArgs(panelClass)` under `ChildProfile.SWING`. `Desktop2D.newChildSupervisor`
then derives the JVM arguments (a bounded Swing heap, the propagated `lg.*`
config), environment and restart policy from the registry automatically.

**Gate the flip on the bare-Xorg runbook** —
[`docs/lfs-iso-contract.md`](../../../../../../../../docs/lfs-iso-contract.md) §4,
criterion 5 (generic panel-host isolation) and criterion 6 (graceful reaping on
desktop exit). An app is registered `CHILD_PROCESS` only after those are
demonstrated for it on the LFS host.

## Testing

- [`PanelHostAppTest`](../../../../../../test/java/org/jdesktop/lg3d/apps/host/PanelHostAppTest.java)
  covers the pure half headlessly: `parse` (flag reading, null on no usable
  `--panel`, stray-token tolerance), `titleOf` (explicit vs derived) and
  `humanize` (package/`Panel`-suffix stripping, camel-case splitting, the bare
  `Panel` edge case).
- The isolation *contract* is asserted end-to-end by
  `lg3d-core`'s `process-isolation.feature` Cucumber scenarios (the generic
  panel-host recipe, Swing-child config inheritance, graceful reaping) and
  `AppProcessSupervisorTest` (shutdown terminates without restarting).
