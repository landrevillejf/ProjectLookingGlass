# Weather Application

> Role-aware per-app guide. Module: [`lg3d-apps`](../../../../../../../AGENTS.md)
> · canonical UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Production** desktop utility (network-backed; degrades gracefully offline) |
| Entry point | `Weather.main` → `TitledSwingWindow.show(...)` |
| Surface | **SwingNode-in-Frame3D** (Swing panel on a `SwingNode` quad under a glassy title bar); MDI internal frame in the 2D/Swing desktop |
| Start-menu name / group | Weather / **Utilities** |
| Command | `java org.jdesktop.lg3d.apps.weather.Weather` |
| Descriptor | `lg3d-apps/src/config/weather.lgcfg` → `config/demo` |
| Icon | `lg3d-core/src/resources/images/icon/weather.png` (sun-behind-cloud glyph from `lg3d-art/tools/GenerateAppIcons`) |
| Build | `./gradlew :lg3d-apps:build` |

## Key components

- **Weather** — entry point; installs the hosted Metal LAF and shows the window.
- **WeatherPanel** — plain Swing UI: a preset-city `JList` (west), a current-conditions
  card (place, big temperature, condition, high/low, feels-like / humidity / wind) with a
  hand-painted sky glyph, and a five-day forecast strip. `°C/°F` and Refresh buttons; a
  status line. `WIDTH_PX`/`HEIGHT_PX` preferred size; background fetch on a daemon
  scheduler, results published back on the EDT.
- **OpenMeteo** — AWT-free backend seam: preset `City` list, `forecastUrl`, `fetch`
  (JDK `java.net.http`), a dependency-free recursive-descent `Json` reader, WMO
  `condition`/`skyFor` maps and Celsius↔Fahrenheit / km-h↔mph conversion. Unit-testable
  headlessly with no network.

## Data source

Weather comes from the free **[Open-Meteo](https://open-meteo.com/)** forecast API — the
same key-less source the desktop **Weather widget** (`lg3d-widgets`
`org.jdesktop.lg3d.widgets.builtin.WeatherCard`) uses — over the JDK `java.net.http`
client with a hand-written JSON reader. **No third-party library** is added; data is
always requested in Celsius / km-h and converted for display, so toggling units never
re-fetches. This replaces the dormant 2006-era `lg3d-incubator`
`org.jdesktop.lg3d.apps.weather` stub (a green `Box` in a `Frame3D` plus a `JFrame`
config editor, no descriptor, no data feed), which is removed.

## Roles

- **Architect** — A reference network-backed SwingNode app: keep all fetching/parsing in
  the AWT-free `OpenMeteo` seam so it stays headless-testable and swappable, and keep the
  panel pure Swing so the one class serves both the 3D (`SwingNode`) and 2D (MDI) desktops.
  No cross-module dependency on `lg3d-widgets`; the Open-Meteo approach is deliberately
  reimplemented self-contained (the widget's `WeatherCard` is coupled to the widget
  lifecycle and cannot be reused from `lg3d-apps`).
- **Engineer / Developer** — Obey the core UI/UX rulebook: Metal LAF via
  `installHostedLookAndFeel` before constructing the panel, no Synth combo boxes (a
  `JList` city selector is used), EDT hops for every model publish, and **never block the
  EDT** — the HTTP fetch runs on the daemon scheduler. Paint the sky glyph with `Graphics2D`
  (no image assets). Use `Locale.ROOT` for number formatting.
- **QA** — `OpenMeteoTest` covers the seam headlessly (URL building, JSON reader,
  condition/sky maps, unit conversion, document parsing, sparse/missing fields) with no
  network. Verify the panel with the in-JVM probe + internal screencapture; a black host
  capture under Wayland is not a defect. Confirm graceful "Unavailable" status when offline.
- **Business Analyst** — A daily-glance utility: current conditions plus a short forecast
  for a preset world city, with no API key or account. Value is immediacy and zero setup.
- **Functional Analyst** — Spec user-visible function (pick a city → current + 5-day
  forecast; toggle `°C/°F`; Refresh; auto re-poll every 15 min) plus the offline contract
  (keep the last good reading flagged in the status line, never crash).
- **Project Manager** — Commit scope `lg3d-apps` (with the `lg3d-core` registration +
  test and the removed incubator stub in the same PR). Done = build + `./run-lg3d.sh` +
  screencapture/log evidence. Branch → PR against `main`.
- **UI/UX (3D & 2D)** — 3D: glassy `TitledSwingWindow` frame + transparency ordering; the
  forecast strip and glyph must stay legible in the offscreen capture. 2D: the same Swing
  panel as an MDI internal frame. Verify ordering over a maximized window.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR. Every PR states
the surface (SwingNode), the Open-Meteo data source, and the verification evidence.

## Commit / PR

Conventional Commit scope `lg3d-apps`; imperative subject ≤ 50 chars; add a
`CHANGELOG.md` bullet under `[Unreleased]`; no version bump (that is a separate chore
PR); stage only intended paths (never `git add -A`).
