# Embedding Mandela in Java and Kotlin

**Project Mandela** · embedding API 1.0 · module `lg3d-mandela`

Mandela is built to be *inside* another program. The engine is a jar with no
third-party dependency and no AWT, Swing, Java 3D or lg3d-core class in it, so
the same `Runtime` object that runs a Web Browser userscript runs a Gradle test
and runs the desktop's own editor. This guide is the host's manual: what to
construct, what to bind, what to grant and what to expect when a script fails.

The language itself is documented in [mandela-language.md](mandela-language.md).

---

## 1. Put the jar on the classpath

```groovy
// build.gradle
implementation project(':lg3d-mandela')          // inside this repository
// coordinates: org.jdesktop.lg3d:lg3d-mandela:<version>
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>org.jdesktop.lg3d</groupId>
  <artifactId>lg3d-mandela</artifactId>
  <version>1.68.0-dev</version>
</dependency>
```

Nothing else. The module needs `java.base` and `javax.script`, and is compiled
for **JDK 21**. Kotlin hosts need no plugin and no wrapper: a Kotlin lambda
arrives as something callable (see §6).

The jar carries `Main-Class: org.jdesktop.lg3d.mandela.cli.MandelaCli`, so
`java -jar lg3d-mandela.jar run script.mnd` works as a standalone tool, and it
carries the `META-INF/services/javax.script.ScriptEngineFactory` registration,
so `javax.script` discovery works with no extra code.

**Import the API package, not the internals.** Everything a host should call is
in `org.jdesktop.lg3d.mandela.api` (`Mandela`, `Interop`, `EditorServices`,
`MandelaScriptEngine`) plus three runtime types it must be able to name:
`rt.Runtime`, `rt.Capabilities` and `rt.HostFunction`. The packages `lang`,
`code`, `vm`, `values` and `rt`'s remainder are the engine's working surface —
public because Java needs no better tool, and stable in practice but not part of
the host contract.

---

## 2. Five minutes

```java
import org.jdesktop.lg3d.mandela.api.Mandela;

// a) one expression, one throwaway engine
Object n = Mandela.eval("1 + 2 * 3");                    // Long 7

// b) a program that prints somewhere you choose
Mandela.engine()
       .output(line -> System.out.print(line))
       .build()
       .run("for i in 1..3 { println(i) }", "count.mnd");

// c) hand the script your data and your functions
var engine = Mandela.engine()
        .bind("prices", List.of(12.5, 40.0))
        .bind("rate", 0.05)
        .bind("tax", (Function<Double, Double>) v -> v * 0.05)
        .build();
Object total = engine.run("prices.map { x -> x * rate }.sum()");   // 2.625

// d) call a function the script defined
engine.run("fun label(t: Str) -> Str { \"[\" + t + \"]\" }");
String s = (String) Interop.toJava(engine.call("label", "ok"));

// e) ask what is wrong, run nothing
List<Diagnostic> problems = Mandela.check("let x = ");

// f) the desktop shape: a script that owns a directory
Mandela.application(Path.of("/home/me/notes")).build()
        .runFile(Path.of("/home/me/notes/cleanup.mnd"));
```

Every one of those is the whole API surface a typical host needs. The rest of
this document is what to do when they are not enough — which is mostly
*capabilities* and *EditorServices*.

---

## 3. The facade: `api.Mandela`

| Member | What it gives you |
| --- | --- |
| `LANGUAGE_NAME` `"Mandela"` · `PROJECT_NAME` `"Project Mandela"` | the names to show a user |
| `LANGUAGE_VERSION` / `IMPLEMENTATION_VERSION` `"1.0"` | language vs jar version |
| `FILE_EXTENSION` `"mnd"` | the suffix, without the dot |
| `ENGINE_NAMES` / `MIME_TYPES` | the JSR-223 aliases (`mandela`, …) and `text/x-mandela` |
| `engine()` | a `Runtime.Builder` seeded with `Capabilities.console()` |
| `engine(Capabilities caps)` | the same builder over a sandbox you decided on |
| `application(Path root)` | builder with `Capabilities.desktop(root)`, that file root and the process console |
| `eval(String source)` | run once, print to `System.out`, return the last value |
| `eval(String source, Map<String,Object> values)` | the same, with names bound first (each via `Interop.toMandela`) |
| `check(String source)` / `check(String, String name)` | diagnostics, never throws |
| `scriptEngine()` | the JSR-223 engine from the platform registry |
| `scriptEngineDirect()` | the same engine without the registry (shaded jar, odd loader) |
| `about()` | language, project, version, extension, names, mimes, module list |

