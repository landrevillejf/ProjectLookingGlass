# Copyright

- This project was created by Jean-Francois Landreville aka Dr. Land Evil, landrevillejf@protonmail.com, 2026, all rights reserved.

# Changelog

All notable changes to this **modernization port** of Project Mandela are
documented here. The format follows [Keep a Changelog](https://keepachangelog.com/),
grouped by Added / Changed / Removed / Fixed.

## Version policy

Mandela carries **its own** version, not the desktop's: the module depends on
nothing (`java.base` + `javax.script`), so it releases on its own clock and a host
that drops the jar on its classpath has to be told which language a script may
assume — which `1.69.0-dev` says about the desktop, not about this. Two axes:

| Axis | Written as | Names | Authority |
| --- | --- | --- | --- |
| **the jar** | `Mandela.IMPLEMENTATION_VERSION`, the manifest's `Implementation-Version`, the artifact name, the header below | what a host checks when it asks whether a bug is fixed here | `mandela.version` in [gradle.properties](../gradle.properties) |
| **the language** | `Mandela.LANGUAGE_VERSION`, the manifest's `Specification-Version`, the line under the [language guide](../docs/mandela-language.md) title | what a script may assume about the grammar and the runtime | `mandela.language` in [gradle.properties](../gradle.properties) |

Both are written in eight places, so [scripts/mandela/mandela-version.mnd](../scripts/mandela/mandela-version.mnd)
compares every copy against its property and fails `:lg3d-mandela:repositoryChecks`
when one disagrees. Bumping Mandela means editing the property, the two constants,
the guide line and a new header here — in one module-scoped commit. The desktop's
release train never rewrites Mandela's numbers; the desktop simply bundles whatever
jar this module produces.

## [Unreleased]

### Added

- **A `deck` starter template** in `EditorServices.starterTemplates()`: a
  console-safe program that assembles a small Presentoire slide deck as Markdown
  in a list and prints it, so Espresso's new *Presentoire Deck* action can drop a
  runnable deck skeleton into an empty buffer. It prints rather than writes,
  because the language depends on nothing and Espresso runs the console profile;
  `scripts/mandela/deck-new.mnd` is the sandboxed generator that writes the `.md`
  file. The grammar is untouched, so `mandela.language` stays `1.0` and no version
  copy moves.

## [1.1.0] — 2026-10-13

The first release on the module's own clock after the version identity landed.
Everything in it is the *Next* horizon of
[docs/ROADMAP.md](docs/ROADMAP.md) §3.1: stdlib members and tooling the shipped
gates were hand-rolling. The grammar is untouched, so `mandela.language` stays
`1.0` — a script written for 1.0 runs here unchanged, and only a script that
asks for the new members needs this jar.

### Added

- **String value members** — `s.count(needle)`, `s.splitOnce(sep)`,
  `s.isBlank()`, `s.removePrefix(p)` / `s.removeSuffix(s)`, `s.lines()` and
  `s.words()`: the occurrence scan, the first-pair `key=value` split and the
  trim-to-empty test each gate in `scripts/mandela/` repeated by hand.
  `lines()` splits on LF, CRLF or CR alike.
- **List value members** — `xs.count()` (bare: truthiness count; with a
  predicate: indexed callback count) and `xs.partition(pred)`, the report-and-
  split pair behind the gates' `filter` + re-filter pattern.
- **`std.glob`** — `glob.matches(text, pattern)` and `glob.isMatch(pattern,
  text)`: the `*`/`?` matcher of the browser userscript host, exposed as a
  scriptable module. Segment-scan by construction, never backtracking, so it
  stays inside the step budget the way a regular expression could not (the
  guide's §12 still says no regex; this is the answer to that want).
- **`scripts/mandela/changelog-check.mnd`** — a tenth gate: `CHANGELOG.md` is
  read by three readers (the Help Center topic, the Pages archive, the Update
  Manager's dialog), and a malformed header is invisible to all of them until
  it renders wrong. The gate reads the document the way they do — `[Unreleased]`
  first and once, `X.Y.Z` labels in descending order with ISO dates, only the
  six Keep-a-Changelog groups, no empty release.
- **`scripts/mandela/todo-scan.mnd`** — a report, not a gate (always exits 0,
  like `asset-report.mnd`): the tree's open `TODO`/`FIXME` markers across the
  root notes and the Java sources the build compiles, with build output, the
  vendored ANTLR generator and the two trees no build compiles (`lg3d-docs`,
  the retired `lg3d-awt`) deliberately out — the same exemption vocabulary
  `legacy-j3d.mnd` states. Measured on this tree: 520 markers across 253
  files, read out of 799,773 lines in 4,099 sources, 38 undecodable files
  named as the blind spot. Writing it found the sandbox's own edge: asking
  each line `contains(marker + ":")` allocates four strings a line, and over
  860k lines that spent the whole 50M-step `desktop(root)` budget before the
  walk finished — the same question asked against two literal `contains` is
  what fits, with the occurrences enumerated only on the lines that mention a
  marker at all.
- **Guide and tables** — `docs/mandela-language.md` documents every member
  above in the Str/List tables and carries a new §9 section for `std.glob`,
  with its code fence compiled by `DocumentationTest`.
- **The suite that proves it** — 117 cases (1142 → **1259** in the module's one
  `test` task): every new member answered from a script, `std.glob` read at its
  edges (a candidate shorter than its pattern, a tail whose ground the head
  already consumed, consecutive stars, a starless literal mismatch), and the
  two-arity `count` callback indexed like `map`'s. Writing them is what found
  the matcher's one real defect: the head segment was cut by where the pattern's
  first star sits without asking the text how long it was, so
  `glob.matches("a", "abc*")` threw an index error instead of answering `false`.
  The bound is now checked before the walk, and the run's length is derived from
  the segment rather than passed beside it — which is how the mistake became
  unrepresentable rather than merely untested.

### Changed

- The artifact name is `lg3d-mandela-1.1.0.jar`; `Mandela.IMPLEMENTATION_VERSION`
  and the manifest's `Implementation-Version` follow it. `mandela-version.mnd`
  gates all eight copies over the bump.
- The module's JaCoCo floor moves with the numbers it is set under: 0.905 / 0.800
  → **0.910 / 0.815** (measured 91.49 % line / 81.86 % branch). `rt/StandardLibrary`
  stays at every line covered, and the new members' branches are covered from both
  sides rather than only the true half — the two outcomes that hid the glob defect
  and the carriage return that ends a text are both line-covered today and were
  both wrong yesterday, which is the argument for pinning branches and not lines.

## [1.0.0] — 2026-10-13

The first version the language carries as its own. Everything below is the record
of `lg3d-mandela` and `scripts/mandela` in this repository's git history (dates are
when the work landed, not when it was written); the same record, told as prose with
its commit SHAs, is [docs/HISTORY.md](docs/HISTORY.md).

### Added

- **The whole tool chain, in one commit** (2026-10-10, `20597a07`, #325): lexer,
  recursive-descent parser, AST, bytecode compiler and stack VM, the
  capability-gated runtime and its standard library (the `std.fs`, `std.json`,
  `std.math` and `std.time` modules, plus the text, list and map members every
  value answers to), the JSR-223 `ScriptEngine`, the embedding API
  (`Mandela`, `Runtime`, `Capabilities`, `Interop`), and the `mandela` CLI with its
  REPL — no third-party dependency and no `lg3d-core` import, which is what lets
  the same jar answer in a headless test, inside a tool's JVM and inside a browser
  page.
- **`scripts/mandela/`, the repository's automation written in the language it
  ships** (2026-10-10, `db722813`, #326): `doc-links.mnd`, `release-check.mnd`,
  `startmenu-audit.mnd`, `asset-report.mnd` and the `userscript-new.mnd` generator,
  aggregated as `:lg3d-mandela:repositoryChecks`.
- **`html-links.mnd`** (2026-10-11, `8e646a03`, #334) — the gate for the Pages
  documentation site: every `href`/`src` resolves, every `viewer.html?path=` names
  a document that exists, every Mermaid fence is balanced. The language auditing
  the site that documents it.
- **The module's own guides** (2026-10-11, `14a1d125`, #336):
  `ARCHITECTURE.md`, `DEVELOPMENT.md` and `FUNCTIONAL_ANALYSIS.md` — the numbered
  requirements (`FR1`–`FR9`) the language promises against.
- **Cucumber BDD over the real surfaces** (2026-10-11, `e4676f40`): the `.feature`
  file is a second, executable statement of the language guide, and it runs inside
  the module's `test` task.
- **`values` covered from both sides** (2026-10-11, `ac5d8cac`, `bb85aa99`): the
  operator table answered row by row, from a script and from the host.
- **Tests that address the machine in its own instruction set** (2026-10-12,
  `681e2917`): a bytecode script is disassembled back to the mnemonics the shipped
  disassembler prints, so a test names opcodes instead of imitating them.
- **`i18n-keys.mnd`** (2026-10-12, `00b8740f`), **`legacy-j3d.mnd`** and
  **`scripts-hygiene.mnd`** (2026-10-12, `9aa99ca3`) — the shell-bundle contract,
  the Java 3D migration rule and the automation's own wiring, each as a gate.
- **`whats-new.mnd`** (2026-10-12, `4d1f7366`): the language generates a page of
  the desktop's own Help Center from `CHANGELOG.md` and fails the build when the
  shipped topic goes stale.
- **`docs/HISTORY.md`** (2026-10-12, `cdd566a0`) — why the name, who made it, and
  the milestone record — and **`docs/USAGE.md`** (2026-10-12, `322e9dd2`), the
  language at work with real transcripts of the scripts.
- **The second render target** (2026-10-13, `3ed4fd5e`, #378): `whats-new.mnd` now
  also draws one page per released version under `documentation/releases/`, with the
  archive index and the portal card, so the changelog feeds the dialog, the help
  topic and the site from one source.
- **Mandela's own version identity** (this release): `mandela.version` and
  `mandela.language` in [gradle.properties](../gradle.properties) name the jar and
  the language independently of the desktop, the jar manifest carries both, and
  `mandela-version.mnd` gates the eight places they are written.
- `Str.indexOf` / `List.indexOf` honour a start index (2026-10-12, `589182b1`,
  #354).

### Changed

- **Three retirements, each found by asking what can reach it** (2026-10-11 → 12,
  `440bfab3`, `7f6ab09f`, `1401efdf`): the AST node no syntax can produce, the
  value surface no receiver can reach, and the five opcodes no compiler can emit.
- The interpreter's limits (`padStart`, `padEnd`, `text.repeat`) honour the same
  text ceiling as everything else (2026-10-12, `9aa99ca3`).

### Fixed

- An overflowing `Int` promotes instead of losing its operand (2026-10-11,
  `a0375296`).
- A closure capture is handed to the function that asks for it (2026-10-11,
  `d108a371`).
- A host function's answer is converted at the machine (2026-10-11, `001a1cfc`).
- The `json` module is as strict as it claims to be (2026-10-11, `65f98143`).
- The standard library's edge cases answer as the guide promises (2026-10-11,
  `2f404507`).
- A range counts along its stride, and values compare all the way down (2026-10-11,
  `7b033c71`).
- The VM tells the truth about the arities it refuses (2026-10-12, `f8054454`).
- `release-check.mnd` tolerates the release-cut and the bare `[Unreleased]` header
  shapes (2026-10-11, `883bf5a4`, `c1c1e8bf`).
