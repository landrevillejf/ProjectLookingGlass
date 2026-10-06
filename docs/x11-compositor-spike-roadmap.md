# X11 Compositor — Spike Report & Production Roadmap

**Status:** Stages 0–3 **complete and verified** on the dev box (compiled,
unit-tested, instrumented, one-command-away). Stage 4 (the live end-to-end
proof) is **user-driven and not yet run** — it requires a *bare Xorg* session
that the Fedora/Wayland dev box cannot provide without violating the
no-nested-X-server policy. This document is the spike report + the roadmap from
spike to production.

**Branch:** `feat/x11-compositor-spike` · **Contract:** [`lfs-x11-contract.md`](lfs-x11-contract.md)

---

## 1. Goal and strategy

Deliver a **working, one-command-away native-X11 compositor** for the LFS/Xorg
production target, proven end-to-end, plus a hardening roadmap. Two fixed
strategy choices:

- **Stock JDK 21** — no forked JDK, no `-javaagent`, no `sun.awt.*` peer
  resurrection. The compositor is pure Java over the in-tree Escher protocol
  library (`gnu.x11`), so it runs on an unmodified JDK 21.
- **End-to-end spike first** — prove *one* external X client composited as a
  `NativeWindow3D` quad with *physical* input forwarded, then roadmap the rest.
  Not full productionization in this pass.

### The load-bearing constraint

The dev box is **Fedora Wayland**; `:0` is **XWayland owned by Mutter**, so lg3d
**cannot** claim `SubstructureRedirect` there (it would get `BadAccess`, contract
§4). Project policy additionally **forbids** nested `Xephyr`/`Xvfb`, `Robot`
synthetic input, and screenshot-based verification. Consequence:

- **Stages 0–3 are fully autonomous** on the dev box (no WM claim needed).
- **Stage 4 is user-driven** on a *real* bare Xorg (the LFS target, or a spare VT).

The X11 compositor sources already existed on `main` (14 `.java` files under
`lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/nativewindow/x11/`) but had
**zero tests** and had **never** been validated end-to-end.

---

## 2. What the spike delivered

### Stage 0 — extension probe (verified, real evidence)

`./gradlew :lg3d-core:verifyX11Extensions` against `:0` exits `0` and prints
`All required X extensions are available. Stage 0 verified.` `xdpyinfo
-queryExtensions` cross-checks all six, with opcodes observed on the dev box:

| Extension | Opcode (`:0`) | Purpose in lg3d |
|---|---|---|
| Composite | 142 | redirect client windows into off-screen pixmaps |
| DAMAGE | 143 | repaint notifications for redirected windows |
| XFIXES | 138 | cursor-shape notifications + region primitives |
| XTEST | 132 | synthetic pointer/keyboard injection (input forwarding) |
| SHAPE | 129 | non-rectangular window support |
| MIT-SHM | 130 | shared-memory pixel readback (fast path) |

This proves the **Escher protocol layer** — the JDK-21 foundation of the whole
approach — negotiates all six extensions against a real modern X server. It is a
read-only probe (no WM claim), so it is safe on XWayland.

> A benign `DinReader: saw IO exception` may print on close while querying XTEST;
> it does not affect the result.

### Stage 1 — headless unit tests (31 tests, green)

New JUnit 5 tests under
`lg3d-core/src/test/java/org/jdesktop/lg3d/displayserver/nativewindow/x11/`
raise the X11 module's coverage from **0**. Each targets a *pure* seam that
needs no live `Display`:

- `X11InputForwarderKeysymTest` (13) — virtual-key → X keysym mapping
  (`vkToKeysym`, `specialVkToKeysym`) and button mapping (`mapButton`):
  letters→lowercase, digits/punctuation→ASCII, editing/navigation keys winning
  over the ASCII range, modifiers/locks, F-keys, keypad, unmapped→0.
- `X11InputForwarderCoordsTest` (9) — `mapLocalToPixel`, the 3D-pick →
  client-pixel coordinate mapping: body-centre→pixel-centre, corners, the Y-flip
  (body-local +Y is up; pixel +Y is down), edge clamping, window-origin offset,
  NaN and non-positive-geometry rejection.
- `CompositeWindowImageLoaderPixelTest` (9) — `readPixel` (byte-order-aware pixel
  assembly from an `XGetImage` reply: LSB/MSB, 1/3/4-byte) and `channel`
  (TrueColor mask extraction scaled to 8 bits: 8-8-8, 5-6-5 scale-up, wide-mask
  scale-down, zero mask).

