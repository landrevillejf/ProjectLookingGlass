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
| [`accelerators.mnd`](accelerators.mnd) | The keyboard-shortcut gate, aimed at a chord that is written correctly and still dead. It parses the shell's own global table (`ShortcutMap.defaultBindings`, expanding the `alt shift 1..9` workspace loop against the real `WorkspaceModel.MAX_COUNT`) and scans every production source for a keystroke-spec literal, then fails on three provable defects: a window-manager grab (a Super/Meta chord, or Ctrl+Alt+arrow, which Mutter eats before the JVM sees it), a binding that shadows the window switcher's Alt+`` ` `` (which `ShortcutMap` must leave unbound), and two shell chords that normalise to one stroke. An application chord that merely overlaps a live shell chord, or that two applications both bind, is printed for a human to judge but never fails. | Ships green (no dead chord); it prints **11 overlaps for review** — `control alt D`/`T`, `control shift F` and `ctrl W` shadowed by the live shell table, and `control F`/`ctrl TAB`/`ctrl T` shared between the text editor, the browser and the mail app — the honest schedule a maintainer works through, not a red build. |
| [`release-check.mnd`](release-check.mnd) | Compares the four canonical version references (Gradle coordinate, root `AGENTS.md`, `README.md`, `CHANGELOG.md`) and cross-checks `settings.gradle` against the module directories, in both directions. | The repository agreed with itself; it is the gate that keeps it that way before a release cut. |
| [`mandela-version.mnd`](mandela-version.mnd) | The same job for the language itself, which carries its own version rather than the desktop's: compares the two axes — `mandela.version` (the jar, the manifest, the artifact name) and `mandela.language` (the grammar a script may assume) — across the eight places they are written, with `gradle.properties` as the authority because that is what the build stamps the artifact with. | Ships green at jar `1.1.0`, language `1.0`; proven against a constant the gate had not been told about, which fails naming both copies and the axis they disagree on. |
| [`i18n-keys.mnd`](i18n-keys.mnd) | The shell bundle contract: every `Messages.tr("…")` literal in a production source exists in `messages.properties`, the French bundle carries exactly the same keys, and each key's `{n}` placeholders agree between the two. Also prints the per-surface key inventory. | Green over the first extraction (288 keys, 2763 sources); proven against a planted base-only key, which fails as "no French twin". 37 legacy Latin-1 incubator sources are unreadable as text and named as skipped. |
| [`legacy-j3d.mnd`](legacy-j3d.mnd) | The Java 3D migration rule as a gate: no source the build compiles may `import` a pre-migration package (`javax.media.j3d`, `javax.vecmath`, `com.sun.j3d`). Scoped so the exemption is honest — the do-not-edit `lg3d-docs` samples, the retired `lg3d-awt` toolkit and the two deserialisation shims that must keep the old names are all out of scope. | Ships green (the only legacy imports left in the tree are under `lg3d-docs`); proven against a planted `import javax.vecmath.*` in a built source, which the gate names by file and line. |
| [`scripts-hygiene.mnd`](scripts-hygiene.mnd) | The automation's own wiring: every `mandelaCheck(...)` gate points at a script that exists, every registered gate is in the `repositoryChecks` aggregate CI runs, and every shipped `.mnd` is documented in this table (and every table row names a file that still exists) — the rot that the compiler and Gradle each miss. | Ships green over the current wiring; proven by registering a gate the aggregate omits and by pointing a row at a deleted script, each of which fails. |
| [`whats-new.mnd`](whats-new.mnd) | Both halves of one job, for two audiences out of one document. As a gate it re-renders `CHANGELOG.md` and compares byte for byte against **every** committed page: the Help Center topic `lg3d-apps/…/help/helpcontent/whats-new.html` (the three newest **released** versions, cut to their headlines — never `[Unreleased]`, which is not in the build a reader runs) and the release archive `documentation/releases/<version>.html` — one page per released version, full narrative, plus the `index.html` that lists them. A stale page, a missing page, or a page naming a version the changelog no longer carries all fail. With the `write` word it is the generator for all of them, output free of timestamps so the comparison is exact. One parse, two skins, three readers: the desktop's own Update Manager reads the very same document, shipped in the release bundle as `changelog.md`. | The first render turned 4 sections and 140 changelog bullets into the guide's nineteenth topic, and its inventory named **72 `Added` entries no other help topic mentions** — the honest list of where a real topic is still owed. The archive pass then wrote 9 more pages (8 released versions and their index) from the same parse, and the gate was proven against a changelog edit the topic did not carry and against a deleted release page. |
| [`changelog-check.mnd`](changelog-check.mnd) | The tenth gate, for the changelog's shape: `[Unreleased]` first and once, `X.Y.Z` release labels in descending order with ISO dates, only the six Keep-a-Changelog groups, and no released section with nothing under it — because three readers parse `CHANGELOG.md` (the help topic, the Pages archive, the Update Manager) and a header they cannot parse is a version that silently disappears from all of them. | Ships green over the current changelog; proven against a planted ascending version, an unknown group name and a dateless header, each of which it names by file and line. |
| [`run-classpath.mnd`](run-classpath.mnd) | The two hand-written copies of one desktop classpath. `:lg3d-core:run` assembles its classpath by hand, because the desktop launches start-menu apps in its own JVM and a Maven dependency of `lg3d-apps` never reaches it on its own; `releaseBundle` then writes the same list a second time for the released `lib/*` wildcard. Seven checks compare the copies in both directions: the module jars each side carries, the jars shipped outside `lib/` and the `systemProperty` that hands the dev run the same jar by path, every `settings.gradle` module that neither list carries and no exemption names, the third-party coordinates, the vendored `ext/` allow-list (both copies must agree and every jar named must exist on disk), and whether the run task actually depends on the jars it puts on the classpath. | Found **3 real gaps** on the tree that produced it: the release bundle shipped neither `lg3d-terminal.jar` nor `jna` / `jna-platform`, although the run classpath had carried all three since PR #428 — `./gradlew :lg3d-core:run` opened the Terminal, and the released desktop would have died with `NoClassDefFoundError` on the first shell. All three ship fixed in the same PR; green now over 17 module(s) on both sides and 33 coordinates. |
| [`coverage-floors.mnd`](coverage-floors.mnd) | What the root `coverageFloors` map is allowed to claim. A module named there gets a `jacocoTestCoverageVerification` rule inside `check`; a module absent from it gets none. So a suite with no floor is a coverage regression that cannot fail the build, and a floor for a module with no test source is a rule that can never fire — protection advertised, not delivered. A suite means at least one `.java` under `src/test/java`, because an empty directory makes the test task NO-SOURCE and JaCoCo never runs; a module with a suite and a deliberate exemption must carry its reason in the script; and the floors quoted in root `AGENTS.md` prose are compared against the map the build actually enforces. | Ships green over 16 floor(s) in 19 module(s), one documented exemption (`lg3d-apps`, whose panels are probe-verified against a live desktop) and 3 floors quoted in `AGENTS.md`. Proven to bite in both directions at once: moving `lg3d-widgets`' floor onto `lg3d-terminal` fails twice in one run — "has 13 test source(s) and no floor" and "has a floor and no test source under `src/test/java`, so its `jacocoTestCoverageVerification` can never fail". |
| [`exclusions.mnd`](exclusions.mnd) | The exclusion set, checked as one set. AGENTS.md names the code paths the build leaves out — the retired AWT peer toolkit, the `sun.awt` shims, the native X11 integration, the unused RMI scene-graph transport, the ODE nodes, the incubator apps whose libraries are gone — and every module's `build.gradle` writes the same fact again as `exclude 'org/...'` lines inside a source set's `java` block, which the document itself admits has drifted before ("Always verify the live exclusion set against `lg3d-incubator/build.gradle`"). Four checks: an exclude no prose explains, a prose token no build file applies, an exclude whose tree holds no source (or is documented in `KEPT` as a deliberate guard over a tree the port never carried), and an app under "Ported, not excluded" that is nonetheless excluded. The two vocabularies are matched on path segments, not strings, so `sun/**` explains `sun.awt.*`, `apps/browser/**` explains the build's own spelling, and `apps/browser/**` never matches `apps/browser3d/**`. | Ships green with 14 source exclude(s) paired 1:1 with 14 prose tokens and the 4 ported claims clear of the exclude set. It found **1 real dead pattern** on the tree that produced it: `org/jdesktop/lg3d/apps/x11integration/**`, excluded by `lg3d-core/build.gradle` and named in four documents, has no sources anywhere in the repository and none in its whole git history — the line is inherited from the 2006 `build.xml`. It stays, recorded in `KEPT` with that reason, as the guard that keeps a restored legacy tree out of the build. Proven by planting an undocumented exclude, a deleted one the prose still claims, and an exclude on a ported app: 5 findings in one run. |
| [`asset-report.mnd`](asset-report.mnd) | Weighs the artwork the desktop ships — totals, heaviest directories, largest files with dates, repeated file names — as a table or as JSON, optionally to a file. | 319 assets, ~41 MiB, with the 3D background models dominating; written as a report, not a gate. |
| [`todo-scan.mnd`](todo-scan.mnd) | Inventories the tree's open `TODO`/`FIXME` markers — the root's markdown notes and the Java sources the build actually compiles, never build output, the vendored ANTLR generator or the two trees nobody compiles (`lg3d-docs`, the retired `lg3d-awt`) — as a count with a `file:line` listing. | 520 open markers across 253 files, read out of 799,773 lines in 4,099 sources; 38 files do not decode as text and are named as the blind spot they are. A report, not a gate: like `asset-report.mnd` it always exits 0 and the reader decides what the list means. It is also the script that taught the budget: the same question asked with a per-line `contains(marker + ":")` allocated four strings a line and spent the whole 50M-step allowance before it finished. |
| [`userscript-new.mnd`](userscript-new.mnd) | Writes a new Web Browser userscript, in the `webPage()` host vocabulary, into the browser's userscript directory. | A Mandela program that authors Mandela programs for a *different* host — the generated script was then run under `Capabilities.webPage()` and logged the page title it saw. |
| [`deck-new.mnd`](deck-new.mnd) | Writes a starter Presentoire slide deck as Markdown (`.md`): a title slide plus one slide per further argument, split on a horizontal rule, in the shape Presentoire's Markdown codec reads back. | A Mandela program that authors a deck for a *different* host — the generated `.md` was opened through Presentoire's Markdown codec as the very slides the arguments named. |
| [`startmenu-categories.mnd`](startmenu-categories.mnd) | Reads each live start-menu descriptor's name and description, scores them against a keyword vocabulary for the eight groups the menu uses, and prints the items whose declared `menuGroup` is beaten by a different group's evidence. A report, not a gate: category is a judgement a maintainer weighs, never a build failure (the `repositoryChecks` gate proves a reference *resolves*; this one only suggests where it belongs). | Of 60 live items, 50 sit in the group their own description supports, 2 are too terse to judge and 5 declare no group; **3 flagged for review** — Image Studio and Paint filed under Utilities though both read as Media, and Mail 3D's "Real IMAP/SMTP mail client" reading more like Internet than Office. |

## Running them

The language needs no install, so the shortest path is the module's own classes:

```bash
./gradlew :lg3d-mandela:repositoryChecks          # the fourteen gates, in one command
./gradlew :lg3d-mandela:docLinksCheck             # or one of them
./gradlew :lg3d-mandela:whatsNewRefresh           # a generator, not a gate: rewrite the help topic and the release archive
./gradlew :lg3d-mandela:mandelaRun -Pscript=scripts/mandela/asset-report.mnd '-Pargs=json -s .'
```

Or the packaged jar, which is what a developer with the desktop built already
has — and, behind a one-line launcher on the `PATH`, what the `#!/usr/bin/env
mandela` shebang at the top of each script expects:

```bash
./gradlew :lg3d-mandela:jar
java -jar lg3d-mandela/build-gradle/libs/lg3d-mandela-1.1.0.jar \
    run scripts/mandela/doc-links.mnd -s .

# the launcher the shebang resolves to, once the jar is where you keep it:
#   mandela() { exec java -jar …/lg3d-mandela-1.1.0.jar "$@"; }
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
