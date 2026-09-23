# LgAmazon (Amazon WebService on LG3D)

> Role-aware per-app guide. Module: [`lg3d-incubator`](../../../../../../../AGENTS.md)
> · UI/UX rulebook: [`lg3d-core`](../../../../../../../../lg3d-core/AGENTS.md)
> · build/exclusions/commits: root [`AGENTS.md`](../../../../../../../../AGENTS.md).

## App at a glance

| Item | Value |
| --- | --- |
| Status | **Dormant prototype** (2006-era; compiles, effectively non-functional — dead web-service API) |
| Entry point | `lgamazon.LgAmazon` (extends `Frame3D`) |
| Surface | **pure-3D** `Frame3D`; search runs on `SearchThread`; includes an AWT `ProxyAuthenticator`; `component.HTMLLabel` / `component.LgEdit` widgets |
| Start-menu name / group | LgAmazon / **Internet** — descriptor `LgAmazon.lgcfg` is in `lg3d-incubator/src/config` → `config/` (**not scanned**) |
| Command | `java org.jdesktop.lg3d.apps.lgamazon.LgAmazon` |
| Runtime blocker | Needs **Apache Axis** (`jaxrpc/axis/wsdl4j/commons-*`) + `activation`/`mail` jars and the 2006-era Amazon Web Service endpoint, which no longer exists in that form |
| Build | `./gradlew :lg3d-incubator:build` |

## Roles

- **Architect** — A SOAP/Axis client for the long-gone Amazon Web Service. Dormant
  **and** functionally dead (endpoint + Axis stack obsolete). Do not invest in reviving
  a dead-backend prototype; a modern version would need a current product-advertising
  API and a current HTTP/JSON client, not Axis.
- **Engineer / Developer** — If touched, keep network calls off the render thread
  (`SearchThread`) and marshal results back safely; obey the core UI/UX rulebook for
  the 3D view. Expect Axis/SOAP calls to fail. Jogamp packages only.
- **QA** — Classify as **compiles, does not run** (dead backend + absent Axis jars). Do
  not file failed searches as a regression; verify only that launch does not crash the
  desktop.
- **Business Analyst** — Historical curiosity (Amazon SOAP on LG3D). No product value.
- **Functional Analyst** — Document the dead-API/missing-Axis blockers so a future port
  is re-scoped against a live API rather than patched.
- **Project Manager** — Commit scope `lg3d-incubator`. Dormant/dead — do not schedule.
- **UI/UX (3D & 2D)** — **3D** results view with `HTMLLabel`/`LgEdit` widgets. Follow
  the glassy vocabulary and depth ordering from core if ever revived.

## Communication & coherence

Single source of truth: this file → module `AGENTS.md` → core UI/UX rulebook → root
`AGENTS.md`. On conflict the higher file wins; fix here in the same PR.

## Commit / PR

Conventional Commit scope `lg3d-incubator` (or `agents`); imperative subject ≤ 50
chars; add a `CHANGELOG.md` bullet under `[Unreleased]`; no version bump; stage only
intended paths (never `git add -A`).
