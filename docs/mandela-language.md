# The Mandela Programming Language

**Project Mandela** · language version 1.0 · file extension `.mnd`

Mandela is Project Looking Glass's own desktop scripting language: a small,
fast, dynamically typed language with a Java/Kotlin-shaped surface, built to be
embedded in the desktop rather than to stand alone. A `.mnd` file can rename a
thousand photos, glue two applications together, drive a Web Browser userscript
or give Espresso an outline of what you are typing — and the same engine that
runs it sits inside those applications as a jar on the classpath.

This guide is the language reference. For embedding the engine in Java or
Kotlin, see [mandela-embedding-api.md](mandela-embedding-api.md).

- **Module:** `lg3d-mandela` (`org.jdesktop.lg3d:lg3d-mandela`)
- **Packages:** `lang` (lexer, parser, AST, diagnostics) · `code` (compiler,
  bytecode, disassembler) · `vm` (frame, machine, interpreter) · `values`
  (runtime values and operators) · `rt` (engine, capabilities, standard library)
  · `api` (the host-facing facade) · `cli` (runner and REPL)
- **Dependencies:** none. Mandela has no third-party jars and touches no AWT,
  Swing or Java 3D class, which is what lets it be embedded anywhere.

---

## 1. A first script

```mandela
#!/usr/bin/env mandela

fun greet(name: Str) -> Str { "Hello, ${name}" }

for name in ["world", "desktop"] {
    println(greet(name))
}
```

```bash
mandela run hello.mnd                    # run it
mandela check hello.mnd                  # compile it, run nothing
mandela repl                             # evaluate lines, interactively
./gradlew :lg3d-mandela:mandelaRun -Pscript=hello.mnd
```

A program is a list of statements; the value of the last expression is the
result of the run. `println` writes to whatever console the host supplied —
a terminal, Espresso's Output panel, the browser log — never to a hard-wired
`System.out`.

---

## 2. Lexical rules

| Form | Meaning |
| --- | --- |
| `// line` | to end of line |
| `/* block */` | nested-free block comment |
| `"text"`, `'text'` | string; both quote marks are accepted and are the same type |
| `"""text"""` | multi-line string, keeping its line breaks |
| `"a=${expr} b=$name"` | interpolation; `${...}` takes any expression and nests |
| `12`, `-7`, `1.5`, `0.001`, `1e3` | `Int` (a Java `long`) and `Double` |
| `true` / `false` / `null` | the only literals of their kinds |
| `_` | a deliberate "don't care" in a pattern or a destructure |
| `1_000_000` | digit separators are allowed inside a number |

Statements end at a line break or `;`. Names are
`[A-Za-z_][A-Za-z0-9_]*` and are case-sensitive.

### Reserved words

```
fun let var if else while for in loop break return class type enum extends
this super match is try catch finally throw defer import use true false null
and or not as
```

Three further words are **contextual**: they are only special where the grammar
reads them — `init` (a constructor), `export` (a module declaration) and `step`
(a range stride in a `for` header). Everywhere else they are ordinary names.
The set is deliberately minimal, and `Keywords.CONTEXTUAL` in the source lists
exactly the words the parser really consumes: a soft keyword is only added when
a parser position for it exists, so an editor never paints `get` or `with` as
reserved when the grammar accepts them as identifiers.

---

## 3. Types and values

| Type | What it is | Java / Kotlin counterpart |
| --- | --- | --- |
| `Int` | a 64-bit whole number | `long` |
| `Double` | an IEEE-754 double | `double` |
| `Bool` | `true` / `false` | `boolean` |
| `Str` | an immutable text value | `String` |
| `List` | an ordered, mutable collection | `java.util.List` |
| `Map` | a string-keyed record | `java.util.Map` |
| `Range` | `start..end step s`, lazy | — |
| `Null` | the absence of a value | `null` |
| `Fun` | a function or lambda | a `Function<N>` |
| `Class` / `Instance` | a declared type and its objects | your own class |
| `Error` | a raised condition, also the exception root | `Throwable`-ish |

Values are never boxed behind a wrapper the script has to unwrap: `Int` *is* a
`long`, and `Interop` moves them across the boundary without ceremony.

