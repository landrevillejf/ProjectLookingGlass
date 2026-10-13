# Mandela scripts

Automation for this repository, written **in Mandela** — the desktop's own
scripting language ([`docs/mandela-language.md`](../../docs/mandela-language.md)).

These are not samples. Each script does a job the project needed doing, runs
against the real tree, and prints findings a person would otherwise skim for.
They exist because the language ships a filesystem, an environment, JSON, a
clock and a sandbox in one dependency-free jar, and because a task written in
the desktop's own language is the cheapest proof that the language is complete
enough to be the desktop's automation surface.

| Script | What it does | What it found the first time it ran |
| --- | --- | --- |
| [`doc-links.mnd`](doc-links.mnd) | Resolves every relative link and `#anchor` in the repository's markdown, against the containing file, and verifies the anchor is a real heading. | **11 dead links** in 4 files — all repaired. Now green over 400+ links in 119 files; the count is what the tree holds that day, so the script is the authority, not this table. |
| [`html-links.mnd`](html-links.mnd) | The Pages site's other half: resolves every `href`/`src` in `documentation/**/*.html`, checks each `viewer.html?path=<file.md>` names a real document, and verifies every Mermaid fence in that tree is balanced and opens with a diagram keyword. | Ships green over the current site (61 HTML references in 11 files, 70 Mermaid blocks — the 11 include the 9 generated release pages, so a changelog page is audited like any other page of the site). It was proven against a planted dead link, a missing `src`, a non-diagram fence and an unclosed fence — each one fails, so the gate bites rather than merely passes. |
| [`startmenu-audit.mnd`](startmenu-audit.mnd) | Indexes every classpath location the assembled runtime exposes, then resolves every `resource:///…` and `java …` reference in the start-menu descriptors, separating live menu entries from inert ones. | 57 live menu items all resolve; **7 references sit in descriptors under directories the desktop never scans**, so they can never appear. |
| [`release-check.mnd`](release-check.mnd) | Compares the four canonical version references (Gradle coordinate, root `AGENTS.md`, `README.md`, `CHANGELOG.md`) and cross-checks `settings.gradle` against the module directories, in both directions. | The repository agreed with itself; it is the gate that keeps it that way before a release cut. |
| [`mandela-version.mnd`](mandela-version.mnd) | The same job for the language itself, which carries its own version rather than the desktop's: compares the two axes — `mandela.version` (the jar, the manifest, the artifact name) and `mandela.language` (the grammar a script may assume) — across the eight places they are written, with `gradle.properties` as the authority because that is what the build stamps the artifact with. | Ships green at jar `1.0.0`, language `1.0`; proven against a constant the gate had not been told about, which fails naming both copies and the axis they disagree on. |
| [`i18n-keys.mnd`](i18n-keys.mnd) | The shell bundle contract: every `Messages.tr("…")` literal in a production source exists in `messages.properties`, the French bundle carries exactly the same keys, and each key's `{n}` placeholders agree between the two. Also prints the per-surface key inventory. | Green over the first extraction (288 keys, 2763 sources); proven against a planted base-only key, which fails as "no French twin". 37 legacy Latin-1 incubator sources are unreadable as text and named as skipped. |
| [`legacy-j3d.mnd`](legacy-j3d.mnd) | The Java 3D migration rule as a gate: no source the build compiles may `import` a pre-migration package (`javax.media.j3d`, `javax.vecmath`, `com.sun.j3d`). Scoped so the exemption is honest — the do-not-edit `lg3d-docs` samples, the retired `lg3d-awt` toolkit and the two deserialisation shims that must keep the old names are all out of scope. | Ships green (the only legacy imports left in the tree are under `lg3d-docs`); proven against a planted `import javax.vecmath.*` in a built source, which the gate names by file and line. |
| [`scripts-hygiene.mnd`](scripts-hygiene.mnd) | The automation's own wiring: every `mandelaCheck(...)` gate points at a script that exists, every registered gate is in the `repositoryChecks` aggregate CI runs, and every shipped `.mnd` is documented in this table (and every table row names a file that still exists) — the rot that the compiler and Gradle each miss. | Ships green over the current wiring; proven by registering a gate the aggregate omits and by pointing a row at a deleted script, each of which fails. |
| [`whats-new.mnd`](whats-new.mnd) | Both halves of one job, for two audiences out of one document. As a gate it re-renders `CHANGELOG.md` and compares byte for byte against **every** committed page: the Help Center topic `lg3d-apps/…/help/helpcontent/whats-new.html` (the `[Unreleased]` section plus the three newest releases, cut to their headlines) and the release archive `documentation/releases/<version>.html` — one page per released version, full narrative, plus the `index.html` that lists them. A stale page, a missing page, or a page naming a version the changelog no longer carries all fail. With the `write` word it is the generator for all of them, output free of timestamps so the comparison is exact. One parse, two skins, three readers: the desktop's own Update Manager reads the very same document, shipped in the release bundle as `changelog.md`. | The first render turned 4 sections and 140 changelog bullets into the guide's nineteenth topic, and its inventory named **72 `Added` entries no other help topic mentions** — the honest list of where a real topic is still owed. The archive pass then wrote 9 more pages (8 released versions and their index) from the same parse, and the gate was proven against a changelog edit the topic did not carry and against a deleted release page. |
| [`asset-report.mnd`](asset-report.mnd) | Weighs the artwork the desktop ships — totals, heaviest directories, largest files with dates, repeated file names — as a table or as JSON, optionally to a file. | 319 assets, ~41 MiB, with the 3D background models dominating; written as a report, not a gate. |
| [`userscript-new.mnd`](userscript-new.mnd) | Writes a new Web Browser userscript, in the `webPage()` host vocabulary, into the browser's userscript directory. | A Mandela program that authors Mandela programs for a *different* host — the generated script was then run under `Capabilities.webPage()` and logged the page title it saw. |