To make these testable without changing behaviour, the following were relaxed
from `private` to package-private (or extracted as a pure static): `mapButton`,
`vkToKeysym`, `specialVkToKeysym`, `mapLocalToPixel` (new, extracted from
`computeAbsCoords`), `readPixel`, `channel`.

`./gradlew :lg3d-core:test` and `:lg3d-core:build` are **green** (compile +
117 tests + the JaCoCo coverage floor + checkstyle warnings-only).

### Stage 2 — instrumentation + minimum hardening

- **Structured logging** added to `X11InputForwarder` (logger `lg.x11.input`):
  an INFO "attached to window 0x… (XTest available/UNAVAILABLE)" line plus
  `FINE`-guarded lines for focus, motion warp, button press/release, wheel and
  key events (including the keysym-absent case). All guarded by
  `isLoggable(Level.FINE)` and add **no X round-trips**, preserving the class's
  threading contract. (`X11Compositor` and `X11WindowManager` already carried
  INFO/FINE markers — see §3.)
- **Concurrency fix** in `X11WindowAssociator.removeAllRules`: replaced a
  remove-during-for-each that could throw `ConcurrentModificationException` with
  `removeIf`. This is on the client-teardown path exercised when a composited
  client disappears.

### Stage 3 — launch wiring (one command)

- `run-lg3d.sh` gained `-n, --nested [<display>]` (default `:1`): it exports
  `DISPLAY=<display>`, implies `--compositor`, and passes
  `-Plgserverdisplay=<display>`. It **never starts an X server** — the caller
  must already have one running on that display. This closes the drift where
  contract §6 already referenced `--nested`.
- `lg3d-core/build.gradle` threads `-Plgserverdisplay=<display>` into the `run`
  task's `lg.lgserverdisplay` system property **and** the `DISPLAY` environment
  of the forked JVM. The existing `-Pcompositor` path already sets
  `lg3d.x11.compositor=true`, selects `lgconfig_1p_x_composite.xml`, and applies
  `--add-exports java.desktop/sun.awt=ALL-UNNAMED`.

Result: the live proof is **one command** on a real Xorg.

### AWT Peer (`lg3d-awt`) — retired, not resurrected

`lg3d-awt` cannot be "rewritten" onto stock JDK 21: JDK 9+ removed the
`awt.toolkit` hook that installed a custom `Toolkit`, 46 of its 79 classes
implement the `java.awt.peer.*` SPI, and 9 use unexported `sun.awt.*` internals.
It is now formally marked **retired** (it was already build-excluded) in
`settings.gradle`, the root `AGENTS.md`, and `README.md`, with the replacement
mapping:

| Former `lg3d-awt` role | Stock-JDK-21 replacement (already in-tree) |
|---|---|
| 2D-in-3D widget bridge (Swing/AWT inside the 3D scene) | `org.jdesktop.lg3d.wg.SwingNode` — lg3d's own **pure-Java** bridge that paints Swing into an offscreen `BufferedImage` and textures it onto a quad (used by `TitledSwingWindow` in `lg3d-apps`). No JavaFX, no AWT peer. |
| Foreign X11 application compositing | `org.jdesktop.lg3d.displayserver.nativewindow.x11` (this compositor) |

No code was resurrected.

---

## 3. Stage 4 — how to run the live proof

A scripted harness ships at [`scripts/x11/live-proof.sh`](../scripts/x11/live-proof.sh).
It automates the read-only criteria, orchestrates a live lg3d compositor session,
walks the operator through the interactive criteria, corroborates the human
answers against the compositor's INFO log markers, and writes a §5 evidence file.

