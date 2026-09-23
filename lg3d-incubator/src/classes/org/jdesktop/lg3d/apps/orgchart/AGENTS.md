# OrgChart Suite (Agenda 3D · Chart 3D · Contact 3D · Prefuse/Buz3D)

> Role-aware guide for the `orgchart/` subtree — a family of Office apps that share
> a contact framework. This file governs `ui/agenda`, `ui/chart`, `ui/contact`,
> `ui/prefuse`, `ui/common`, `ui/images` and `framework/`. Module:
> [`lg3d-incubator`](../../../../../../../AGENTS.md) · canonical UI/UX rulebook:
> [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md) · native-3D guide:
> [`docs/lg3d-native-apps.md`](../../../../../../../../docs/lg3d-native-apps.md) ·
> build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## Suite at a glance

| App | Entry point | Base | Start-menu name / group | Descriptor |
| --- | --- | --- | --- | --- |
| Agenda 3D | `ui.agenda.Agenda3D.main` | `Frame3D` (native, authored for the port) | Agenda 3D / **Office** | `lg3d-demo-apps/src/config/agenda3d.lgcfg` |
| Chart 3D | `ui.chart.Chart3D.main` | `AbstractOrgChartApp` → `Frame3D` | Chart 3D / **Office** | `lg3d-demo-apps/src/config/orgchart-chart.lgcfg` |
| Contact 3D | `ui.contact.Contact3D.main` | `Frame3D` | Contact 3D / **Office** | `lg3d-demo-apps/src/config/orgchart-contact.lgcfg` |
| Prefuse (Buz3D) | `ui.prefuse.Prefuse3D.main` | `AbstractOrgChartApp` | Buz3D / **Office** | `lg3d-incubator/src/config/prefuse.lgcfg` — **not discovered** (incubator `src/config` bundles to `config/`, which is not scanned) |

| Item | Value |
| --- | --- |
| Status | **Production-grade** native-3D apps (Agenda/Chart/Contact are ported & registered; Prefuse is built but its descriptor is not scanned) |
| Surface | **pure-3D `Frame3D`** — click-driven (no keyboard focus in dev mode) |
| Shared framework | `framework/` (`ServiceContext`/`Channel`/`Service`, `contact.ContactService` + `PreferenceContactService`/`LDAPContactService`) and `ui/common/` (`AbstractOrgChartApp`, `Button`, `UIUtil`, panels) |
| Extra deps | Prefuse needs `ext/prefuse.jar`; Agenda 3D needs `libs/jbusinessday` (+ `slf4j` at runtime) |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — These apps share a **contact framework** (`framework/contact`):
  `Contact` is a `Map` (uid/givenName/sn/email/calendar/calendarBusy/presence) served
  by `PreferenceContactService`. Cross-app data uses the **shared user `Preferences`
  tree** (`/contacts`), **not** ServiceContext/`Channel` — `ServiceContextFactory`
  makes a fresh context per app, so channels are not shared across separately-launched
  apps. Contact 3D populates `/contacts`; Agenda 3D reads it and owns
  `/agenda/appointments`; Mail 3D also reads it. `AbstractOrgChartApp` is the shared
  `Frame3D` base for Chart/Prefuse.
- **Engineer / Developer** — Obey the core UI/UX rulebook and the **live-texture
  rule** (single fixed-size `ImageComponent2D` with `ALLOW_IMAGE_WRITE`, repaint +
  `.set()` in place, never re-attach; POT textures; pixels uploaded before attach).
  Interactive quads set `Geometry.ALLOW_INTERSECT` for `PICK_GEOMETRY`. **Dev mode
  routes no keyboard focus** — use the runtime-drawn `AgendaButton` idiom, not typing.
  Never write another app's `Preferences` node. `UIUtil.setTexture` creates a new
  texture and is only safe off-live. Note Agenda 3D needs `slf4j` on the run classpath
  (jbusinessday static-init) or the desktop dies with `NoClassDefFoundError`. Jogamp only.
- **QA** — Unit-test the plain-Java pieces headless (`Appointment`/`AppointmentStore`,
  `ContactDirectory`, business-day/holiday marking, contact model). Verify each 3D view
  with the in-JVM probe + internal screencapture; a black host capture under Wayland is
  not a defect. Note Prefuse will not appear in the start menu (descriptor not scanned)
  — that is a packaging fact, not a runtime failure.
- **Business Analyst** — A supported Office suite (contacts, org chart, week agenda,
  prefuse graph) demonstrating cross-app data sharing via `Preferences`. Agenda/Chart/
  Contact are the shipped showcase; Prefuse is experimental/niche.
- **Functional Analyst** — Spec each app as user-visible function + core contract
  (Frame3D host, live-texture view, `/contacts` + `/agenda/appointments` nodes,
  descriptor location). Record the Prefuse descriptor-not-scanned issue explicitly so
  it is not mistaken for a broken app.
- **Project Manager** — Commit scope `lg3d-incubator`; Agenda/Chart/Contact descriptors
  live in `lg3d-demo-apps`, so PRs often span two modules — say so. Track `ext/` and
  run-classpath changes (prefuse, jbusinessday, slf4j) as integration risk. Done =
  build + `:lg3d-core:runtimeResources` + `./run-lg3d.sh` + capture/log evidence.
- **UI/UX (3D & 2D)** — **3D only**: week grid, org-chart nodes, contact cards and
  buttons are runtime-drawn scene-graph widgets (Agenda 3D marks weekends/holidays and
  navigates weeks/months/years). Follow the glassy vocabulary, depth ordering and
  click-driven-input rules; frame-level gestures use the CTRL+right-click convention
  (a plain right-click never reaches hosted apps — their content quad is non-propagatable).

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook +
`docs/lg3d-native-apps.md` → root `AGENTS.md`. On conflict the higher file wins; fix
here in the same PR. Every PR states which app changed, live vs dormant status, the
descriptor location, the `Preferences` nodes touched, and the evidence.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents` for this file); imperative
subject ≤ 50 chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump.
Stage only intended paths (never `git add -A`).
