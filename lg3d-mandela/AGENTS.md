# AGENTS.md — lg3d-mandela

> Role-aware guide for everyone working on **lg3d-mandela** (Project Mandela, the
> desktop's own scripting language). The root [`../AGENTS.md`](../AGENTS.md)
> governs the build system, module map, exclusions and commit conventions; this
> file adds module-specific guidance and the shared per-role view so **all roles
> stay coherent**.

## Module at a glance

| Item | Value |
| --- | --- |
| Purpose | **Project Mandela**: the desktop scripting language — lexer, parser, AST, bytecode compiler, stack VM, capability-gated runtime, standard library, JSR-223 engine, embedding API, CLI and REPL, in one jar. |
| Root package | `org.jdesktop.lg3d.mandela` — `lang` · `code` · `vm` · `values` · `rt` · `api` · `cli` |
| Source layout | Maven-standard `src/main/java`, `src/test/java`, `src/main/resources` (not the `src/classes` layout the other modules use) |
| Language / encoding | Java 21, **UTF-8** (the root default) |
| Depends on | **Nothing** — no repo module, no third-party jar. `java.base` + `javax.script` only. |
| Depended on by | `lg3d-apps` (Espresso's `MandelaTools`, the Web Browser's `MandelaUserscriptsExtension`); its jar is also resolved onto `lg3d-core`'s `run` classpath and copied into the release bundle. |
| Jar | `build-gradle/libs/lg3d-mandela-<version>.jar`, `Main-Class: …mandela.cli.MandelaCli` |
| Build | `./gradlew :lg3d-mandela:build` |
| Run | `./gradlew :lg3d-mandela:mandelaRun -Pscript=hello.mnd` · `:lg3d-mandela:mandelaRepl` |
| Tests | `./gradlew :lg3d-mandela:test` — 366 headless JUnit 5 tests (`java.awt.headless=true`) |
| Mutation | `./gradlew :lg3d-mandela:pitest` (report-only, on-demand, **not** in `check`) |
| Docs | [`../docs/mandela-language.md`](../docs/mandela-language.md) (the grammar and the library) · [`../docs/mandela-embedding-api.md`](../docs/mandela-embedding-api.md) (the host contract) |

**Nature of the module:** a language *is* a contract. Every grammar decision,
every error message and every capability default is something shipped software
(the editor, the browser) and user-written `.mnd` scripts depend on. Correctness
here is measured by parsing and execution behaviour, not by anything rendered.

## How the roles work together

The Architect owns the package layering and the sandbox boundary; the Engineer
keeps the jar dependency-free and the grammar unambiguous; QA verifies through
the parser, the machine and the two host-facing surfaces (no live desktop needed,
which is why this module is the repo's best-tested one); the Business Analyst
guards "a script is the desktop's automation surface"; the Functional Analyst
keeps the grammar and the capability model explicitly rationalised; the PM
tracks the blast radius into `lg3d-apps`. A disagreement is settled in the PR,
never silently in code.

## Architect

- **Dependency direction:** `lang` is a leaf (it knows nothing above it). `code`
  and `values` are the inner pair — a compiled body is callable, so they
  reference each other and that is the one accepted cycle. `vm` runs on them,
  `rt` wires machine + library + capabilities, `api` is the **only** package a
  host should import, `cli` is outermost. Never let `lang`, `values` or `code`
  import `rt`, `api` or `cli`.
- **Zero dependencies is the feature.** No AWT, no Swing, no Java 3D, no
  lg3d-core, no library jar: that is what lets the same `Runtime` run inside a
  headless test, inside Espresso's JVM and inside a browser page. A single
  `import java.awt.` here breaks the module's reason for existing.
- The hosts live in `lg3d-apps`, not here. This module must never reference
  `org.jdesktop.lg3d.apps.*` or `org.jdesktop.lg3d.*` at all.
- `META-INF/services/javax.script.ScriptEngineFactory` is a published
  registration; the JSR-223 names and MIME types in `api.Mandela` are part of the
  contract for hosts that discover the engine.
- The CLI's exit codes (`0` ok, `1` usage, `2` the program failed, `3` a named
  file was unreadable, `4` `check` found a blocking diagnostic) are a contract
  with scripts and CI, not a convenience — they are the `MandelaCli` constants,
  and the embedding guide tabulates them.

## Engineer / Developer

- **The grammar is the single source of truth.** A host that needs keywords,
  diagnostics, an outline or completions calls `api.EditorServices` and
  `api.Mandela.check` — it never re-implements a regex. When you add syntax, the
  keywords, `EditorServices.vocabulary`, the language guide and the two shipped
  hosts all read the same tables automatically; if you find yourself painting a
  word in a host, you have built a second grammar.
- New keywords are **contextual by default**. `Keywords.RESERVED` is 34 words,
  pinned word-for-word by `EditorServicesTest`, and it grows only where the parser
  already has a position for the word; a soft
  keyword (`init`, `export`, `step`) costs no script its name. Never make a word
  reserved because it is convenient.
- An ambiguity must be resolved by a rule that is *visible in the error*, not by
  a heuristic that silently mis-parses. Precedent: the trailing lambda
  (`xs.map { x -> x * 2 }`) requires the block to open on the same line as the
  call **and** to carry a `->` header at nesting level one — which is exactly
  what keeps `for x in items() { … }` a loop body and `match f(2) { 1 => … }` a
  match block. Copy that reasoning style.
- Every side effect goes through `Capabilities.require(grant, what)` and every
  budget through the machine. A new stdlib function that touches the host must
  name the grant it needs in its error message; a grant with no consumer is
  documented as *reserved* rather than quietly hooked to something.
- Values cross the boundary only through `rt.HostValues` / `api.Interop`, and
  **refusing an arbitrary object is the security model** — do not "make
  binding easier" by reflecting over a POJO.
- Errors are `MandelaError` (kind + message, catchable in scripts) or
  `LangException` (carries a `Diagnostic`). Do not throw a bare
  `RuntimeException` at script-visible code: the host has nothing to show then.
- A `Diagnostic.rule` id is a host-facing contract, and there are exactly three
  today: `"syntax"` (the grammar cannot read it), `"undefined-name"` (a name with
  no declaration — routine while a buffer is being typed, so an editor marks it
  without stopping the run) and `"param-order"` (a warning). Add an id only where
  a shipped host branches on it, and document the whole set in `lang.Diagnostic`,
  both guides and `MandelaTools`' mapping in the same PR.
- Name resolution sees the **declared** class hierarchy: inside
  `class Meter() extends Counter`, a bare `total` or `bump(2)` is the base's
  member, own members win over inherited ones, and `Compiler.seedInherited` is
  where that walk lives. A base the compiler cannot see (a host binding) adds no
  names, so the honest `unknown name` stands and `this.total` is the documented
  escape hatch — do not "fix" that by string lookup at run time.
- Keep the jar's text honest: the javadoc examples are syntax the parser
  accepts. An example using `fn` or a trailing-lambda with an implicit `it` is a
  defect, because a host copies it.
- Warnings are disabled repo-wide and the tree is large; do not reformat
  unrelated files.

## QA

- Everything here is headless and fast: `./gradlew :lg3d-mandela:test`.
  Suites by surface — `SyntaxTest` (what parses, and the exact fragment of the
  message a mistake produces), `LanguageTest` (end-to-end semantics),
  `StandardLibraryTest` (every library member), `EmbeddingApiTest` +
  `InteropTest` + `ScriptEngineTest` (the host contract),
  `api/EditorServicesTest` (the editor surface), `CliTest` (the command line),
  `DocumentationTest` (the guide), `ShippedScriptsTest` (the scripts the
  repository ships in `../scripts/mandela`).
- **The language guide is a tested artifact.** `DocumentationTest` extracts every
  `` ```mandela `` block from
  [`../docs/mandela-language.md`](../docs/mandela-language.md), parses it with the
  real parser and compiles it, allowing only the `undefined-name` finding a
  fragment is entitled to (a value the prose introduced). A block that abbreviates
  code carries an elision marker (`…`) and is skipped, and the test fails if too
  few blocks are found, so a broken extractor cannot make it vacuously green.
- **The repository's own automation is tested too.** `ShippedScriptsTest`
  compiles every `.mnd` under
  [`../scripts/mandela/`](../scripts/mandela/) with **no** finding excused — a
  shipped script is a complete program, unlike a guide fragment — and pins the
  header conventions (shebang, one-line purpose, the `-s` sandbox it must be run
  with) that keep that directory usable. The scripts themselves run in CI as
  `:lg3d-mandela:repositoryChecks` (`docLinksCheck`, `releaseCheck`,
  `startMenuAudit`), which are intentionally *not* part of `check`: they audit the
  whole tree, so their failures belong to the step that ran them, not to this
  module's build.
- **A grammar change is a two-sided test**: add the accepted form *and* pin the
  construct that must not be swallowed by it. A parse that silently changes
  meaning is worse than one that fails.
- Error-message text is asserted by fragment, so a wording change is a test
  change — say so in the PR.
- Coverage and mutation: the 100% JaCoCo / 0-PIT-mutant targets in the root file
  are **not** enforced (report-only). Do not claim untested success; report what
  ran. Scene-graph rules do not apply — nothing here needs a live desktop or the
  in-JVM probe described in [`../lg3d-core/AGENTS.md`](../lg3d-core/AGENTS.md).
- When Gradle is memory-starved locally, `build-gradle/scratch/junit.sh`
  compiles the same sources and runs the suite in one JVM. Its output is build
  scratch and must never be committed.

## Business Analyst

- The customer is twofold: the desktop user who automates a task in a `.mnd`
  file, and the developer who embeds the language in an application. Both pay
  for the same promise — *fast, secure, light-weight, Java/Kotlin-compatible* —
  and neither pays for a new language feature that a host cannot reach through
  `api`.
- A capability story is a product story: "a page script cannot read your disk"
  is the feature that lets the Web Browser ship userscripts without a consent
  dialog beyond the existing content-script gate.

## Functional Analyst

- The functional contract is the grammar (§1–§8 of the language guide), the
  standard-library surface (§9), the capability model (§10) and the host API
  (the embedding guide). Behaviour changes are written as *what a script
  observes*: the kind of error, the value of the last expression, the grant named
  in the message.
- Keep the rationale for each rule living with the rule. If a construct is
  refused — no alternation in `match` arms, no alias on `use`, no implicit
  lambda parameter — the guide must say why, so the next author does not
  "restore" it as a polish item.
- If `Capabilities` gains a grant or a profile changes a budget, that is a
  security-relevant change: flag it to the Architect and the PM before code
  lands, and update both guides in the same PR.

## Project Manager

- Scope: a language change is **high-blast-radius, low-frequency** — two shipped
  hosts and every user script read it. Schedule grammar work with an explicit
  `lg3d-apps` impact review, and keep host work in the `lg3d-apps` PR it belongs
  to.
- Commit scope is `lg3d-mandela` (or `agents` for this file). Follow branch →
  commit → push → PR against `main`; never commit directly to `main`.
- Definition of done: `:lg3d-mandela:test` green, `:lg3d-apps:test` green (the
  hosts), both guides updated in the same PR, and no new dependency in
  `lg3d-mandela/build.gradle`.

## UI/UX

**Not applicable.** The module renders nothing: no `Component3D`, no
`SwingNode`, no Swing. The one user-visible surface it produces is *vocabulary* —
the keywords, completions and outline entries an editor paints — and that comes
from `api.EditorServices`, in a stable order, so `lg3d-apps` can present it
without inventing anything. UI/UX guidance for the surfaces lives in
[`../lg3d-core/AGENTS.md`](../lg3d-core/AGENTS.md) (the rulebook),
[`../lg3d-apps/src/classes/org/jdesktop/lg3d/apps/texteditor/AGENTS.md`](../lg3d-apps/src/classes/org/jdesktop/lg3d/apps/texteditor/AGENTS.md)
(Espresso) and
[`../lg3d-apps/src/classes/org/jdesktop/lg3d/apps/webbrowser/AGENTS.md`](../lg3d-apps/src/classes/org/jdesktop/lg3d/apps/webbrowser/AGENTS.md)
(the browser).

## Communication & coherence

- Single source of truth: this file for module specifics, the root
  [`../AGENTS.md`](../AGENTS.md) for build/exclusions/commits. If they conflict,
  the root file wins and this file gets fixed in the same PR.
- The language guide, the embedding guide and this file quote the same grammar.
  When the grammar changes, all three move in the same PR — a stale guide is a
  bug a user finds first, and `DocumentationTest` now finds it before they do.
- Every PR states: which construct or grant changed, the `lg3d-apps` host impact,
  and the verification run.

## Commit / PR

- Conventional Commit scope: **`lg3d-mandela`**. Types as in the root file;
  `feat` for a grammar or API capability, `fix` for a mis-parse or a wrong
  message, `docs` for guide-only work.
- Subject ≤ 50 chars in the imperative, body wrapped at 72 and explains *why* —
  a language decision without its rationale is a decision that gets reverted.
- Add a `CHANGELOG.md` bullet under `[Unreleased]`; do **not** bump the version
  (that is a separate chore PR).
- Stage only intended paths — never `git add -A` (`build-gradle/`, scratch
  harnesses and screencapture PNGs live in the tree). Sign with the repository
  committer identity (`Signed-off-by:`), branch off `main`, PR via `gh`, and
  wait for approval before merging.