### Truthiness

Only `false` and `null` are false. `0`, `""` and `[]` are **true** — the rule a
Java author expects, applied consistently. Use `is Empty`-style members
(`isEmpty`, `length == 0`) when you mean the collection sense.

---

## 4. Operators

| Precedence (loosest first) | Operators |
| --- | --- |
| pipeline | `\|>` |
| logical | `or`, `and`, `not`, `\|\|`, `&&`, `!` |
| equality | `==`, `!=`, `is`, `!is`, `in`, `not in` |
| comparison | `<`, `<=`, `>`, `>=` |
| additive | `+`, `-` |
| multiplicative | `*`, `/`, `%` |
| power | `**` (right associative) |
| unary | `-`, `!`, `not` |
| access | `.`, `?.`, `?.[]`, `??`, `as`, indexing `[]` |

- `+` adds numbers, joins strings and concatenates lists (`[1] + [2]`).
- `/` between two `Int` values is integer division; `1.0 / 2` is a `Double`.
- `??` is the null-coalescing operator, `?.` the safe accessor:
  `s?.length` yields `null` instead of raising when `s` is `null`.
- `x |> f(y)` reads as `f(x, y)` — the pipeline threads a value through calls.
- `x as Str` and `x is Int` test and convert; `is` also destructures
  (`let Pair(x, y) = point()`).
- Assignment operators: `=`, `+=`, `-=`, `*=`, `/=`, `%=`.
- A range is `a..b` (inclusive of `a`, exclusive of `b`); `8..0 step -1` counts
  down, and `step` is only a keyword inside a `for` header or a range.

---

## 5. Statements

### Binding

```mandela
let size = 12          // immutable
var count = 0          // mutable
count += 1
let [head, tail] = list
let Pair(x, y) = point()
let row = {"a": 1}
row.a                  // maps answer to dot access as well as row["a"]
```

### Functions

```mandela
fun add(a: Int, b: Int = 1) -> Int { a + b }   // last expression is the result
fun shout(text: Str) { println(text.upper()) } // `return` is available too
let twice = { x -> x * 2 }
let thunk = { -> 42 }
add(1) + twice(3) + thunk()
```

A block-bodied function returns its last expression; an expression-bodied one
is written on one line. Default parameters are evaluated at call time.

A lambda is `{ a, b -> body }`, and — as in Kotlin — a lambda that is a call's
last argument may be written after the parentheses, or in place of them when it
is the only argument:

```mandela
prices.map { x -> x * 2 }
prices.reduce({ total, x -> total + x }, 0)   // the same call, argument first
jobs.filter { j -> j.ready }.forEach { j -> run(j) }
list |> .map { x -> x + 1 }                    // pipelines take it too
```

Three rules make that readable rather than ambiguous:

- the lambda's parameters are always **named** — there is no implicit `it`,
  because a one-name-fits-all parameter would be a guess about the callback;
- a trailing lambda must open on the **same line** as the call it belongs to;
- its header must carry `->`, so a `for x in items() { … }` body and
  `match value { 1 => … }` arms are still a block and arms, not an argument.
  (`{ x => x * 2 }` is a lambda when written inside the parentheses, where
  there is no second reading.)

### Control flow

```mandela
if (count > 3) { ... } else if count > 0 { ... } else { ... }
if count > 3 { ... }                 // parentheses optional
let label = if (ok) "yes" else "no"  // `if` is an expression

while (queue.has()) { job = queue.next() }
loop {                               // endless until you leave it
    if done() { break }
    step()
}
for i in 0..10 { }                   // ranges
for i in 0..100 step 5 { }
for name in list { }
for [key, value] in map { }          // destructure each entry
for key, value in map { }
for (entry in map) { }               // parentheses accepted
```

`break` and `continue` apply to the innermost loop. A runaway loop is not a
hung desktop: every capability profile carries an instruction budget, and
crossing it raises rather than spins (see §10).

### Match

```mandela
match value {
    1 => "one",
    n if n > 8 => "many",
    [first] => first,                  // a list pattern binds its parts
    _ => "other"
}
```