`engine()` starts at the safe end on purpose: a host that forgets to say what it
trusts gets a console and no files, which is the mistake that cannot hurt anyone.
`eval` inherits that, so an `eval`-ed snippet cannot read your disk — a program
that needs a filesystem builds an engine and keeps it.

---

## 4. Configure an engine: `Runtime.Builder`

| Method | Notes |
| --- | --- |
| `capabilities(Capabilities caps)` | the sandbox; **adopts `caps.fileRoot()`** as the engine's filesystem *and* import root |
| `files(Path root)` | give (or with `null`, remove) a `std.fs` filesystem; also sets the import root |
| `importRoot(Path root)` | where a bare `import "name"` looks, independent of the sandbox root |
| `bind(String name, Object value)` | publish one host value or function; converted, refused if unconvertible |
| `bindAll(Map<String,Object> values)` | several at once; null map is a no-op |
| `library(StandardLibrary table)` | share the standard library's tables between engines |
| `output(Consumer<String> sink)` | where `print` / `println` go |
| `error(Consumer<String> sink)` | where `eprintln` and uncaught errors go |
| `build()` | the `Runtime` |

Three things surprise hosts, so they are stated here rather than discovered:

- **The sink receives characters, not lines.** `println` terminates its own
  line, so a chunk can be `"2\n"`, or `"partial"` with no terminator at all when
  the script used `print`. A host that appends a newline per callback gets
  double-spaced output — which is exactly the bug the desktop's own editor had
  before it learned this. Append the text, and add a terminator only if it is
  missing.
- **`capabilities()` and `files()` overlap deliberately.** The path checks are
  made against `Capabilities.fileRoot()`, so naming the root twice would be a
  host forgetting one of them and shipping a sandbox `std.fs` cannot open. Call
  `files()` afterwards only when you want a *different* view.
- **`bind` before the first `run` is better than after.** A name the compiler
  can see is a name it can type-check and an editor can complete. The builder's
  binds are all compile-time; `Runtime.bind` is a runtime publish.

---

## 5. Drive an engine: `Runtime`

| Method | Contract |
| --- | --- |
| `run(String source)` | run under the name `<mandela>`; returns the last expression's value |
| `run(String source, String sourceName)` | the same, with the name your diagnostics should carry |
| `run(CompiledFunction body)` | run something compiled earlier — the compile-once/run-many door |
| `runFile(Path file)` | read the file, run it under its own path |
| `compile(String source, String name)` | a `CompiledFunction`, no execution |
| `check(String source, String name)` | diagnostics; **the one entry point that never raises** |
| `disassemble(String source)` | the instruction stream, the text `mandela dump` prints |
| `bind(String, Object)` / `get(String)` | publish and read back a global |
| `call(String name, Object... args)` | invoke a script function from Java |
| `globals()` | the live global table — the engine's own map, not a copy |
| `knownGlobals()` | every name a fresh engine already answers to |
| `setOutput(Consumer, Consumer)` | re-point the console after `build()` |
| `trace()` / `failureTrace()` | frames now executing / frames the last failure ran through |
| `machine()` / `library()` / `capabilities()` | the interpreter, the library, the sandbox |
| `evalLine(String)` / `evalEntry(String, String)` | the REPL's two reading modes (`REPL_SILENT` suppresses an echo) |

**One engine is not one run.** The global table is kept between calls — that is
what makes a REPL and a re-runnable selection behave — while `run` resets the
instruction budget and the stack. A host that wants a clean slate builds a
second engine. That is cheap: the library's member tables are read-only after
construction and may be shared with `Builder.library(table)`, which is what a
host that builds one engine per document does to stay warm. The one caveat:
a `StandardLibrary` resolves `import` through a single importer, so if two
engines import files from different roots, give each its own library.

