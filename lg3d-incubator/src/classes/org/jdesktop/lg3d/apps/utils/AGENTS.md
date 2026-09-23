# Utils / FreeDesktop (Desktop Plugin + XDG Library)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Library + scene-manager plugin** (compiles; plugin descriptor not scanned, so not loaded by default) |
| Entry point | `utils.freedesktop.DesktopPlugin` (`implements SceneManagerPlugin, LgEventListener`); `utils.freedesktop.tabbed.TabbedDesktopPlugin` (`implements DesktopPluginInterface`); `menu.MenuLoader` has a `main` for offline menu parsing |
| Surface | **Desktop infrastructure**, not a window: parses freedesktop.org/XDG `.desktop` entries, icon themes and menus (`common/DesktopEntry`, `IconTheme`, `MimeTypes`, `XDG`), and renders a 3D desktop of icons (`tabbed/DesktopIcon`, `InfinityMatrixLayout`, `TabShortcut`) |
| Registration | `desktop.lgcfg` declares a **`SceneManagerPluginConfig`** pointing at `DesktopPlugin` — but it lives in `lg3d-incubator/src/config` → `config/` (**not scanned**), so the plugin is not installed by default |
| Command | loaded as a scene-manager plugin (not launched as an app) |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — This is the freedesktop.org/XDG integration layer: a `SceneManagerPlugin`
  that reads the host's `.desktop` entries, icon themes and menu spec and presents them as a
  3D desktop. It is a **plugin + shared library**, not a start-menu app, so it is registered
  via `SceneManagerPluginConfig`, not `StartMenuItemConfig`. It compiles but is **not
  loaded** (its descriptor is in the unscanned `config/`). Keep the `common` (XDG model) /
  `menu` (spec parsing) / `tabbed` (3D desktop rendering) layering clean — other code may
  reuse the XDG readers.
- **Engineer / Developer** — Follow the core plugin contract (`SceneManagerPlugin`
  lifecycle, `LgEventListener` wiring) and the UI/UX rulebook for the icon surfaces (texture
  pixels before attach, `Component3D` wrapping, translucency sorting, EDT hops for any
  Swing). Filesystem/menu parsing must run off the render thread. Do not assume the plugin
  is active — verify how it is registered before relying on it. Jogamp packages only.
- **QA** — Verify the XDG/menu parsers headless (`MenuLoader.main`, `DesktopEntry`/
  `IconTheme` unit-testable). The plugin is **not loaded by default**; do not file "no
  freedesktop icons on the desktop" as a regression unless the descriptor is wired into a
  scanned config path. Confirm icon rendering with the in-JVM probe + internal screencapture.
- **Business Analyst** — Potentially high-value: it is the bridge that lets lg3d show the
  host's real application menu/icons. Currently dormant (not registered). Decide whether to
  promote it into the scanned config as a product feature.
- **Functional Analyst** — Spec the XDG contract (which `.desktop`/icon-theme/menu fields
  are honoured), the plugin registration mechanism, and the 3D desktop layout behaviour.
  Record the descriptor-not-scanned fact as the reason it is inactive.
- **Project Manager** — Commit scope `lg3d-incubator`. Promotion to a live desktop plugin
  is a deliberate decision (move/enable the `SceneManagerPluginConfig` descriptor) — track it
  as such, not as routine maintenance.
- **UI/UX (3D & 2D)** — **3D desktop** of icons/shortcuts (glassy labels, layouts) driven by
  host metadata. Follow the glassy vocabulary, `Cursor3D` on interactive icons, and depth
  ordering from core.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