The arms an expression takes are: a literal (`1`, `"a"`, `true`, `null`), a
binding with an optional guard (`n if n > 8`), a list pattern
(`[a]`, `[a, b]`) and `_` as the catch-all. There is no alternation (`2 | 3`) —
write two arms with the same body, or a guard `n if n == 2 or n == 3` — and no
map pattern, because a `{` inside a `match` block would be readable as an arm
body. `match` is an expression; arms are tested in order.
No arm matching raises `MatchError` — falling through silently
to `null` hides mistakes.

### Errors

```mandela
try {
    risky()
} catch e {                          // any error
    println("it failed: ${e}")        // renders as "TypeError: cannot add"
} finally {
    cleanup()                        // runs whatever happened, before the error moves on
}

try { parse(text) } catch e: ValueError { fallback() }

error("cannot continue")             // raise kind Error
error("bad input", "ValueError")     // raise that kind
throw value                          // raise any value as kind Error
throw e                              // re-raise, keeping the original kind

fun worker() -> Str {
    defer flush()                    // runs when the function leaves, by any exit
    "ok"
}
```

Errors are values of the `Error` family (`MandelaError` in the engine) with a
kind and a message. The kinds the engine raises are `TypeError`, `ValueError`,
`KeyError`, `IndexError`, `RangeError`, `NullError`, `IOError`, `JsonError`,
`SyntaxError`, `MatchError`, `AssertError`, `PermissionError` (a refused
grant), `LimitError` (a step or depth ceiling) and `RuntimeError` (the rest). An
uncaught error stops that run and is reported by the host in the host's own
words; `catch e { … }` sees every one of them, and `e.kind` names it.

Three rules keep this small vocabulary honest, and each one is a syntax error
rather than a silent reading:

- **One `catch` per `try`.** A second `catch` clause does not parse. Pick the
  kind by testing `e.kind` in the body, or nest a `try`.
- **`catch e: <Kind>` matches that kind exactly** and lets every other error
  travel on to the host, so a typo in the kind name swallows nothing.
- **`defer` belongs to a function**, and `try` is a statement: it cannot stand
  where a value is expected, so `let v = try { … }` does not parse. Declare a
  `var` before the `try` and assign to it inside.

---

## 6. Data

```mandela
let xs = [3, 1, 2]
xs.push(4)
xs.map { x -> x * 2 }.filter { x -> x > 4 }.join(", ")
xs.reduce({ total, x -> total + x }, 0)
xs.map { x, i -> "${i}:${x}" }        // the second parameter is the index
```

```mandela
let cfg = {"host": "localhost", "port": 8080}
cfg.put("tls", true)
cfg.keys()
for [k, v] in cfg { println("${k}=${v}") }
cfg.mapValues { v -> v }              // a new map
cfg.forEach { k, v -> println(k) }    // the map form takes both names
```

```mandela
(1..5).toList()
(5..1 step -1).sum()
```

Lists and maps are mutable and reference-shared, the way a Java `List` is. The
sorting family says which side of that line it sits on: `sort` and `sortBy`
reorder the list you called them on (and answer it back, so they chain), while
`sorted`, `sortedBy` and `reversed` return a new list and leave the original
alone; `copy` on a map is the same idea.

---

## 7. Types of your own

```mandela
type Point(x: Int, y: Int)                   // a record: value semantics

class Counter(start: Int = 0) {
    var total = start
    init { total = start * 1 }               // `init` is the constructor body
    fun bump(step: Int = 1) -> Int { total += step; total }
}

class Meter() extends Counter {
    fun read() -> Int { total }
}

enum Status { Idle, Running, Done }

let p = Point(1, 2)
let c = Counter(5)
c.bump()
p is Point                                   // true
Status.Running
```

- `type` declares an immutable record with component access (`p.x`) and
  structural equality.
- `class` declares a nominal type; the constructor parameter list is the
  primary constructor and its names are fields in the body, `init { … }` is the
  extra constructor body (statements, and members must be separated by line
  breaks like any other statements), `this` and `super` work as in Java, and
  members are functions and fields only.