**`import` runs in a child engine** — same capabilities, separate machine, and
whatever the module binds stays in the module. Cycles are refused rather than
answered with a half-built module map. `MAX_IMPORT_DEPTH` is 32.

**Threading.** An engine is not a monitor: two threads running one engine will
share its global table and its machine. Build one engine per thread (or per
run) rather than synchronising; that is what the desktop's two hosts do, and the
JSR-223 engine is the one place that *does* serialise, because that specification
assumes a single-threaded host.

---

## 6. Values across the boundary: `api.Interop`

```java
Object in  = Interop.toMandela(hostValue);   // Java/Kotlin -> script
Object out = Interop.toJava(scriptValue);    // script -> Java
String s   = Interop.toJava(v, String.class);// ask for a shape
```

| Java / Kotlin | Mandela |
| --- | --- |
| `null` | `Null` |
| `Boolean` | `Bool` |
| `Byte`/`Short`/`Integer`/`Long` | `Int` (a `long`) |
| `Float`/`Double`/`BigDecimal` | `Double` |
| `String`/`Character`/`CharSequence` | `Str` |
| any `Collection`, any array | `List`, elements converted |
| a `Map` with string-ish keys | `Map`, values converted |
| a `Callable` (script or host function) | unchanged |
| anything with one public `invoke` — a Kotlin lambda, a Groovy closure — or one abstract method — `java.util.function`, any SAM | a `HostFunction` that calls it |
| anything else | **refused**, with the class named in the message |

The refusal is the load-bearing decision. A host *could* wrap an arbitrary
object and let a script poke at its fields — that is how a script ends up
reading `System.out.getClass().getProtectionDomain()`, and how a sandbox is
quietly bypassed. Here a Java object reaches a script only through a function
the host wrote, and both that function and the grant are visible in the host's
source, which is what a reviewer and `Capabilities.allowsJvm` can reason about.

**Kotlin needs no special case.** A Kotlin lambda or function reference compiles
to `kotlin.jvm.functions.FunctionN`, whose single abstract method is `invoke`,
so the reflective row covers it; Kotlin's `List` and `Map` are `java.util` types
by the time they reach the JVM.

```kotlin
val engine = Mandela.engine()
    .bind("greet") { name: String -> "hi $name" }
    .output { print(it) }
    .build()
engine.run("""println(greet("world"))""")
```

Naming a function explicitly, when you want the name in the script's errors:

```java
engine.bind("upper", Interop.fn("upper", args ->
        ((String) args[0]).toUpperCase()));      // Function<Object[], Object>
engine.bind("now",   Interop.supplier("now", Instant::now));
engine.bind("log",   Interop.consumer("log", System.out::println));
engine.bind("clamp", Interop.fn1("clamp", v -> Math.min(10, (Integer) v)));
```

On the way out, `toJava` unwinds `List`/`Map` into `java.util` copies a host may
keep after the script is done with them, and leaves everything that is already a
Java object alone. `toJava(value, Class)` accepts `String`, `Boolean`, `Long`,
`Integer`, `Double`, a `List` or a `Map` (or `Object`), and says so rather than
quietly returning something else — asking for an `int` for a value that does not
fit is an error, not a truncation.

`Interop.tryFunctional(value)` is the conversion behind the SAM row, exposed for
a host that wants to test a value before binding it.

---

## 7. Decide what a script may do: `rt.Capabilities`

```java
Capabilities caps = Capabilities.builder()
        .grant(Grant.FILE_READ).grant(Grant.FILE_WRITE).fileRoot(workDir)
        .allowJvm("com.example.rules.Rule")   // implies Grant.JVM
        .maxSteps(10_000_000L)
        .maxDepth(256)
        .label("rule-engine")
        .build();
```