**Hard guarantees (contract §4):** the harness **never** starts an X server (no
Xephyr/Xvfb/Xorg — it aborts if the target display's X socket is absent),
**never** injects synthetic input (no xdotool/xte/Robot — input forwarding is
proven with the operator's *physical* pointer/keyboard), and **never** takes or
depends on screenshots.

### Obtain a bare Xorg target (operator)

- **(a) Recommended fastest real proof — a spare VT on the dev box:**
  ```bash
  Xorg :1 vt2 -nolisten tcp          # a BARE local Xorg, NOT nested
  # from any terminal (observe/interact on the VT, Ctrl-Alt-F2):
  scripts/x11/live-proof.sh -d :1
  ```
- **(b) The actual LFS target** once provisioned (contract §3.1: Xorg on `:0`
  as the session client, no competing WM):
  ```bash
  scripts/x11/live-proof.sh -d :0
  ```

Use `-c xterm` instead of the default `xeyes` to also exercise keyboard
forwarding. `--probes-only` runs just criteria 1/2/4 (safe from a headless
orchestration terminal). Evidence + logs land in
`${LG3D_EVIDENCE_DIR:-/tmp/lg3d-x11-live-proof}/<run>/`.

### Dev-box smoke result (probes only, `:0`)

Criteria **1, 2, 4 PASS** on the dev box even against XWayland —
`glxinfo -B` reports `direct rendering: Yes` (AMD Radeon, Mesa 26.2.3). Criteria
**3, 5, 6 cannot pass on `:0`** because Mutter owns XWayland (lg3d would get
`BadAccess`), which is exactly why they must be run against a bare Xorg.

### §5 acceptance checklist

| # | Criterion | How the harness checks it | Automatable? |
|---|---|---|---|
| 1 | **Extension probe** (authoritative) | `verifyX11Extensions --args=<dpy>` exits 0 and prints `Stage 0 verified` | Yes |
| 2 | **xdpyinfo cross-check** | all six extensions present in `xdpyinfo -queryExtensions` | Yes |
| 3 | **Sole WM, no `BadAccess`** | poll the lg3d log for `X Window Manager initialization completed against display` (pass) vs `Failed to access root window. Another WM is running?` (fail) | Yes (marker) |
| 4 | **GLX direct rendering** | `glxinfo -B` reports `direct rendering: yes` | Yes |
| 5 | **End-to-end** | launch a real client (`xeyes`/`xterm`) with `DISPLAY=<dpy>`; operator confirms it is composited **as a `NativeWindow3D` quad** and responds to **physical** input; corroborated by the `X11 input forwarder attached to window` INFO marker | Human |
| 6 | **Key ownership** | operator presses the **real** `Alt+Tab`; confirms lg3d's switcher receives it (not swallowed by a host shell) | Human |

Record the harness's `section5-evidence.md` (plus the run's logs) in the PR as
the compliance record. Criteria 3/5/6 are the ones that genuinely require the
operator at a bare-Xorg VT.

---

## 4. Deferred work (with rationale)

The plan named two "minimum gaps" to close in Stage 2. **After reading the code,
neither blocks a single-client spike**, so both were **deferred rather than
implemented blind** (implementing WM/EWMH semantics with no live Xorg to test
against is reckless scope creep). This is a deliberate, documented deviation.

1. **`X11WindowManager` "non-override-redirect InputOnly not implemented yet"**
   (`X11WindowManager.java:710`). This branch handles *InputOnly* windows
   (event-only, no pixels). A real external client such as `xterm`/`xeyes` is an
   *InputOutput* window and never reaches it, so it does not block the spike.
   **Defer** to the multi-window/focus phase.

2. **`X11WindowManagerHints` EWMH subset** — `// TODO THE FELLOWING FUNCTIONS ARE
   NOT COMPLETLY IMPLEMENTED.` (`X11WindowManagerHints.java:158`). The
   already-present `isSupportedWinType` (`:280`) resolves a basic client to
   `_NET_WM_WINDOW_TYPE_NORMAL`, which the compositor accepts. The incomplete
   parts are the richer `_NET_WM_STATE`/`_NET_WM_ALLOWED_ACTIONS` handling needed
   for full desktop-policy behaviour, not for compositing one NORMAL window.
   **Defer** to the EWMH phase.

3. **Display-entangled test seams** not unit-tested in Stage 1. **Phase B has
   since closed most of this** (see §5): the extension reply/event decoders
   (`X11CompositeExt.OverlayReply`, `X11DamageExt.NotifyEvent`,
   `X11FixesExt.FetchRegionReply`/`CursorNotifyEvent`, `X11ShmExt.GetImageReply`)
   and `ConfigureNotifyBugFixed` are now headless-tested. The key realisation is
   that these decoders do **not** need a `gnu.x11.Display` fake after all: their
   reading constructors (`Data(byte[])`, `Event(Display,byte[],int)`,
   `ConfigureNotify(Display,byte[])`) only *store* the display and parse purely
   from the buffer, so a `null` display plus a synthetic buffer written through
   `Data`'s own `writeN` helpers (byte-order-agnostic) exercises them with **zero
   production change**. **Also since closed:** the `X11WindowAssociator` *rule
   matcher* — the cls/name/title-pattern decision in
   `WindowAssociationRuleEntry.getTargetWindow` was extracted verbatim into a
   pure package-private static `X11WindowAssociator.matches(ruleCls, ruleName,
   ruleTitle, cls, name, title)` (both the sub-window and focused-window halves
   delegate to it) and is now unit-tested with plain Strings, so the real
   association logic is covered without an `X11Client`. **Also since closed:** the
   associator's *orchestration* — `getAssociatedWindow` (rule walk, one-time
   retirement, first-match-wins, focus guard) and `removeAllRules` are now
   headless-tested through a `WindowAssociationTarget` interface seam
   (implemented by `X11Client`, faked in tests) plus a no-wiring
   `X11WindowAssociator(boolean)` constructor and a `setFocusedWindow` hook that
   bypass the `LgEventConnector` listener and prefs load. **Still deferred:** the
   constructor's *live* listener/prefs wiring (needs a running desktop) and PIT
   scoped to the X11 package (until the mutation gate is enforceable repo-wide).