- `enum` declares named constants; each constant carries its name.
- Multiple inheritance does not exist. `extends` takes one class.
- A subclass reads its base's fields and methods **by bare name**, exactly as it
  reads its own (`Meter().read()` above returns `total`, inherited from
  `Counter`). Own members win when a name is both declared and inherited, so an
  override shadows the base instead of colliding with it. The compiler only
  walks a hierarchy it can see: when the base comes from a host binding rather
  than a `class` in this script, spell the receiver (`this.total`).
- `export` goes on a *declaration* (`export fun`, `export let`, `export type`,
  `export class`, `export enum`), never on its own inside a body.

---

## 8. Modules

```mandela
use std.json                                 // the bundled library
use std.fs                                   // a module is bound by its own name
json.parse(text)
fs.read("notes.txt")
```

`use` takes no alias — the module answers to its last name (`std.fs` is `fs`),
which is one less thing to remember when two files read the same library. Only
`import` of a file may be renamed:

```mandela
import "lib/util.mnd"                        // a file, relative to the script
import "lib/util.mnd" as util                // or under a name
util.helper(1)
```

A module is a normal Mandela file whose exported names survive the import:

```mandela
export fun helper(n: Int) -> Int { n * 2 }
export let answer = 42
export type Row(a: Int)
```

An un-`export`ed top-level name stays private to the file. Imports are
resolved under the engine's import root and are cached — one file is loaded
once per engine, never twice.

---

## 9. The standard library

`use std.<name>` is required; nothing but the console and the conversions is in
scope by default, so a module name never collides with a name you declared.

### Globals (no `use`)

`println print eprintln str int double bool length typeOf min max abs error
assert keys valuesOf`

### `std.json`

`parse encode stringify isValid` — JSON text in, Mandela values out
(`Map`/`List`/`Str`/`Double`/`Bool`/`Null`), and back. `isValid` tests without
raising.

### `std.math`

`PI E tau abs sign floor ceil round sqrt cbrt pow exp log log10 log2 sin cos
tan asin acos atan atan2 hypot min max random randomInt`

### `std.fs` *(needs the filesystem grant)*

`read lines write append exists isDirectory list mkdir delete size modified
root` — confined to the engine's file root; anything outside it raises
`PermissionError`.

### `std.env` *(needs the `ENV` grant)*

`get has names user os separator pathRoots`

### `std.time`

`now nanoTime iso format fields parse sleep zone` — reading the clock needs no
grant; `sleep` does, because it parks a host thread (`THREAD`).

### Value members

| Type | Members |
| --- | --- |
| `Str` | `length upper lower trim split replace replaceFirst contains startsWith endsWith indexOf charAt substring toInt toDouble repeat padStart padEnd` |
| `List` | `length size isEmpty first last push add insert removeAt pop clear set get indexOf contains join map filter reduce forEach any all find sort sortBy sorted sortedBy reversed slice take skip sum min max distinct flatten` |
| `Map` | `length size isEmpty keys values get put set remove has clear merge copy forEach mapValues entries` |
| `Range` | `length first last step isEmpty toList contains reversed sum map filter forEach any all find join min max distinct slice take skip sorted sortBy` |
| `Int`/`Double` | `abs sign floor ceil round sqrt cbrt toInt toDouble isWhole` |

Properties (`s.length`) resolve before methods of the same name
(`xs.length()`), so a member's kind never changes what it reports.

---

## 10. Security model

Mandela runs **in** the host JVM and never assumes the host trusts the script.
Every engine carries a `Capabilities` profile that decides, per grant, what a
program may do:

| Grant | Lets a script | Enforced today by |
| --- | --- | --- |
| `STDOUT` | `print` / `println` | the interpreter's output channel |
| `STDERR` | `eprintln` | the interpreter's error channel |
| `STDIN` | read input | *reserved* — the language has no input function |
| `FILE_READ` | `std.fs` reads, `list`, `exists`, `size`, `modified`, `import` | `std.fs`, module loading |
| `FILE_WRITE` | `std.fs` `write`, `append`, `mkdir`, `delete` | `std.fs` |
| `ENV` | `std.env` | `std.env` |
| `NETWORK` | open a socket | *reserved* — there is no socket function to call |
| `JVM` | reach Java types, on top of an explicit allow-list | `Capabilities.allowsJvm` |
| `PROCESS` | start a process | *reserved* — no process function exists |
| `THREAD` | `time.sleep` | `std.time` |

