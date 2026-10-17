# Mandela at Work

What the language is *for* in Project Looking Glass, shown with programs that
run. This page is a demonstration, not a reference: the syntax and the whole
standard library are in the
[language guide](../../docs/mandela-language.md), and the module's internals are
in [ARCHITECTURE.md](ARCHITECTURE.md). Every block below is a program from this
repository or one run for this page, and every `text` block is output the tools
produced while it was written - nothing here is written from memory.

## The shape of a desktop program

Almost every script in the desktop does four things, in this order: **walk** a
tree, **scan** text it found, **classify** what it read, **answer** - by printing,
by writing a file, or by failing with one line CI can show. Here is a program that
does all four in about 80 lines. It is this page's own example rather than a
shipped tool, so nothing here would change the counts it prints: save it anywhere
the sandbox reaches (`build-gradle/scratch/` is gitignored and is where the
transcript below was made) and run it from the repository root. The `println`
lines that produce each of the four sections are left out of the listing; the
program is otherwise exactly what was run.

```mandela
use std.fs
use std.env
use std.time
use std.json

let SKIP = [".git", "build", "build-gradle", "node_modules", "out"]

// 1. WALK. There is no fs.walk: `fs.list` answers the names in a directory,
//    sorted, and `fs.isDirectory` says whether to go deeper. A skip-list is what
//    keeps a walk out of the build output.
fun programsUnder(dir, found) {
    for (name in fs.list(dir)) {
        if (SKIP.contains(name)) {
            continue
        }
        let path = "${dir}/${name}"
        if (fs.isDirectory(path)) {
            programsUnder(path, found)
        } else if (name.endsWith(".mnd")) {
            found.push(path)
        }
    }
    return found
}

// 2. SCAN. No regular expressions, so a scan is a loop over lines: test the
//    marker, then take what sits between the first pair of quotes. `indexOf`
//    takes a start index, so the closing quote is asked for directly.
fun gateNames(path) {
    let names = []
    for (line in fs.lines(path)) {
        let t = line.trim()
        if (!t.startsWith("mandelaCheck(")) {
            continue
        }
        let open = t.indexOf("'")
        if (open < 0) {
            continue
        }
        let close = t.indexOf("'", open + 1)
        if (close > open) {
            names.push(t.substring(open + 1, close))
        }
    }
    return names
}

// 3. CLASSIFY. `match` is an expression, arms are tried in order, `_` catches
//    the rest, and no arm matching raises MatchError rather than answering null.
fun kindOf(name) {
    return match (name) {
        "whats-new" => "gate and generator",
        "asset-report" => "report",
        "todo-scan" => "report",
        "userscript-new" => "writes a program",
        _ => "gate"
    }
}

// 4. ANSWER. The host's own context, and a JSON document that survives the
//    round trip, because a machine reads the last line of a report.
let scripts = programsUnder("scripts/mandela", []).sort()
let gates = gateNames("lg3d-mandela/build.gradle")
var who = "(not granted)"
try {
    who = env.get("USER", "(USER is not set)")
} catch e: PermissionError {
    who = "refused by the host (${e.kind})"
}
let summary = {"programs": length(scripts), "gates": length(gates), "runBy": who,
               "at": time.format(time.now(), "yyyy-MM-dd HH:mm")}
println(json.stringify(summary))
```

Run from the repository root - `-s .` is not a nicety, it is the sandbox:
`run` confines file access to the script's own directory unless the host says
otherwise.

```bash
mandela run build-gradle/scratch/mandela-at-work.mnd -s .
```

```text
== what the repository ships in the language ==
   scripts/mandela/asset-report.mnd  5929 bytes
   scripts/mandela/changelog-check.mnd  6389 bytes
   scripts/mandela/doc-links.mnd  5969 bytes
   scripts/mandela/html-links.mnd  8243 bytes
   scripts/mandela/i18n-keys.mnd  7813 bytes
   scripts/mandela/legacy-j3d.mnd  4567 bytes
   scripts/mandela/mandela-version.mnd  7036 bytes
   scripts/mandela/release-check.mnd  5799 bytes
   scripts/mandela/scripts-hygiene.mnd  5906 bytes
   scripts/mandela/startmenu-audit.mnd  8359 bytes
   scripts/mandela/todo-scan.mnd  3737 bytes
   scripts/mandela/userscript-new.mnd  3651 bytes
   scripts/mandela/whats-new.mnd  34977 bytes
   13 program(s)

== what the build claims ==
   10 wired gate task(s): docLinksCheck, htmlLinksCheck, releaseCheck, mandelaVersionCheck, changelogCheck, startMenuAudit, i18nKeysCheck, legacyJ3dCheck, scriptsHygieneCheck, whatsNewCheck

== one line per program ==
   report  <-  asset-report.mnd
   gate  <-  changelog-check.mnd
   gate  <-  doc-links.mnd
   gate  <-  html-links.mnd
   gate  <-  i18n-keys.mnd
   gate  <-  legacy-j3d.mnd
   gate  <-  mandela-version.mnd
   gate  <-  release-check.mnd
   gate  <-  scripts-hygiene.mnd
   gate  <-  startmenu-audit.mnd
   report  <-  todo-scan.mnd
   writes a program  <-  userscript-new.mnd
   gate and generator  <-  whats-new.mnd

== the same facts as JSON ==
   {"programs":15,"gates":11,"runBy":"refused by the host (PermissionError)","at":"2026-10-13 20:09"}
   parsed back: 15 programs, 11 gates
```

