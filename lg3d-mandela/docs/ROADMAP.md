# Mandela — Life Beyond the Desktop, Probing, and the Roadmap

What Mandela is when nobody is looking at a 3D desktop, whether it can be used
to probe a running program, and what comes next for the language. The numbered
promises the language already makes live in the
[language guide](../../docs/mandela-language.md) and
[Functional Analysis](FUNCTIONAL_ANALYSIS.md); this document is about the parts
of that surface a host or a maintainer has to *choose* — and about the ones no
release will offer, with the reason attached.

## 1. A life outside Looking Glass

The module depends on nothing: `java.base` plus `javax.script`, no AWT, no
Swing, no Java 3D, no `lg3d-core` import. That single constraint is the whole
answer — a language with no host to lean on is a library, and a library that
names no UI toolkit runs wherever a JVM does. Five such lives are already real:

| Where | What runs there | What it is granted |
| --- | --- | --- |
| **This repository's CI** | the eleven gates in [`scripts/mandela/`](../../scripts/mandela/README.md) — link checkers, version-consistency gates, a start-menu audit, an accelerator-conflict gate, an i18n contract — via `./gradlew :lg3d-mandela:repositoryChecks` | `desktop(root)`: files under the repository root, console, a step budget |
| **Any terminal** | the `mandela` CLI: `run`, `check`, `eval`, the REPL (`mandelaRepl`) | whatever the invocation says, `desktop(root)` by default |
| **Any Java or Kotlin program** | the embedding API (`Mandela`, `Runtime`, `Capabilities`, `Interop`) or JSR-223 — see the [embedding guide](../../docs/mandela-embedding-api.md) | whatever the host's `Capabilities` says |
| **Espresso** (the desktop's text editor) | the Mandela extension: run, check, disassemble, template a document | `console()`: no files, no env, 20 M steps |
| **The Web Browser** | userscripts from `~/.lg3d/webbrowser/userscripts/*.mnd`, on every page that matches | `webPage()`: no files, no env, no host types, 5 M steps |

Only two of those five are the desktop. The other three — CI, terminal,
embedder — never render a frame, and they are the reason the sandbox is a
grant list rather than a guess: the same jar that rewrites a documentation
site's links also runs untrusted text in a browser page, and the difference
between those two jobs is exactly `Capabilities`, nothing else.

A new host costs one jar and one `Capabilities`. Nothing has to be ported,
because there is nothing to port: no natives, no resource bundle, no toolkit.

## 2. Mandela as a probe

**Yes — for in-JVM probes, and it is the use the zero-dependency rule was
protecting.** The desktop's own verification story (see `lg3d-core/AGENTS.md`,
*Verifying UI changes*) runs an in-JVM probe plus internal screencapture
because a headless test cannot construct the scene graph. The probe half of
that story is exactly what an embedded engine is for:

1. The host JVM starts with the real classes on the classpath — the desktop, a
   tool, anything under test.
2. The probe wires a `Runtime` and **binds the surface under test as host
   functions** through `Interop.fn` / `Interop.supplier` / `Interop.consumer`
   (embedding guide §6): `frameCount()`, `windowTitle(i)`, `captureTo(path)`,
   whatever the subject answers to. The script never reaches into a class; the
   host hands it names, gated by `JVM` at the binding, not by the script's
   ambition.
3. The script drives them, `assert`s what must hold, and prints what a reader
   needs. Exit codes are fixed (`0` ran, `2` failed — a failed assertion is a
   failed probe, so CI branches on the number and never parses prose).
4. The step budget is the watchdog: a probe that hangs on the host's own lock
   still stops, with a `MandelaError`, at the machine's step ceiling rather
   than at a timeout nobody configured.

What this shape buys over a Java probe class: the probe is *data* — shipped,
edited and re-pointed without recompiling the host, diffed in review as four
lines of script, and run against any build of it. The scripts in
`scripts/mandela/` are already this pattern aimed at the repository instead of
a live JVM: bind the question ("do these links resolve?"), assert, answer with
an exit code.

What it deliberately does not buy:

- **No network or process probe.** `NETWORK` and `PROCESS` are grant names with
  nothing behind them — no stdlib member can satisfy them — so a probe cannot
  leave the JVM it was handed, which is the point of an *in-JVM* probe. A
  health-check script that wants `http://` is a different tool with a different
  threat model (see §3.3 below).
- **No live 3D.** A probe answers about objects, not pixels; the screencapture
  half of the desktop's verification stays in the host. The language never
  grows a scene-graph import — that is `lg3d-core`'s job, and the dependency
  direction is fixed.
- **No long-running daemon.** The engine is a callable, not a service; a probe
  runs, answers and is gone.

## 3. The roadmap

Three horizons, and one rule that applies to all of them: **an item ships with
its tests, its guide text and its gate in the same PR**, because a promise the
documentation does not carry is not a promise, and the coverage floors (and
PIT at zero survivors for this module) do not negotiate.