Grants: `STDOUT`, `STDERR`, `STDIN`, `FILE_READ`, `FILE_WRITE`, `ENV`, `NETWORK`,
`JVM`, `PROCESS`, `THREAD`. Defaults: `DEFAULT_STEPS` 200 000 000 and
`DEFAULT_DEPTH` 1024.

| Profile | Grants | Steps | Depth | Right for |
| --- | --- | --- | --- | --- |
| `open()` | all ten | 200 M | 1024 | the CLI, code the runner's author wrote |
| `console()` | `STDOUT`, `STDERR` | 20 M | 512 | a pasted snippet, a one-shot `eval`, the editor |
| `desktop(root)` | console, `FILE_READ`, `FILE_WRITE` under `root`, `THREAD` | 50 M | 1024 | a desktop automation |
| `webPage()` | none | 5 M | 256 | anything that arrived from an untrusted source |

`FILE_READ`/`FILE_WRITE` are enforced against `fileRoot` by `resolve(path, what)`,
which returns the confined path or raises `PermissionError` — so a `..` escape is
the engine's problem, not yours. `NETWORK`, `PROCESS`, `STDIN` and `JVM` are
declared in the vocabulary and enforced only where a surface consumes them; today
no stdlib function opens a socket or a process, so granting them widens nothing.
`time.sleep` is the one place `THREAD` is required.

Refusal is never silent: `require(grant, what)` throws
`MandelaError("PermissionError", "this host (<label>) does not allow <what>; ask
the embedding application for the <GRANT> capability")`. Budgets throw
`LimitError` quoting the ceiling — a runaway loop stops in about 1.3 s at 20 M
instructions rather than hanging the host. Both are catchable *inside* the
script (`try` / `catch`), which is what lets a well-written script degrade.

For a status line or a consent dialog, `describe()` lists the grants and `label()`
names the profile; `toString()` is the same in one string.

---

## 8. When a script fails

Three exception shapes, and the difference is what you show the user:

| Raised | Means | Host's move |
| --- | --- | --- |
| `lang.LangException` | the text does not parse or compile | a gutter marker: it carries a `Diagnostic` with source name, line, column and the parser's own words |
| `values.MandelaError` | the program ran and stopped | a failure report: `kind()` (`TypeError`, `KeyError`, `PermissionError`, `LimitError`, …) and a message; it is a `RuntimeException` with a script trace |
| anything else from a bound function | your own code threw | the message reaches the script, which may catch it |

`Runtime.check(source, name)` (and `Mandela.check`, and `MandelaScriptEngine`'s
compile path) converts all of the first two into a `List<Diagnostic>` and never
throws — the reason the editor can call it on every keystroke. A
`lang.Diagnostic` is `(sourceName, line, column, endLine, endColumn, severity,
message, rule)`: `severity` is `ERROR` / `WARNING` / `INFO` / `HINT` with
`isError()` as the "this blocks execution" test, `location()` is the
`source:line:column` prefix a command-line host prints, and `rule` is the stable
check identifier a host should branch on instead of matching the message text —
today `"syntax"` for anything the grammar cannot read, `"undefined-name"` for a
name with no declaration (the expected state halfway through typing a file, so
an editor marks it without stopping the run) and `"param-order"` for a required
parameter behind a defaulted one.

A host that runs a script and shows output should keep the output even when the
run failed; the half-line printed before the bomb is usually the clue. That is
the behaviour of both shipped hosts, and the reason `MandelaTools` keeps two
buffers and joins them rather than throwing the first away.

---

## 9. Editor and tool services: `api.EditorServices`

Pure functions over source text. No engine, no capabilities, no side effect, so
an editor can call them on every keystroke without a thread.

| Method | Returns |
| --- | --- |
| `keywords()` | every reserved and contextual word — the same list the parser consumes |
| `globals()` | the names a default engine answers to |
| `moduleNames()` | what `use std.<name>` may ask for |
| `valueMembers(typeName)` | the members of `Str`, `List`, `Map`, `Range`, `Int`, `Double` (properties before methods, the resolution order) |
| `moduleMembers(module)` | the members of one `std` module |
| `outline(source)` | `List<Entry>` — top-level `name : kind` symbols for a structure panel |
| `vocabulary(source)` | every word that should be painted, including the ones you declared |
| `complete(source, caret)` / `complete(source, caret, prefix)` | up to `MAX_CANDIDATES` (80) candidates for the text before the caret |
| `prefixAt(source, caret)` | the fragment being typed |
| `membersOf(receiver, source)` | members valid on a receiver, using the annotations in the file |
| `starterTemplates()` | named snippets for a "new file" action |
| `languageName()` / `fileExtension()` | `"Mandela"` / `"mnd"` |

