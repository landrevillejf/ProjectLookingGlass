# wonderland-server — and the Wonderland world library

> Role-aware guide for everyone working on the **Wonderland** port's server and
> its **worlds**. The root [`../AGENTS.md`](../AGENTS.md) governs the build
> system, module map and commit conventions. This file adds the module-specific
> rules and — most importantly — teaches two things an agent keeps needing:
> **how to author a Wonderland world** and **how to give the server access to
> every existing world** (the clean-room ones and the stock Open Wonderland
> ones).

## The Wonderland modules at a glance

The OpenWonderland port is split across several Gradle modules (`docs/wonderland-migration.md`,
milestones M0–M7). This guide owns the **world** concern, which spans two of them:

| Item | Value |
| --- | --- |
| `wonderland-server` | The Spring Boot session/host server: the REST world surface (`WorldRestController`), the wire-protocol WebSocket (`CommsWebSocketHandler`), the authoritative `WorldModel`, the opt-in `WorldSeeder`, and the `WorldCatalog` that answers "which worlds can I serve". Bundled world resources live here under `src/main/resources/<module>/`. |
| `wonderland-modules` | The M5 asset pipeline: `WorldStore` (`DirectoryWorldStore`, `ClasspathWorldStore`, `ManifestClasspathWorldStore`, `JarWorldStore`), `WorldLoader` (decodes `*-wlc.xml` cells and resolves deployed models), `ModelLoader` (`wla://` → `.dep` → `.ldr` → `.dae.gz`). Pure Java, no client / `lg3d-core` import. |
| Depends on | `wonderland-common` (shared wire/data model) + `wonderland-modules` (asset pipeline). It does **not** import `wonderland-client` or `lg3d-core` in production code (only in tests, for the loopback integration). |
| Build / test | `./gradlew :wonderland-server:build` (Spring Boot tests boot on a random port, headless). `:wonderland-modules:build` runs the asset-pipeline suite. Coverage floors are pinned in the root `build.gradle` (`coverageFloors`). |
| Run | `./gradlew :wonderland-server:bootRun` serves REST at `http://localhost:8080/api/world` and the WebSocket at `ws://localhost:8080/comms`. See *Accessing every world* for the seeding knobs. |

## How the roles work together