---

## 5. Spike → production roadmap

Phased; each phase is independently shippable and testable against a bare Xorg.

**Phase A — prove the spike (this branch + Stage 4).** One external client
(`xterm`/`xeyes`) redirected → textured → input-forwarded on a bare Xorg; §5
evidence recorded. *Exit:* harness reports `COMPLIANT` (all six PASS).

**Phase B — test hardening.** Raise X11-module coverage materially and make the
§4.3 seams headless-testable. **Partially delivered** (`test/x11-compositor-phase-b-seams`):
**25 new JUnit 5 tests** (the X11 package is now 56 tests, all green under
`:lg3d-core:test`/`build`) cover the pure wire-decoder seams with no production
change and no `Display` fake — `X11ShmExt.GetImageReply` (header fields,
size-clamped `pixels()` copy), `X11CompositeExt.OverlayReply` (overlay window
id), `X11FixesExt.FetchRegionReply` (rectangle count + `Enum` iteration) and
`CursorNotifyEvent` (fields + synthetic flag), `X11DamageExt.NotifyEvent` (all
accessors, signed area/geometry origins, `area()`/`geometry()`, `toString`), and
`ConfigureNotifyBugFixed` (the signed 16-bit coordinate sign-extension that
fixes the stock Escher unsigned read). A follow-up
(`test/x11-window-associator-matcher`) then closed the last pure seam: the
`X11WindowAssociator` cls/name/title rule matcher was extracted verbatim into a
static `matches(...)` and unit-tested with plain Strings (8 more tests), so the
real association decision is covered without needing an `X11Client`. A second
follow-up (`test/x11-associator-orchestration`) closed the associator's
*orchestration* too: a `WindowAssociationTarget` interface seam (implemented by
`X11Client`, faked in tests), a no-wiring `X11WindowAssociator(boolean)`
constructor and a `setFocusedWindow` hook make `getAssociatedWindow` (rule walk,
one-time retirement, first-match-wins, focus guard) and `removeAllRules`
headless-testable — **14 more tests** (X11 package now 78). The refactor is
behaviour-preserving except that the focused-window rule path is now null-safe on
a missing WM_CLASS (previously an NPE), which a test pins. **Remaining:** only
wiring PIT scoped to the X11 package once the mutation gate is enforceable
repo-wide; the constructor's *live* `LgEventConnector`/prefs wiring needs a
running desktop and is intentionally left to the integration phases.

**Phase B.5 — shared pixel pipeline (foundation for the 2D host).** The
readback was refactored so the *one* pixel source can feed *two* presentation
sinks, without either sink knowing how pixels were obtained
(`feat/x11-composite-shared-pipeline`). `CompositeWindowImageLoader` now
`implements WindowPixelSource` (a `readRegion(x,y,w,h)` contract over the
Composite `NameWindowPixmap`) and its Z-pixmap scanline assembly was extracted
verbatim into a pure, `Display`-free static `decodeZPixmap(...)` — the single
decoder both sinks read through. A new `CompositedWindowSink` (a
`present(region,x,y,w,h)` + `resized`/`dispose` contract) and a
`CompositedWindowPipeline` (implements `X11Compositor.DamageListener`; on damage
it clamps negative origins, reads the region and presents it, forwarding
resize/dispose) form the presentation-agnostic spine: the 3D desktop registers a
`NativeWindow3D` texture sink, the 2D desktop will register a Swing sink, and the
same pipeline drives both. The refactor is behaviour-preserving for the live 3D
tile path (`X11Client.setupCompositeImageSource` is untouched) and is fully
headless-testable: `CompositeWindowImageLoaderDecodeTest` (8 tests, synthetic
reply buffers pinning 32/24/16-bpp, LSB/MSB, scanline stride/padding and the
geometry/bpp/truncation/null guards) and `CompositedWindowPipelineTest` (8 tests
over fake source/sink pinning read-then-present, origin clamping, empty/null
short-circuits, resize/dispose forwarding, constructor null-checks and the sink
default no-ops). *Exit:* `:lg3d-core:test`/`build` green (X11 package now 94
tests). This is the seam Phase C and the 2D-desktop host build on.