`Entry` is `record Entry(String name, String kind, int line, int column)` with
`display()` = `name + " : " + kind`. `BUFFER` (`<buffer>`) is the source name for
unsaved text.

Two honest limits: completion is name-based, so it uses declared types and
annotations and does not infer the type of `let s = "hi"` — annotating the binding
is what makes `s.le` complete; and `complete(source, caret)` answers empty rather than guessing,
so an editor never offers a member the runtime does not have.

`Mandela.check(source, name)` is the diagnostics half of the same story; an editor
that wires both gets the language's own answers instead of a second grammar to
maintain — which is the point. Espresso does exactly this, and its colouring,
outline, problems and completion cannot drift from the parser's.

---

## 10. JSR-223, for hosts that already speak it

```java
ScriptEngine engine = new ScriptEngineManager().getEngineByName("mandela");
engine.put("prices", List.of(12.5, 40.0));
engine.put("rate", 0.05);
Object total = engine.eval("""
        let tax = { v -> v * rate }
        prices.map(tax).sum()
        """);
// total is a Double, and ((Invocable) engine).invokeFunction("tax", 9.0) works
```

The engine is `Compilable` and `Invocable`. One `eval`:

1. announces the engine's attributes into the engine scope;
2. pushes engine and global `Bindings` into the runtime, each converted;
3. re-points the console at `ScriptContext.getWriter()` / `getErrorWriter()`, so
   `println` lands where the host asked;
4. compiles and runs; a `LangException` or `MandelaError` becomes a
   `ScriptException` carrying the real file, line and column;
5. writes back into the engine scope, in plain Java form, every name the run
   created or changed, and returns the last value through `Interop.toJava`.

A binding with no Mandela form stays on the host's side of the window:
`engine.get(key)` still returns it and the script sees an undefined name —
skipping quietly beats reflecting, and beats throwing at the boundary for a value
the script never mentions.

`invokeFunction(name, args...)` and `invokeMethod(obj, name, args...)` call into
the script, and `getInterface(cl)` / `getInterface(self, cl)` hand you a dynamic
proxy that implements a **Java interface** with the script's functions of the same
name — the call that makes a script a plug-in with nothing compiled against
anything. It returns `null` when `cl` is not an interface or any non-default
method has no script function behind it, because a half-built proxy that throws
three clicks later is worse than no proxy.

Default sandbox is `console()`. A host that means more passes its own
`Capabilities` (or a configured `Runtime`) to the constructor. Discovery can fail
under a shaded jar or an odd class loader; `Mandela.scriptEngineDirect()` builds
the same engine without the registry, and throws a message that says so.

---

## 11. Two shipped hosts, as recipes

Both are in `lg3d-apps`; read them as worked examples of the rules above.

**Espresso — `org.jdesktop.lg3d.apps.texteditor.MandelaTools`.** A document the
user wrote, run inside the editor's JVM:

```java
static RunResult runInProcess(String source, String name) {
    StringBuilder out = new StringBuilder();
    StringBuilder err = new StringBuilder();
    try {
        Mandela.engine()                                  // console(): no files
                .output(line -> append(out, line))
                .error(line -> append(err, line))
                .build()
                .run(source, name);
        return new RunResult(out.toString(), err.toString());
    } catch (RuntimeException failure) {                  // parse, compile, script
        return new RunResult(out.toString(), err + terminated(failure.getMessage()));
    }
}
```

- **A fresh engine per run**, so a script cannot see the globals its previous run
  left behind.