Read that last block twice, because it is the whole security model in one field:
the script walked the tree and read the build file, and it still could not ask the
environment for a user name. `std.env` needs the `ENV` grant, the desktop profile
does not carry it, and the refusal is a value the program can catch and name -
not a silent empty string.

§13 of the [language guide](../../docs/mandela-language.md) maps each shipped
program in `scripts/mandela/` onto the constructs it is a worked example of; this
page is the tour, that table is the index.

## Four hosts, one language

| Host | How a script arrives | What it is granted | What it is used for |
| --- | --- | --- | --- |
| The CLI | `mandela run FILE -s DIR` | `desktop(root)`: console, files under `DIR`, no env, 50 M steps | the project's own automation, and any script you write |
| Espresso | the **Mandela** extension, on the document you are editing | `console()`: the same profile as `mandela eval` - no files, no env, 20 M steps | *Run Mandela* `Ctrl+Alt+Shift+M`, *Check Mandela* `Ctrl+Alt+Shift+A`, *Mandela Bytecode* `Ctrl+Alt+Shift+B`, *Mandela Template*, *Presentoire Deck* |
| The Web Browser | `~/.lg3d/webbrowser/userscripts/*.mnd`, run on every page that commits | `webPage()`: no files, no env, no host types, 5 M steps | page logic, bound to `page`, `inject(js)` and `note(msg)` |
| Any JVM | the engine facade or JSR-223 | whatever the host's `Capabilities` says | embedding the language in a tool |

Both desktop hosts must be **enabled by the user** in their extension manager:
the consent boundary is the grant, not the file on disk.

### Espresso

A `.mnd` file opens with language support that comes from the language jar
itself, so the editor cannot drift from the grammar: the keywords are
`Keywords.ALL`, and the outline panel, the squiggles and the completion strip are
all answers from `EditorServices`. A script runs on a **virtual thread**, so a
mistyped `loop { }` costs a `LimitError` in the Output panel and never the UI.

The run is deliberately the *console* profile and not the desktop one: a buffer is
unsaved text in somebody's editor, and an action called "run" must not be able to
write outside it. A script that needs the filesystem is a file on disk, and the
CLI with `-s` is the host for it.

### The Web Browser

`userscript-new.mnd` writes the starting file, and this is the body it generates
for a script named `my-script` with `example.com` as its URL pattern - the
template is the clearest statement of what a page script may touch:

```mandela
if (page.title == null) {
    note("my-script: waiting for a page")     // the document is not ready
} else if (!page.url.contains("example.com")) {
    note("my-script: not for " + page.url)
} else {
    note("my-script: saw " + page.title + " (" + str(length(page.title)) + " chars)")
    // Narrow columns are the cheapest improvement a page script can make.
    inject("var s = document.createElement('style'); s.textContent = 'body{max-width:72ch;margin:2em auto}'; document.head.appendChild(s);")
}
```

`page` is a map of `url` and `title`; `note(msg)` writes to the browser log
because there is no console to write to - `println` is *bound* to that same log
rather than granted, so an author who types it gets a browser line and the host
never opens a path to the launching terminal. `inject(js)` queues JavaScript for
the page and is the only way a script changes what the user sees.

Applicability is host metadata, not grammar: a leading `// @match <glob>` line
decides which pages the script runs on, where `*` is any run of characters, `?`
is exactly one and everything else - including the dots of a domain - is
literal. No `@match` at all means every page. A script that crashes takes nothing
down with it: it runs in its own engine, and the injections it had already asked
for still land, because the delivery is in a `finally`.

### The repository's own CI

Sixteen programs ship in `scripts/mandela/`; the eleven gates among them are what
the build ran above wires:

```bash
./gradlew :lg3d-mandela:repositoryChecks     # all eleven
./gradlew :lg3d-mandela:docLinksCheck        # one of them
```

One of them, `whats-new.mnd`, is both a **generator** and a **gate**: with the
word `write` it renders a page of the desktop's Help Center out of
`CHANGELOG.md`, and without it the same program compares the committed page with
what it would write now and fails the build on a difference. That is the pattern
this repository uses: the check that ships with the thing it checks. Another,
`mandela-version.mnd`, checks the language against itself — the jar's version and
the language's are written in eight places, and a jar whose own documentation
contradicts it is how an embeddable engine loses the trust that got it embedded.

## Reading the machine

Three commands turn a script into something you can inspect instead of guess at.

```bash
mandela check FILE       # compile only: every finding, one per line
mandela dump FILE        # the instructions the compiler produced
mandela eval 'PROGRAM'   # a one-liner, in the console profile
```