### 3.1 Shipped in 1.1.0 — stdlib and tooling (a `mandela.version` bump only)

Members and modules added to the existing vocabulary. No script that runs
today changes behaviour, so `mandela.language` stayed `1.0`: a script may
assume the grammar, and the grammar is untouched. New members are things a
shipped script hand-rolled at the time this list was written:

- `s.count(needle)` — the occurrence count the link checkers each scan for by
  hand with `indexOf` in a loop.
- `s.splitOnce(sep)` — the first-pair split every `key=value` parser in the
  tree repeats.
- `s.isBlank()` — `trim().length == 0`, stated once instead of twice per gate.
- `xs.count(pred)` and `xs.partition(pred)` — the report-and-split pair the
  gates' `filter` + re-filter pattern wants.
- `std.glob` — `matches(text, pattern)` and `escape(text)`: the `*`/`?` matcher
  the browser's `// @match` host already implements in Java, exposed as a
  scriptable utility. Bounded by construction (a glob scans once; it does not
  backtrack), which is why it is on this list and regular expressions are not.
- **New shipped scripts**: a changelog-shape gate (the release pages and the
  Update Manager's dialog both parse `CHANGELOG.md`; a malformed section is
  invisible to them until it renders wrong), and a `todo-scan` report over the
  tree's markers, report-only like `asset-report.mnd`.

### 3.2 Later — language-surface work (both axes bump together)

Anything a *script* can observe that it could not before — new syntax, new
semantics, a new statement — moves `mandela.language` as well, and every host
has to decide whether to accept the newer line. Candidates, none promised:

- **Guard expressions on `for`** (`for (x in xs if x.ok)`) — the loop-and-filter
  idiom the gates write constantly; small to parse, small to compile.
- **`match` over values and types** — the dispatch the `typeOf` chains in host
  code emulate; must stay step-countable and fall through exhaustively-checked
  arms only, or it is just `if` with more places to hide a bug.
- **String `format` with named fields** — `"{name}:{port}"` against a `Map`,
  for the report lines every gate assembles with `padEnd` today.
- **A second sandbox profile for probes** — `desktop(root)` plus read-only
  `ENV`, shaped for CI probe scripts; a `Capabilities` change, so no grammar
  moves under it.

### 3.3 Parked, with the reason — and parked is not "backlog"

These will not appear in a Mandela release unless the reason against them is
*dissolved*, not merely tired:

| Item | Why it is out |
| --- | --- |
| Regular expressions | A backtracking matcher burns any step budget inside a single Java instruction; the sandbox counts machine steps, not calls. Text gets `indexOf`-family plus `std.glob` (§3.1) instead. |
| Async / coroutines | There is no scheduler to yield to; a blocking call blocking the host thread is the host's problem, which is why Espresso runs scripts on a virtual thread *outside* the language. |
| Generics, operator overloading, metaclasses | The language is a scripting surface with one consumer class — glue, automation, page logic — and each of these triples the teaching cost for zero added capability. Guide §12 states the limits plainly; a reader who wants them wants a host language. |
| JIT | The machine is a stack VM whose step count *is* the sandbox; optimised steps could not be counted the same way. Speed-critical work is bound from the host as a function, which is faster than any JIT of the script would be. |
| `PROCESS` / `NETWORK` grants with real calls behind them | The vocabulary exists so a host may grant without widening anything; nothing currently can satisfy it. `std.http` (GET/POST, size-capped, host-preflighted) is the only honest shape and it rewrites the browser userscript story's threat model — that needs its own design doc and its own review, not a release note. |
| Scene-graph / lg3d-core imports | The dependency direction is fixed: `lg3d-mandela` depends on nothing. A probe sees the desktop through `Interop` bindings a host chose, never through an `import`. |

### 3.4 On the version axes

How the two numbers move is policy, not mood — written in full in
[`lg3d-mandela/CHANGELOG.md`](../CHANGELOG.md) and gated by
[`scripts/mandela/mandela-version.mnd`](../../scripts/mandela/mandela-version.mnd):

- A §3.1 item bumps `mandela.version` (the jar) alone: `1.0.0 → 1.1.0`, the
  way the whole of §3.1 shipped.
- A §3.2 item bumps both axes: a script that *can* use it lives in a world
  where `LANGUAGE_VERSION` says so.
- A bug fix bumps the patch: `1.1.0 → 1.1.1`.
- The desktop's release train never rewrites either number; the desktop
  bundles whatever jar this module's own clock produced.

The roadmap is a promise about direction, not about dates; the items under
*Next* are the ones whose shape is already proven by a script in the tree that
hand-rolls them. When one ships, it moves from this file into the module's
changelog — and the guide text, the tables and the gate arrive in the same PR,
because that is the only way the three copies of a feature stay one feature.

---

See also: [README.md](README.md) - the module's index ·
[USAGE.md](USAGE.md) - the language in daily use ·
[HISTORY.md](HISTORY.md) - why this name ·
[language guide](../../docs/mandela-language.md) - what the language promises