- Runs on a **virtual thread** and delivers on the EDT
  (`Thread.ofVirtual().start` + `SwingUtilities.invokeLater`) — the language has
  no async, so a blocking script blocks *your* worker thread, and that is the one
  you chose.
- Whatever printed is shown even when the run failed.
- Every panel it fills is gated: `READ` for completions, `DIAGNOSE` for problems
  and outline, `FILE_IO` for the Output panel, `WRITE` for the template action —
  and when a gate is missing the extension degrades with a message instead of
  throwing.

**Web Browser — `…webbrowser.ext.builtin.MandelaUserscriptsExtension`.** Third-party
code, so the strictest profile plus three bound names:

```java
Mandela.engine()
    .capabilities(Capabilities.webPage())        // no files, no console grant, 5 M steps
    .bind("page", Map.of("url", url, "title", title))
    .bind("inject", collect)                    // queue JavaScript for the page
    .bind("note", note)                          // the browser log
    .bind("println", console("println", note, ""))
    .bind("print",  console("print",  note, ""))
    .bind("eprintln", console("eprintln", note, "! "))
    .output(line -> echo(note, line)).error(line -> echo(note, line))
    .build().run(source, name);
```

`webPage()` withholds `STDOUT`, so the natural `println("…")` a script author
would write would abort the run with a `PermissionError` about a console the host
does not have. The fix is not to grant `STDOUT` — that is a path to the terminal
that launched the browser — it is to **bind the names**: `bind` overrides the
stdlib global, so the script's console lands in the browser log and no new door
opens. The injections a script queued before it died are still flushed, from a
`finally`, because the script did ask for them.

---

## 12. The command line, as a third host

`mandela` (or `java -jar lg3d-mandela.jar`, or
`./gradlew :lg3d-mandela:mandelaRun -Pscript=hello.mnd` / `:mandelaRepl`):

```
usage: mandela <command> [options] [args]

commands:
  run FILE [args...]   run a program (its directory is the file sandbox)
  check FILE...        compile without running; print every finding
  eval PROGRAM         run one program from the command line
  repl                 start an interactive session
  dump FILE            print the instructions the compiler produced
  about                print the version and the module table
  help                 print this text

options for run, eval, repl and dump:
  -s, --sandbox DIR    confine file access to DIR instead of the script's directory
  -u, --unrestricted   give the program the host's full rights; only for code you wrote and trust
  -t, --trace          print the script's frame trace when it fails
```

Exit codes: `0` success, `1` usage, `2` the program failed — a compile error is a
program failure, not a usage error, because the author of the script is the
person who needs to hear about it.

---

## 13. Do and don't

| Do | Don't |
| --- | --- |
| Start from `Mandela.engine()` and add what the script needs | Start from `open()` because it is convenient |
| Bind plain values, collections and functions | Bind a domain object and hope reflection works — it is refused, on purpose |
| Build one engine per run or per thread | Share one engine between two threads |
| Compile once with `compile(...)`, then `run(body)` often | Re-parse a template on every keystroke |
| Call `check(source, name)` in a keystroke handler | Call `run` to find out whether it compiles |
| Route the console to a sink you own | Assume `System.out` is where the user will read it |
| Name the source (`file.mnd`, `userscript`, `<buffer>`) | Run everything under `<mandela>` and wonder which document failed |
| Keep the output when a run fails | Discard the printed half because the exit was bad |
| Report a `PermissionError` in your own words too | Leave the user with only the grant's name |

---

## 14. Compatibility

`LANGUAGE_VERSION` is the grammar's version, `IMPLEMENTATION_VERSION` the jar's;
`about()` answers both. Within `1.x`:

- the public surface in §3–§7 and §9–§10 is the contract; the packages behind it
  may be reorganised;
- a program that runs on `1.0` runs on `1.x` — new *contextual* keywords are only
  ever introduced where the grammar previously failed, which is why the
  contextual set is enumerated in the language guide rather than implied;
- a grant is never added to a profile silently: a new side effect needs a new
  `Grant` constant, and a host compiled against `1.0` will keep refusing it;
- `Capabilities` and `EditorServices` results are ordered, so an editor's popup
  and a golden test do not shuffle between builds.