A broken file and a runaway loop, as actually reported. The two files are three
lines between them - `broken.mnd` is one line, `fun bad( {`, and the loop is two -
so both runs are reproducible:

```text
$ mandela check build-gradle/scratch/exits/broken.mnd ; echo $?
build-gradle/scratch/exits/broken.mnd:1:10: error: expected a parameter name but found '{' [syntax]
mandela: 1 error(s), 0 warning(s)
4

$ mandela eval 'var i = 0
loop { i = i + 1 }' ; echo $?
LimitError: the script retired more than 20000000 instructions; a loop is probably never terminating
2
```

`dump` is what Espresso's *Mandela Bytecode* action shows you, and the same text
is what the machine-code tests are written in. `shape.mnd` is eight words of
branch and a list:

```mandela
let a = [1, 2, 3]
var n = a.sum()
if (n > 5) { println("big") } else { println("small") }
```

The stream it compiles to, with the middle elided by `...` - a real run prints
every instruction:

```text
$ mandela dump build-gradle/scratch/exits/shape.mnd
function <script>@build-gradle/scratch/exits/shape.mnd:1#1 {
  name="<script>"  source=build-gradle/scratch/exits/shape.mnd:1
  params=0 required=0 slots=0 upvalues=0 method=false boxed=[]
     0  LOAD_CONST      0      const 1   :1
     2  LOAD_CONST      1      const 2   :1
     4  LOAD_CONST      2      const 3   :1
     6  MAKE_LIST       3      count    :1
     8  DEFINE_GLOBAL   3      name a   :1
    ...
    12  CALL_METHOD     1024   name<<8|argc sum /0 args   :2
    14  DEFINE_GLOBAL   5      name n   :2
    ...
    38  RETURN          0      -      :3
}
```

### Exit codes a build can branch on

Fixed, so a Makefile or a desktop launcher never has to parse prose
(`MandelaCli`):

| Code | Meaning |
| --- | --- |
| 0 | the program ran |
| 1 | the command line was wrong; nothing started |
| 2 | the program failed - at run time **or** at compile time |
| 3 | a named file was missing, unreadable or a directory |
| 4 | `check` found at least one blocking diagnostic |

A compile error is `2`, not `1`: whoever runs `mandela run broken.mnd` gets the
same branch whether the mistake is a missing brace or a division by zero.

## Embedding it in a host

One call, no builder beyond a profile, and the answer is the script's last
expression (from the [embedding guide](../../docs/mandela-embedding-api.md)):

```java
var engine = Mandela.engine()
        .bind("prices", List.of(12.5, 40.0))
        .bind("rate", 0.05)
        .bind("tax", (Function<Double, Double>) v -> v * 0.05)
        .build();
Object total = engine.run("prices.map { x -> x * rate }.sum()");   // 2.625
```

The same jar answers under `javax.script`:
`new ScriptEngineManager().getEngineByName("mandela")`.

## Four rules a first script learns quickly

1. **Pass `-s DIR`** when a program walks the tree, or the first `fs.read` is a
   `PermissionError`. That default is the feature.
2. **`let` does not change.** `let n = 0; n = n + 1` is a compile error, not a
   warning. Use `var` for a counter.
3. **`+` will not mix a `Str` with a number.** Interpolate - `"${count} gates"` -
   or write `str(count)`. The refusal names itself: *`'+' cannot mix Int and Str;
   concatenate two strings, or write str(...) on the other side`*.
4. **`indexOf` takes a start index.** `t.indexOf("'", open + 1)` is the closing
   quote, so a scanner is one call per token. A negative start counts as `0` -
   the `-1` a failed lookup answers never scrambles the search after it - and a
   start past the end answers `-1` rather than refusing. `List.indexOf` takes the
   same second argument.

## What the language deliberately does not have

No regular expressions, no async, no coroutines, no generics, no operator
overloading, and no process or socket - the `PROCESS` and `NETWORK` grants exist
in the vocabulary and nothing can satisfy them, which is what lets a host grant
them without widening anything. Text is matched with `indexOf`, `startsWith`,
`endsWith`, `split` and a loop; that is more code than a pattern and less than it
feels like, and it keeps a page script from being able to ask the host for
unbounded work. The machine steps are what the sandbox can count, and a backtracking
matcher runs inside one Java call, so a pattern with a nested quantifier would burn
any budget from a single instruction. Where a pattern really is the right tool the
host supplies a narrower one instead: the browser's `// @match` header is a glob -
`*`, `?` and literals, translated to an anchored pattern by the host, never by the
script. The other absences - generics, overloading, coroutines, the recursion and
frame ceilings - are listed with their reasons in §12 of the
[language guide](../../docs/mandela-language.md).

---

See also: [HISTORY.md](HISTORY.md) - why this name ·
[DEVELOPMENT.md](DEVELOPMENT.md) - working on the module ·
[`scripts/mandela/README.md`](../../scripts/mandela/README.md) - the shipped
programs · [AGENTS.md](../AGENTS.md) - the module's rules

---

Copyright © 2026 Jean-François Landreville (Dr. Land Evil). All rights reserved.