**Phase B.6 — 2D Swing sink + input forwarder.** The two ends that let a
composited native window render *inside* the conventional 2D desktop
(`feat/x11-composite-shared-pipeline`). `SwingCompositedWindowSink` is the 2D
`CompositedWindowSink`: a full-window `BufferedImage` canvas that blits each
damage region at its window offset (locked against the painting surface),
repaints only the dirty rectangle, reallocates-and-preserves on `resized`, drops
the canvas on `dispose`, and exposes a `getComponent()` `JPanel` for a
`Desktop2DWindow` to embed. `SwingX11InputForwarder` is the 2D sibling of
`X11InputForwarder`: AWT mouse/motion/wheel/key listeners on that component are
re-injected into the real client window via XTest — the canvas is a 1:1 pixel
copy, so a component-local point maps straight to `(winX+px, winY+py)` with no
scene-graph pick to invert, key mapping reuses `X11InputForwarder.vkToKeysym`,
and focus follows pointer-enter/press. Headless-tested:
`SwingCompositedWindowSinkTest` (8) pins canvas accumulation/offset, resize
preserve, dispose, degenerate clamping and the headless paint path;
`SwingX11InputForwarderMappingTest` (8) pins the pure `mapPanelToRoot`,
`mapAwtButton` and `keysyms_per_keycode`-aware `keysymToKeycode` seams.
*Exit:* `:lg3d-core:test`/`build` green (X11 package now 110 tests). Increment 3
wires these into `Desktop2DAppRegistry`/`Desktop2DWindow` so an `EXTERNAL` app is
hosted rather than forked to the host WM.