The **Architect** guards the dependency rule (server never imports the renderer
or client — a cell's client class is stored as an inert `String`) and the
**never-throw boot contract** (a bad world must not stop a server). The
**Engineer** authors world modules as data (XML + COLLADA) and keeps the store
seam honest. **QA** proves the pipeline headlessly with synthetic jars and
`@TempDir`, and boots the real server to read back the catalog over REST. The
**Analysts** keep the world/asset vocabulary explicit. The **PM** scopes commits
to `wonderland-server` / `wonderland-modules` and keeps the docs and changelog
in sync.

## Architect

- **Dependency direction is non-negotiable.** `wonderland-server` compiles
  against `wonderland-common` + `wonderland-modules` only. The server never
  imports the renderer (`wonderland-renderer`) or the client
  (`wonderland-client`) in production code. A seeded cell therefore carries its
  client-side class **by name** (`WorldSeeder.MODEL_CELL_CLASS`,
  `org.jdesktop.wonderland.renderer.cell.WonderlandModelCell`) so the LOAD_CELL
  reaches a client that reflects it — the server stays headless of graphics.
- **A world that fails to load must degrade, never abort.** Every read path —
  `WorldCatalog`, `WorldSeeder`, `JarWorldStore`, `WorldLoader` — swallows a
  malformed jar / XML / unreadable asset into an empty or name-only result with a
  `WARN`, never an exception that reaches boot. This is proven by
  `WorldSeederTest.aStoreThatThrowsDegradesToAnEmptyWorld` and
  `WorldCatalogTest.aCorruptJarDegradesToANameOnlyRowRatherThanHidingTheLibrary`.
- **Seeding is opt-in and OFF by default** (`wonderland.world.seed=none`) so an
  empty server still boots and the M3b socket test (which asserts an empty world
  fans out exactly the environment plus a fresh view cell) is unaffected.
- **REST view and wire view are one object.** `WorldRestController` reads the
  *same* `WorldModel` the `ProtocolRouter` mutates, so they can never drift —
  `WorldRestControllerTest.aWireMoveIsReadableThroughRest` pins it.

---

## How to author a Wonderland world

A **world** is a *module*: a directory (or jar) that names itself, lists its
cells, and packs the models they point at. The **outpatient clinic** under
`wonderland-server/src/main/resources/wonderland-outpatient-clinic/` is the
worked example — copy it. It is a clean-room re-authoring of the community
"TMS Medical Center" from `outpatientclinic.jpg`, built entirely from COLLADA
volumes (no vendored installer binaries).

### 1. The module skeleton

Place the world under `wonderland-server/src/main/resources/<module-id>/`:

```
<module-id>/
├── module.xml                 # name + version + description (the catalog reads this)
├── requires.xml               # module dependencies (may be an empty list)
├── world-index.txt            # the cell catalog (see step 4 — classpath worlds only)
├── wfs/<world>-wfs/           # one XML per cell
│   ├── floor-wlc.xml
│   ├── exam-room-1-wlc.xml
│   └── ...
└── <name>.kmz/                # a deployed model, referenced at the MODULE ROOT
    ├── <name>.dep             # the dependency file the cell points at
    ├── <name>.ldr             # the loader manifest
    └── models/<name>.dae      # the COLLADA geometry (a jar packs these as .dae.gz)
```

`module.xml` (the catalog reads `<name>` and `<description>` for the listing):

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<wonderland-module>
    <name>wonderland-outpatient-clinic</name>
    <version><major>0</major><minor>1</minor><mini>0</mini></version>
    <description>Outpatient clinic world: waiting area, exam rooms, …</description>
</wonderland-module>
```

### 2. A cell is one `*-wlc.xml`

Each cell is a JAXB-decoded `CellServerState`. A model cell roots at
`<model-cell>` and carries a `model-cell-component` (the deployed model) plus a
`position-component` (translation / scale / rotation the seeder lifts into a
`CellTransform`):

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<model-cell>
    <name>Floor</name>                     <!-- the human name; a nameless cell is skipped -->
    <model-cell-component>
        <deployedModelURL>wla://wonderland-outpatient-clinic/clinic-box.kmz/clinic-box.dep</deployedModelURL>
    </model-cell-component>
    <position-component>
        <translation><x>0</x><y>0.0</y><z>0</z></translation>
        <scale><x>24.0</x><y>0.05</y><z>18.0</z></scale>
        <rotation><x>0.0</x><y>1.0</y><z>0.0</z><angle>0.0</angle></rotation>
    </position-component>
</model-cell>
```

- **`wla://<module-id>/<path>`** references a model **relative to the module
  root** — not a URL. `ModelLoader` resolves it: `.dep` → `.ldr` → `.dae(.gz)`.
- A cell with **no `<name>`** is intentionally skipped by the seeder (there is
  nothing meaningful to list), so every cell you want hosted must be named.

### 3. Author the geometry

The clinic uses simple COLLADA `<geometry>` volumes (boxes / planes) so the world
is committed as readable text and has no binaries. `.dae` files live under
`<name>.kmz/models/`; the same `.dep`/`.ldr` chain a stock jar uses applies, so
the model resolves identically whether it ships on the classpath or in a jar.

### 4. Classpath worlds need a `world-index.txt`

A **bundled classpath** world cannot be enumerated (a classpath directory cannot
be walked across exploded *and* jar-packaged layouts), so `ManifestClasspathWorldStore`
reads a fixed catalog instead. List every cell path (module-root relative) in
`<module-id>/world-index.txt`:

```
wfs/outpatient_clinic-wfs/floor-wlc.xml
wfs/outpatient_clinic-wfs/exam-room-1-wlc.xml
…
```

`WorldLoader` sorts, so order is not significant. Register the module in
`WorldCatalog.BUNDLED` (`List.of(WorldSeeder.CLINIC_MODULE, "<module-id>")`) so
the catalog offers it and `available()` lists it with its real cell count.

> A world packaged as a **jar** does *not* need `world-index.txt` — a
> `JarFile` is enumerable and `JarWorldStore.listAssets` walks its entries
> directly. Only the classpath origin needs the manifest.

### 5. Verify a new world

- Add a `WorldSeederTest`-style case that seeds it and asserts the expected cell
  names land under the `Environment` root (`seedingTheClinicPopulatesEveryNamedCell`).
- Boot it for real and read it back (no graphics needed):
  ```bash
  ./gradlew :wonderland-server:bootRun --args='--wonderland.world.seed=<module-id>'
  curl -s localhost:8080/api/world/cells | python3 -m json.tool
  ```

---

## Accessing every existing world

The port has **two origins** of worlds, merged into one catalog view.

### The bundled classpath world (clean-room)

The outpatient clinic ships inside the server jar and is always available. Seed
it by module name:

```bash
./gradlew :wonderland-server:bootRun --args='--wonderland.world.seed=wonderland-outpatient-clinic'
```

### The stock Open Wonderland worlds (7 packaged jars)

The 2006 installer (`wonderland/Wonderland.jar`, ~400 MB, **gitignored, never
committed**) carries each demo world as a packaged module jar under
`modules/wonderland-*-world.jar`: almostempty, auditorium, combined, gallery,
japanesepavilion, meetingcomplex, town. They are made accessible **without
committing any binary** by `:wonderland-server:extractWorlds`:

```bash
# 1. unpack the 7 stock world jars into wonderland-server/wonderland-worlds/ (gitignored)
./gradlew :wonderland-server:extractWorlds

# 2. point the catalog at that directory; every *-world.jar now lists AND seeds
./gradlew :wonderland-server:bootRun \
  --args='--wonderland.world.dir=wonderland-worlds --wonderland.world.seed=wonderland-town-world'
```

- The task **warns and succeeds** when no installer is present (CI has none), so
  it is never a build dependency and is deliberately not wired into `check`.
- Override the output dir with `-PwonderlandWorldsDir=<path>`.
- **Why it "just works":** each stock world spells its art as
  `wla://<module>/<path>` at the module root but the jar stores those bytes under
  `art/<path>`. `JarWorldStore` resolves this on **read** — it tries the exact
  entry, then the `art/` sibling — so no vendored binary is ever rewritten.

### The catalog REST surface

`GET /api/world/worlds` returns a `WorldSummary` per world — `module`, `title`,
`description` (both read from `module.xml`), `source` (`classpath` or `jar`), and
`cellCount` — with the bundled worlds first, then installed jars by name. It is
read **independently of the seeded world**, so a browser can list what is
available even before (or without) any world being loaded:

```bash
curl -s localhost:8080/api/world/worlds | python3 -m json.tool
# [{ "module": "wonderland-outpatient-clinic", "source": "classpath", "cellCount": 9 },
#  { "module": "wonderland-combined-world",    "source": "jar", "cellCount": 41 }, …]
```

`GET /api/world/cells` then lists the *seeded* world's hierarchy, exactly as before.

## Engineer / Developer

- **A cell is inert data on the server.** Never import a renderer class to seed
  a cell; store the client class name as a `String` (`MODEL_CELL_CLASS`).
- **Never rewrite a vendored asset.** To make a stock jar readable, add a
  read-side fallback in a store (like `JarWorldStore`'s `art/` bridge), not a
  transform of the committed/jar bytes.
- **`JarFile.getEntry` returns `ZipEntry`, not `JarEntry`** — type the
  `findEntry` seam as `ZipEntry` (jar natives still resolve `getInputStream`).
- **`@Autowired` a multi-constructor Spring bean explicitly.** `WorldSeeder` has
  a public 3-arg (`@Value` + `WorldCatalog`) bean ctor and a package-private
  2-arg test ctor; without `@Autowired` on the public one Spring falls back to a
  no-arg ctor and boot fails.
- JAXB is the **`javax.xml.bind`** (2.3.x) namespace here, forced back in
  `wonderland-server/build.gradle` — the ported data model is written against
  it, not `jakarta.xml.bind`. Do not remove those `resolutionStrategy.force`
  pins or a world silently fails to seed (`NoClassDefFoundError`).

## QA

- Coverage floors (root `build.gradle`, `coverageFloors`) gate `build`:
  `wonderland-server` branch floor `0.840`, `wonderland-modules` lines `0.985` /
  branches `0.945`. A new store/catalog class must arrive with tests that cover
  its **defensive branches** (absent `module.xml`, corrupt jar, null / non-existent
  worlds dir, nameless cell), not only the happy path — an uncovered fallback
  drops the module below its floor and fails the build.
- Tests are **headless**: build small synthetic world jars in a `@TempDir`
  (`JarWorldStoreTest`, `WorldCatalogTest`) rather than depending on the
  installer; boot the real context on a random port for REST.
- **Live verification for stock worlds** (needs the local installer, so it is
  PR evidence, not CI): run `:wonderland-server:extractWorlds`, boot with
  `--wonderland.world.dir=wonderland-worlds`, and confirm `GET /worlds` lists all
  8 and a stock seed fans out its cells. Report that evidence rather than
  claiming an untested pass.

## Business Analyst

- Value: the port does not ship *one* world, it hosts a **library** — the
  clean-room clinic plus every classic Open Wonderland demo — selectable per
  deployment without a rebuild. Access to a world costs a config flag
  (`wonderland.world.dir`) and one extraction command, never a code change.

## Functional Analyst

- A world = a module (`module.xml` + `requires.xml`) of named cells (`*-wlc.xml`),
  each resolving its model through the `wla://` chain. Two origins (classpath,
  jar) share one `WorldStore` contract: `open` / `present` / `listAssets`.
- Catalog = "what can I serve" (read-only, never a boot dependency); cells =
  "what is live right now". Keep those two questions distinct.

## Project Manager

- Commit scope is **`wonderland-server`** / **`wonderland-modules`** (or `agents`
  for this file). Branch → commit → push → PR against `main`; never commit to
  `main`; stage only intended paths (never `git add -A` — `wonderland-worlds/`
  and the `wonderland/` installer are gitignored and must stay out).
- Add a `CHANGELOG.md` bullet under `[Unreleased]` and run
  `./gradlew :lg3d-mandela:whatsNewRefresh` in the same PR; do **not** bump the
  version here.
- Done = `:wonderland-server:build` + `:wonderland-modules:build` green (tests +
  coverage floors + checkstyle), the catalog/seeding verified live against a
  stock jar, and this guide updated when the authoring or access contract changes.

## Communication & coherence

- Single source of truth: this file (the world concern), the root
  [`../AGENTS.md`](../AGENTS.md) (build / commits / module map). Conflict → root
  wins; fix here in the same PR.
- The full port context lives in [`../docs/wonderland-migration.md`](../docs/wonderland-migration.md)
  and [`../documentation/wonderland/docs/`](../documentation/wonderland/docs/)
  (architecture, asset pipeline, milestones).