## Running them

The language needs no install, so the shortest path is the module's own classes:

```bash
./gradlew :lg3d-mandela:repositoryChecks          # the nine gates, in one command
./gradlew :lg3d-mandela:docLinksCheck             # or one of them
./gradlew :lg3d-mandela:whatsNewRefresh           # a generator, not a gate: rewrite the help topic and the release archive
./gradlew :lg3d-mandela:mandelaRun -Pscript=scripts/mandela/asset-report.mnd '-Pargs=json -s .'
```

Or the packaged jar, which is what a developer with the desktop built already
has — and, behind a one-line launcher on the `PATH`, what the `#!/usr/bin/env
mandela` shebang at the top of each script expects:

```bash
./gradlew :lg3d-mandela:jar
java -jar lg3d-mandela/build-gradle/libs/lg3d-mandela-1.0.0.jar \
    run scripts/mandela/doc-links.mnd -s .

# the launcher the shebang resolves to, once the jar is where you keep it:
#   mandela() { exec java -jar …/lg3d-mandela-1.0.0.jar "$@"; }
```

Every script's own header repeats its invocation, because the invocation carries
the one decision that is not obvious: **the sandbox**.

## The sandbox rule that bites

`mandela run FILE` confines file access to **the directory the script sits in**.
That default is the security feature — a script dropped into the desktop cannot
read the home directory — but it means a checker living in `scripts/mandela`
cannot see the documents it is supposed to check unless told otherwise:

```bash
run scripts/mandela/doc-links.mnd -s .    # from the repository root
```

`-s .` is therefore part of every command above, and
`ShippedScriptsTest` fails any shipped script whose header stopped saying it.
`-u` / `--unrestricted` exists, and none of these scripts need it: a task that
walks a repository is exactly what a `fileRoot` was designed for. The scripts
write nothing except when explicitly told to — `asset-report.mnd` with an
`out=PATH` argument, and `whats-new.mnd` with its `write` mode word, which is the
only script here that rewrites a tracked file and the reason its gate exists.

## Exit codes

Taken from `MandelaCli`, tabulated in
[`docs/mandela-embedding-api.md`](../../docs/mandela-embedding-api.md): `0` success,
`1` bad command line, `2` the program failed (which includes a raised
`error(…)` — how these scripts report a finding), `3` a file was unreadable, `4`
`check` had a blocking diagnostic. CI branches on `0`.

## Guarded by the build

[`ShippedScriptsTest`](../../lg3d-mandela/src/test/java/org/jdesktop/lg3d/mandela/ShippedScriptsTest.java)
compiles every `.mnd` in this directory as part of
`./gradlew :lg3d-mandela:test`. Unlike a guide fragment, a shipped script is a
complete program, so **no** compiler finding is excused — not even
`undefined-name`, which is what makes a grammar change that would break real
automation fail the build instead of the next user. The same test pins the header
convention: a `#!/usr/bin/env mandela` first line, a `// name.mnd — purpose`
second line, and an invocation that names its sandbox.

## Adding a script

1. Write it here, with the two header lines above and the run command in its own
   comment block; `#!/usr/bin/env mandela` plus `chmod +x` makes it runnable once
   a `mandela` launcher is on the `PATH`.
2. Bind nothing you do not need: `args` is the host's list of words, and the
   gates raise `error(message, "ViolationError")` so CI prints one line.
3. Decide whether it is a **gate** (exits non-zero on a finding, belongs to
   `repositoryChecks` and to CI) or a **report** (always exits 0, prints for a
   human, may emit JSON). Say which one in the header, as the scripts above do.
4. If it is a gate, add it to the `mandelaCheck(...)` list in
   [`lg3d-mandela/build.gradle`](../../lg3d-mandela/build.gradle) and to the table
   above. The CI step runs the aggregate task, so it needs no workflow edit.
5. Run it before you commit — a finding the script reports is a claim to verify
   by hand, not a defect to trust.