A grant that is *reserved* is a promise in the vocabulary, not a door: a script
cannot open a socket or a process under any profile, so a host may grant them
without widening anything today. The clock needs no grant — `time.now` is pure —
which is why `std.time` is not gated the way `std.fs` and `std.env` are.

Two budgets are not grants but are what stops a page script from freezing a
desktop: `maxSteps` (instructions, default 200 M) and `maxDepth` (frames,
default 1024). Crossing either raises a catchable `LimitError` that quotes the
ceiling it hit.

Ready-made profiles:

| Profile | Use it for | Console | Files | Env | Thread | Steps | Depth |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `Capabilities.open()` | code you own, run by you | yes | yes | yes | yes | 200 M | 1024 |
| `Capabilities.console()` | a CLI script, a pasted snippet | yes | no | no | no | 20 M | 512 |
| `Capabilities.desktop(root)` | a desktop automation | yes | under `root` | no | yes | 50 M | 1024 |
| `Capabilities.webPage()` | anything that arrived from a page | no | no | no | no | 5 M | 256 |

Refusing a capability is not a silent no-op: the call raises `PermissionError`
naming the grant, in the form *"this host (webpage) does not allow writing to
the console; ask the embedding application for the STDOUT capability"* — which
is the message a user needs to see. `std.fs` additionally requires the engine to
have been given a filesystem at all (`Mandela.application(root)`); a profile may
grant `FILE_READ` and still have nothing to read. The engine has no reflection
and no way to name a Java type unless the host both grants `JVM` and lists that
type — see `Interop` in the embedding guide.

---

## 11. Espresso and the Web Browser

The language is the single source of truth for both hosts.

- **Espresso** (the text editor) recognises `.mnd` from its language catalogue —
  keywords come straight from `Keywords.ALL` — and its bundled **Mandela**
  extension (`org.jdesktop.lg3d.apps.texteditor.MandelaTools`) offers
  *Run Mandela* (`Ctrl+Alt+Shift+M`), *Check Mandela* (`Ctrl+Alt+Shift+A`),
  *Mandela Bytecode* (`Ctrl+Alt+Shift+B`) and *Mandela Template*. Diagnostics,
  the outline panel and the completion strip all come from `EditorServices` in
  the language jar, so the editor cannot drift from the grammar.
- **The Web Browser** runs `~/.lg3d/webbrowser/userscripts/*.mnd` through the
  bundled **Mandela Userscripts** extension. A script sees `page.url`,
  `page.title`, `inject(js)` and `note(msg)`, its console lands in the browser
  log, and it runs in the `webPage()` profile — no disk, no sockets, no JVM
  types, five million instructions.

Both hosts must be *enabled* by the user in their extension manager; the grant
of `CONTENT_SCRIPT` / the editor's `DIAGNOSE` is the consent boundary.

---

## 12. Style, speed, and limits

- Prefer `let`; use `var` only when the value changes.
- One blank line between declarations; the language has no formatter and the
  reviewer is the style guide.
- Names are `lowerCamelCase` for values and functions, `UpperCamelCase` for
  `class`/`type`/`enum`.
- A script that runs many times should compile once: on the engine,
  `compile(source, name)` then `run(body)` (see the embedding guide).
- Loops are interpreted. Mandela is for glue, automation and page logic — a
  numeric kernel belongs in the host, bound as a function.
- `Str` is immutable; building large text with `+` inside a loop is the usual
  quadratic trap. Join a list at the end.

Known limits, stated plainly:

- No generics, no overloading, no operator overloading, no metaclasses.
- No coroutines or async; a blocking call blocks the host thread (which is why
  Espresso runs scripts on a virtual thread).
- Closures capture variables by reference, like Java effectively-final-free
  lambdas; a loop variable shared between two created functions is one cell.
- Deep recursion is capped by `maxDepth` (1024 frames by default) rather than
  overflowing the JVM stack.
- Tail calls are not optimised.
