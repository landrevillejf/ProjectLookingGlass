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

**Phase C — multi-window + focus/stacking.** Manage N simultaneous clients:
map/unmap lifecycle, sibling stacking order, focus-follows-pointer vs
click-to-focus, per-window Damage tracking, and correct z-order of the
`NativeWindow3D` quads. Implement the InputOnly branch (§4.1).

**Phase D — full EWMH.** Complete the `_NET_WM_STATE` / `_NET_WM_ALLOWED_ACTIONS`
/ `_NET_ACTIVE_WINDOW` / client-list and stacking hints (§4.2) so external
toolkits behave (minimize/maximize/above/below, taskbar/pager hints), and so the
application switcher can enumerate and activate external apps.

**Phase E — performance: MIT-SHM fast path.** Today readback prefers MIT-SHM and
falls back to `XGetImage`. Production needs: SHM pixmap attach/detach lifecycle
hardening, damage-region (not full-window) readback, texture-upload batching /
PBO or `ImageComponent2D` reuse, and frame pacing to avoid uploading unchanged
regions. Measure against a video-playing client.

**Phase F — target GL/DRI3 bring-up.** On the LFS host, validate Java 3D
(Jogamp) obtains a **hardware** GLX context with DRI3 on the real GPU DDX
(contract §3.3); pin `lg3d.x11.ownwindowid` if auto-discovery of lg3d's own
`Canvas3D` window id fails on the target driver.

**Phase G — session integration.** The systemd/`xinit` hand-off (contract §3.6):
lg3d as the sole session client on `:0`, clean shutdown (`Shutting down X11
compositor`), crash recovery, and login/`xdm`-style autostart.

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
