# History of the Language

This page records why the language is called **Mandela**, who made it, and the
documented record of how it entered Project Looking Glass. It is history, not
specification: what the language *does* is in the [language guide](../../docs/mandela-language.md),
and how it is built is in [ARCHITECTURE.md](ARCHITECTURE.md).

## The name

Project Mandela is named in honour of a south african president who died in prison in the early 90's.

## Who made it

Project Mandela was created by **Jean-François Landreville**, who publishes
under the name **Dr. Land Evil** (landrevillejf@protonmail.com), for
Project Looking Glass. The module's copyright line reads
*Copyright © 2026 Jean-François Landreville (Dr. Land Evil). All rights
reserved*, and the same attribution heads the module's own
[CHANGELOG](../../CHANGELOG.md).

## Why the desktop has its own language

Project Looking Glass already ran on a JVM, so any embedded engine could have
been bought. Four reasons argued for writing one, and the first three are
numbered requirements of
[FUNCTIONAL_ANALYSIS.md](FUNCTIONAL_ANALYSIS.md):

- **It must be safe to run a stranger's script.** The Web Browser executes
  userscripts and Espresso executes whatever file is open. A page's script
  gets `webPage()` - no files, no environment, no console, no host types, no
  network - and that guarantee has to be a property of the engine, not of a
  policy wrapped around someone else's interpreter.
  **FR4.1, FR4.3, FR4.9.**
- **Nothing may be downloaded to run the desktop.** The module depends on
  `java.base` and `javax.script` and on no third-party artifact, which is
  also why the same jar answers in a headless test, inside a tool's JVM and
  inside a browser page. **FR9.1.**
- **A runaway script must die, not hang the desktop.** The interpreter counts
  retired instructions against a budget and raises `LimitError` at
  `maxSteps`, and a wall-clock deadline ends a script blocked on a host
  callback. A bought-in engine would have had to be guarded from the
  outside. **FR4.5, FR4.6, FR4.8, FR8.2.**
- **The desktop's own automation should be a customer.** The scripts that
  audit this repository are written in the language they ship in; that
  dogfooding is what found most of the defects listed below. This one is a
  convention, not a requirement - and it is the reason the language has a
  future beyond the desktop it was written for.

## The record in this repository

The language landed **whole, in one commit**, and then went to work on itself.
These are dates from the git history of `lg3d-mandela` and `scripts/mandela`;
they record when the work became part of the repository, not when it was
written.

