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
| [`startmenu-audit.mnd`](startmenu-audit.mnd) | Indexes every classpath location the assembled runtime exposes, then resolves every `resource:///…` and `java …` reference in the start-menu descriptors, separating live menu entries from inert ones. | 57 live menu items all resolve; **7 references sit in descriptors under directories the desktop never scans**, so they can never appear. |
| [`release-check.mnd`](release-check.mnd) | Compares the four canonical version references (Gradle coordinate, root `AGENTS.md`, `README.md`, `CHANGELOG.md`) and cross-checks `settings.gradle` against the module directories, in both directions. | The repository agreed with itself; it is the gate that keeps it that way before a release cut. |
| [`asset-report.mnd`](asset-report.mnd) | Weighs the artwork the desktop ships — totals, heaviest directories, largest files with dates, repeated file names — as a table or as JSON, optionally to a file. | 319 assets, ~41 MiB, with the 3D background models dominating; written as a report, not a gate. |
| [`userscript-new.mnd`](userscript-new.mnd) | Writes a new Web Browser userscript, in the `webPage()` host vocabulary, into the browser's userscript directory. | A Mandela program that authors Mandela programs for a *different* host — the generated script was then run under `Capabilities.webPage()` and logged the page title it saw. |

## Running them

The language needs no install, so the shortest path is the module's own classes:

```bash
./gradlew :lg3d-mandela:repositoryChecks          # the three gates, in one command
./gradlew :lg3d-mandela:docLinksCheck             # or one of them
./gradlew :lg3d-mandela:mandelaRun -Pscript=scripts/mandela/asset-report.mnd '-Pargs=json -s .'
```

Or the packaged jar, which is what a developer with the desktop built already
has — and, behind a one-line launcher on the `PATH`, what the `#!/usr/bin/env
mandela` shebang at the top of each script expects:

```bash
./gradlew :lg3d-mandela:jar
java -jar lg3d-mandela/build-gradle/libs/lg3d-mandela-1.68.0-dev.jar \
    run scripts/mandela/doc-links.mnd -s .

# the launcher the shebang resolves to, once the jar is where you keep it:
#   mandela() { exec java -jar …/lg3d-mandela-1.68.0-dev.jar "$@"; }
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
write nothing except when explicitly told to (`asset-report.mnd out=PATH`).

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