**Phase B.7 — 2D desktop host wiring.** The desktop-side plumbing that turns a
composited native window into an ordinary MDI window *inside* the 2D desktop
(`feat/x11-composite-shared-pipeline`). A Java-3D-free `CompositedWindowHost`
seam (x11) speaks only in terms of a `JComponent` + lifecycle, so the
`desktop2d` package consumes it without dragging in the scene graph; its
production impl `X11CompositedWindowHost` wires a `CompositeWindowImageLoader`
source → `SwingCompositedWindowSink` through a `CompositedWindowPipeline`,
registers it as the window's `DamageListener`, primes a full-window read, and
re-issues `NameWindowPixmap` on resize — the *display* half only (input stays the
WM's glue via `SwingX11InputForwarder`). `Desktop2DCompositorHost` (desktop2d) is
the controller: it owns the `windowId → Desktop2DWindow` map and folds
map/resize/retitle/unmap/shutdown into it (a re-map folds into resize+retitle,
unmap disposes the surface and closes the MDI window). Headless-tested:
`Desktop2DCompositorHostTest` (8) drives the controller with a fake host and a
fake opener building a real headless `Desktop2DWindow`. *Exit:*
`:lg3d-core:test`/`build` green. The live WM claim + `Desktop2D` startup hook
that instantiates `X11CompositedWindowHost` on a bare Xorg is the
session-integration step (Phase G).

**Phase B.8 — WM → 2D-desktop lifecycle bridge (native apps *inside* the 2D
desktop).** The last missing link that makes a real external X11 application
appear as an ordinary window in the conventional 2D desktop
(`feat/x11-composite-shared-pipeline`). Until now the two halves existed but were
never connected: `X11WindowManager` drove only the 3D texture path on
Map/Unmap/Configure/PropertyNotify, and nothing ever notified
`Desktop2DCompositorHost`, so a native client redirected by the compositor had no
route into a `Desktop2DWindow`. This phase adds that route:

- **`WindowLifecycleListener`** (x11) — a `gnu.x11`-free, Java-3D-free
  notification seam speaking only in window ids, titles and pixel sizes:
  `windowMapped` / `windowResized` / `windowRetitled` / `windowActivated` /
  `windowUnmapped`.
- **`X11WindowManager`** now holds a `volatile WindowLifecycleListener` (installed
  via `setWindowLifecycleListener`) and fires it from `mapNotify` (skipping lg3d's
  own window and InputOnly clients), `configureNotify` (resize),
  `propertyNotify` WM_NAME (retitle), `activate` (activation) and
  `unmapNotify`/`destroyNotify` (release). Each callback is isolated in a
  try/catch so a desktop-side failure is logged and never kills the X event loop;
  with no listener registered every fire is a no-op (behaviour-preserving).
- **`CompositedWindowBridge`** (desktop2d) implements the listener and forwards to
  a `Desktop2DCompositorHost`, marshalling each call onto the Swing EDT through an
  injectable `Consumer<Runnable>` (`SWING_EDT` in production, `DIRECT` in tests).

The complete path a native app's pixels + lifecycle now take into the 2D desktop:

```
external X11 client (xterm, firefox, …)
  └─ X server Composite redirect (X11Compositor.redirectSubwindows)
       ├─ PIXELS: NameWindowPixmap → CompositeWindowImageLoader (WindowPixelSource)
       │            → CompositedWindowPipeline (DamageAccumulator + FramePacer +
       │              ReadbackPlanner) → SwingCompositedWindowSink (JPanel canvas)
       │            → Desktop2DWindow content pane
       ├─ INPUT:  SwingX11InputForwarder (AWT listeners → XTest into the client)
       └─ LIFECYCLE: X11WindowManager map/resize/retitle/activate/unmap
                      → WindowLifecycleListener → CompositedWindowBridge (→ EDT)
                      → Desktop2DCompositorHost → open/adjust/close Desktop2DWindow
```

Headless-tested: **`CompositedWindowBridgeTest` (7)** drives the bridge with the
`DIRECT` runner and the fake host/opener, pinning map→open, resize/retitle/
activate/unmap forwarding, EDT-runner marshalling, and that both a controller
failure and a runner refusal are swallowed (never thrown back to the X thread).
The WM fire-points themselves are Display-bound glue and, per the locked scope,
are exercised only on a bare-Xorg host. **The `Desktop2D` startup hook is now
delivered under Phase G** (below): it builds the `Desktop2DCompositorHost` +
`CompositedWindowBridge` over the published session's `CompositedWindowHost` and
registers it through the `WindowLifecycleRegistrar` seam once the WM has claimed
the display.

**Phase C — multi-window + focus/stacking.** Manage N simultaneous clients:
map/unmap lifecycle, sibling stacking order, focus-follows-pointer vs
click-to-focus, per-window Damage tracking, and correct z-order of the
`NativeWindow3D` quads. Implement the InputOnly branch (§4.1).

*Partially delivered* (`feat/x11-composite-shared-pipeline`): the pure
focus/stacking brain now exists as **`CompositedWindowSet`** — it tracks the live
window set, its sibling z-order (bottom→top) and the input focus under a
`POINTER` (focus-follows-pointer) or `CLICK` (click-to-focus) policy, with
`add`/`remove`/`raise`/`lower`/`retitle`/`activate`/`clear` and unmodifiable
`stackTopDown()`/`windowsBottomUp()` views. It holds no X and no Java 3D state, so
both desktops share it. `Desktop2DCompositorHost` maintains one (3-arg
constructor selects the policy; 2-arg defaults to `POINTER`) and exposes
`focusWindow`/`pointerEnter`/`focusedWindowId`/`stackTopDown`/`getFocusPolicy`.
**21 headless tests** (`CompositedWindowSetTest` 13, +4 controller) pin stacking,
focus-transfer-on-remove, raise/lower, both policies and the controller sync.
**Still deferred (needs a bare Xorg):** driving the live `X11WindowManager`
map/unmap/ConfigureNotify into this model, the X sibling z-order of the
`NativeWindow3D` quads, and the non-override-redirect **InputOnly** branch
(`X11WindowManager.java:710`) — implementing WM/InputOnly semantics blind, with
no live Xorg to test against, remains the reckless scope creep §4 warns about.

**Phase D — full EWMH.** Complete the `_NET_WM_STATE` / `_NET_WM_ALLOWED_ACTIONS`
/ `_NET_ACTIVE_WINDOW` / client-list and stacking hints (§4.2) so external
toolkits behave (minimize/maximize/above/below, taskbar/pager hints), and so the
application switcher can enumerate and activate external apps.

*Partially delivered* (`feat/x11-composite-shared-pipeline`): the pure,
spec-correct decision brain now exists as **`NetWmState`** — a static function
over enums and `EnumSet`s with **no `gnu.x11.Display` and no atom ids**. It
implements `applyChange` (REMOVE/ADD/TOGGLE, §5.9, null-safe, never mutates the
input), `defaultStateFor` (per-window-type initial state), `allowedActions`
(state + capabilities → the action set, incl. the maximized/fullscreen/skip-taskbar
/above-below rules), and `decideActive` (focus-stealing prevention: an
application-initiated activation → `DEMANDS_ATTENTION`, a pager/unspecified one →
`ACTIVATE`). `X11WindowManagerHints` keeps the Display-bound half: its
previously-stubbed `setNetWmState` / `setNetAllowedActions` (which hard-coded
only the DIALOG case) now delegate to `NetWmState`, mapping each enum to its atom
name via the 1:1 `stateAtomName`/`actionAtomName` convention and **filtering to
the atoms already advertised in `_NET_SUPPORTED`**; a new `windowTypeFor(atomId)`
maps the client's `_NET_WM_WINDOW_TYPE_*` id back to the enum. The client-list /
stacking half is served by `CompositedWindowSet` (Phase C). **18 headless tests**
(`NetWmStateTest`, X11 package now 140) pin the wire codes, atom-name mapping,
all three state-change actions, every window type's default state, each
allowed-action branch, both focus decisions and the unmodifiable view.
**Still deferred (needs a bare Xorg):** round-tripping a live `_NET_WM_STATE`
client message through `applyChange` and re-emitting the property, honouring
`_NET_ACTIVE_WINDOW`/`_NET_CLIENT_LIST(_STACKING)` requests from real pagers, and
verifying external toolkits react to the advertised actions — all require a live
WM session on bare Xorg.

**Phase E — performance: MIT-SHM fast path.** Today readback prefers MIT-SHM and
falls back to `XGetImage`. Production needs: SHM pixmap attach/detach lifecycle
hardening, damage-region (not full-window) readback, texture-upload batching /
PBO or `ImageComponent2D` reuse, and frame pacing to avoid uploading unchanged
regions. Measure against a video-playing client.

*Partially delivered* (`feat/x11-composite-shared-pipeline`): the pure perf brain
now exists as three X-free, clock-free classes. **`DamageAccumulator`** coalesces
the damage rects reported between frames into one bounding region (clamping
negative origins, ignoring empty rects, `drain()`ing to an immutable `Region`),
so a frame does one readback + one upload instead of one per damage event.
**`FramePacer`** throttles presents to a minimum interval (clock-injected via an
explicit `nowMillis`; first present always due; interval 0 = always due;
`tryAcquire` is side-effect-free on refusal). **`ReadbackPlanner`** picks the
MIT-SHM fast path over a core `XGetImage` round-trip (SHM only when attached,
shared pixmaps supported and the region fits the segment) plus the ZPixmap
`regionBytes`/`bytesPerPixel` sizing. **`CompositedWindowPipeline`** is wired to
all three behaviour-preservingly: it accumulates each `damageReported`, presents
the coalesced region only when its pacer says one is due, and gained
`flush(windowId)` + `hasPendingDamage()`; the 2-arg constructor delegates with an
always-due pacer so the live 3D tile path is unchanged. **29 headless tests**
(`DamageAccumulatorTest` 9, `FramePacerTest` 8, `ReadbackPlannerTest` 8, +4
pipeline coalescing/pacing; X11 package now 169) pin the geometry, throttle and
path-selection branches. **Still deferred (needs a bare Xorg):** the live SHM
segment attach/detach lifecycle, driving `flush` from the real render loop, and
measuring the upload savings against a video-playing client.

**Phase F — target GL/DRI3 bring-up.** On the LFS host, validate Java 3D
(Jogamp) obtains a **hardware** GLX context with DRI3 on the real GPU DDX
(contract §3.3); pin `lg3d.x11.ownwindowid` if auto-discovery of lg3d's own
`Canvas3D` window id fails on the target driver.

*Partially delivered* (`feat/x11-composite-shared-pipeline`): the bring-up
decision is codified as **`GlBringUpPlanner`** — a pure function over a live
`Probe` (`glxPresent`/`directRendering`/`dri3Present`/resolved own-window id)
returning one `Verdict` (`READY`, `PIN_OWN_WINDOW_ID`, `SOFTWARE_ONLY`, `ABORT`)
with the operator-facing `remediation` text, plus `resolveOwnWindowId(override,
discovered)` capturing the override-then-discovery fallback that
`X11Compositor.exemptOwnWindow()` already runs. It encodes the contract §5
acceptance checks. **9 headless tests** (`GlBringUpPlannerTest`) pin every
verdict branch and the own-window resolution. **Still deferred (needs the LFS
host):** running the real GLX/DRI3 probe (`glxinfo`, the GLX extension query)
on the target GPU DDX and feeding it to the planner — a hardware context cannot
be obtained or measured headlessly.

**Phase G — session integration.** The systemd/`xinit` hand-off (contract §3.6):
lg3d as the sole session client on `:0`, clean shutdown (`Shutting down X11
compositor`), crash recovery, and login/`xdm`-style autostart.

*Partially delivered* (`feat/x11-composite-shared-pipeline`): the session state
machine now exists as **`SessionLifecycle`** — `INIT → STARTING → RUNNING →
SHUTTING_DOWN → STOPPED` with `CRASHED`/`RECOVERING` branches, a bounded
crash-recovery budget (`canRecover`), refused (not thrown) illegal transitions,
and the ordered `shutdownSteps()` teardown list. It is **wired into
`X11Compositor`**: the constructor advances `START → READY` (or `FAILURE` when a
required extension is missing) and `shutdown()` drives `SHUTTING_DOWN →
STOPPED`, exposed via `getSessionState()`. **12 headless tests**
(`SessionLifecycleTest`) pin the full transition table, the recovery budget and
the teardown order. **Still deferred (needs the LFS host):** the actual
systemd/`xinit` unit hand-off, process supervision and login/`xdm` autostart
that drive this machine, and observing clean shutdown / crash recovery in a live
session.

*Delivered — 2D-desktop in-JVM session integration*
(`feat/x11-composite-shared-pipeline`): the other half of "session integration" —
letting the conventional 2D desktop *discover* a live compositor running in the
same JVM and host native X11 clients as ordinary MDI windows — is now wired end
to end. **`X11CompositorSession`** (x11) is a discovery holder
`X11IntegrationModule` publishes once redirection succeeds (and
`X11Compositor.shutdown()` clears); its `Session` exposes only the Java-3D-free
interface types — a `CompositedWindowHost` and a **`WindowLifecycleRegistrar`**
(the new public seam `X11WindowManager` implements, since the WM class is
package-private) — so `desktop2d` never drags in the scene graph or `gnu.x11`.
**`CompositedDesktopWiring`** (desktop2d) is the turnkey assembly: a pure
`shouldInstall(optIn, sessionLive, headless)` decision, a
`desktopOpener(JDesktopPane)`, and `install(host, opener, registrar)` returning a
`Handle` that tears down via `dispose(unregister)`. `Desktop2D.show()` calls the
guarded `installCompositedWindows()`, which reads
`X11CompositorSession.current()` and installs only when the operator opted in via
**`lg3d.x11.composite2d`**, a session is live and the JVM is not headless;
composited windows open through a rich opener (taskbar button + workspace
assignment + cascading placement, but *not* session-persisted, since a native
client is transient) and `exit()` disposes the wiring. In every topology without
a live compositor session (dev mode, the `*_nox` configs, compositing disabled)
nothing is published and the 2D shell is unchanged. **16 headless tests**
(`X11CompositorSessionTest` 7, `CompositedDesktopWiringTest` 9) pin the holder's
publish/null-clear/replace lifecycle, the install truth table and null guards,
the default opener, and the full map→window→dispose path over a real headless
`JDesktopPane` (EDT-flushed). **Still deferred (needs a combined bare-Xorg
launch):** no production topology yet runs the 2D Swing desktop *and* the X11
WM/compositor in one JVM — `Main` treats 3D and 2D as mutually exclusive and the
`*_x` configs start the 3D desktop — so the live registration path is exercised
only once such a combined launch exists on the LFS host.

---

## 6. References

- [`lfs-x11-contract.md`](lfs-x11-contract.md) — normative host contract (§3
  requirements, §4 prohibitions, §5 acceptance, §6 `--nested`).
- [`../scripts/x11/live-proof.sh`](../scripts/x11/live-proof.sh) — Stage-4 harness.
- `lg3d-core/src/classes/org/jdesktop/lg3d/displayserver/nativewindow/x11/` —
  `X11Compositor`, `X11WindowManager`, `X11WindowManagerHints`,
  `CompositeWindowImageLoader`, `X11InputForwarder`, `X11WindowAssociator`,
  `X11CompositeExt`/`X11DamageExt`/`X11ShmExt`/`X11FixesExt`,
  `VerifyX11Extensions`.
- Gradle task `:lg3d-core:verifyX11Extensions` (`lg3d-core/build.gradle`).
- README → **X11 compositor mode** and **Deployment target (Linux From Scratch)**.