| Date | Commit | What landed |
| --- | --- | --- |
| 2026-10-10 | `20597a07` (#325) | The module: lexer, parser, AST and bytecode compiler, stack VM, capability-gated runtime, standard library, JSR-223 engine, embedding API, CLI and REPL. |
| 2026-10-10 | `db722813` (#326) | `scripts/mandela/` - the repository's own automation rewritten in Mandela (`doc-links`, `release-check`, `startmenu-audit`, `asset-report`, `userscript-new`), with `:lg3d-mandela:repositoryChecks` as the aggregate gate. |
| 2026-10-11 | `8e646a03` (#334) | `html-links.mnd`, the gate for the published documentation site - the language auditing the site that documents it. |
| 2026-10-11 | `14a1d125` (#336) | The module guides: [ARCHITECTURE.md](ARCHITECTURE.md), [DEVELOPMENT.md](DEVELOPMENT.md) and [FUNCTIONAL_ANALYSIS.md](FUNCTIONAL_ANALYSIS.md) - the module's first written architecture, contributor and requirements documents. |
| 2026-10-11 | `e4676f40` | The language driven through Cucumber BDD scenarios, so the Gherkin file is a second, executable statement of the guide. |
| 2026-10-11 | `ac5d8cac`, `bb85aa99` | The `values` package covered from the script *and* from the host; every row of the operator table answered. |
| 2026-10-11 | `a0375296`, `d108a371`, `001a1cfc`, `65f98143`, `2f404507`, `7b033c71` | The defect campaign the tests started: an overflowing `Int` now promotes instead of losing the operand, a closure capture is handed to the function that asks for it, a host function's answer is converted at the machine, the `json` module is as strict as it claims, the standard library's edge cases answer as the guide promises, and a range counts along its stride while values compare all the way down. |
| 2026-10-11 → 12 | `440bfab3`, `7f6ab09f`, `1401efdf` | Three retirements, each found by asking what can reach it: the AST node no syntax can produce, the value surface no receiver can reach, and the five opcodes no compiler can emit. |
| 2026-10-12 | `f8054454` | The VM tells the truth about the arities it refuses. |
| 2026-10-12 | `681e2917` | Tests address the machine **in its own instruction set**: a bytecode script is disassembled back to the mnemonics the shipped disassembler prints, so the test names the opcodes rather than Groovy that imitates them. |
| 2026-10-12 | `00b8740f` | `i18n-keys.mnd`, landing with the French desktop shell it audits: every key a call site asks for is in the bundle, and every translated pattern keeps its `{0}` placeholders. |
| 2026-10-12 | `9aa99ca3` | The two remaining core seams hardened: `legacy-j3d.mnd` and `scripts-hygiene.mnd` as gates, and the interpreter's limits (`padStart`, `padEnd`, `text.repeat`) made to honour the same text ceiling. |
| 2026-10-12 | `4d1f7366` | `whats-new.mnd` - the language now writes a page of the desktop's own Help Center, generating a JavaHelp topic from `CHANGELOG.md` and failing the build when the shipped topic goes stale. |
| 2026-10-13 | `3ed4fd5e` (#378) | The same script gains a second audience: `CHANGELOG.md` now also draws the release archive under `documentation/releases/`, one page per released version plus its index, so one document answers the Update Manager's dialog, the Help Center's topic and the Pages site - with the gate proving every page still matches the changelog it came from. |
| 2026-10-13 | `a9b8f1b2` (#379) | Mandela's own version identity: `mandela.version` and `mandela.language` in `gradle.properties`, the same two numbers in the jar manifest, this module's changelog filled in from the record above, and `mandela-version.mnd` gating the eight places they are written - because a zero-dependency language releases on its own clock, and `1.69.0-dev` says nothing a host needs to know. |
| 2026-10-13 | this PR | The language **1.1.0** - the first release on that own clock, and the whole of the roadmap's next horizon: string members (`count`, `splitOnce`, `isBlank`, `removePrefix` / `removeSuffix`, `lines`, `words`), list members (`count`, `partition`) and `std.glob`, each one a scan a shipped gate was hand-rolling, plus two new programs - `changelog-check.mnd`, the tenth gate, guarding the shape `CHANGELOG.md` must keep for the three readers that parse it, and `todo-scan.mnd`, a report of the tree's open markers. The grammar is untouched, so only the jar's version moved. |

Measured on the day this page was written, the module is 45 Java files and
18,885 lines in `src/main/java`, and the repository's automation is 13 shipped
scripts in [`scripts/mandela/`](../../scripts/mandela/README.md), 10 of them
wired gates. Every date above is `git log`, and every count is the tree; none
of it is remembered.

## Where it runs today

- **Espresso**, the text editor - run a script, check syntax, view bytecode.
- **The Web Browser** - userscripts, authored with `userscript-new.mnd` and
  gated by the security model described in the
  [embedding guide](../../docs/mandela-embedding-api.md).
- **The repository's own CI** - the eleven gates in
  [`scripts/mandela/README.md`](../../scripts/mandela/README.md) run on every
  push in [`.github/workflows/build.yml`](../../.github/workflows/build.yml).
- **The Help Center** - `whats-new.mnd` generates the guide's What's New topic.
- **Any JVM** - through the JSR-223 `ScriptEngine`, the embedding API or the
  CLI and REPL.

## Reading order

- [README.md](README.md) - the module index and quick start
- [ARCHITECTURE.md](ARCHITECTURE.md) - how it is built and why it is built that way
- [DEVELOPMENT.md](DEVELOPMENT.md) - how to work on it
- [FUNCTIONAL_ANALYSIS.md](FUNCTIONAL_ANALYSIS.md) - what it promises, as numbered
  requirements (`FR1`-`FR9`), use cases and error model
- [Language guide](../../docs/mandela-language.md) and
  [Embedding API guide](../../docs/mandela-embedding-api.md)
- [AGENTS.md](../AGENTS.md) - the module's rules for its maintainers

---

Copyright © 2026 Jean-François Landreville (Dr. Land Evil). All rights reserved.
