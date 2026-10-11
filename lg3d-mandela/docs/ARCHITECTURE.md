# Mandela Architecture

This document describes the internal architecture of Project Mandela, including the package structure, data flow, and key design decisions.

## Package Overview

```
org.jdesktop.lg3d.mandela
├── api/          # Public API for host applications
├── cli/          # Command-line interface and REPL
├── code/         # Bytecode compiler and disassembler
├── lang/         # Lexer, parser, AST, diagnostics
├── rt/           # Runtime, capabilities, standard library
├── values/       # Runtime values and operators
└── vm/           # Virtual machine (frame, interpreter)
```

## Dependency Rules

The architecture follows strict dependency rules to maintain the module's contract:

- **`lang`** is a leaf package - it knows nothing above it
- **`code`** and **`values`** form the inner core - they reference each other (the only allowed cycle)
- **`vm`** runs on `code` and `values`
- **`rt`** wires the machine, library, and capabilities together
- **`api`** is the **only** package a host should import
- **`cli`** is the outermost layer, depending on everything below

**Never allow**: `lang`, `values`, or `code` to import `rt`, `api`, or `cli`.

## Component Layers

### 1. Language Layer (`lang`)

The language layer handles source text transformation:

- **Lexer** (`Lexer`) - Tokenizes source code into tokens
- **Parser** (`Parser`) - Recursive descent parser that builds an AST
- **AST** (`AstNode` and subclasses) - Abstract syntax tree representation
- **Diagnostics** (`Diagnostic`) - Error and warning reporting with source locations

Key design decisions:
- Recursive descent parser for clear error messages
- Full error recovery to report multiple issues in one pass
- Contextual keywords (`init`, `export`, `step`) to preserve user names
- Diagnostic rule IDs for host integration

### 2. Code Layer (`code`)

The code layer transforms AST into executable bytecode:

- **Compiler** (`Compiler`) - AST to bytecode translation
- **Bytecode** (`Opcode`) - Instruction set definition
- **Disassembler** (`Disassembler`) - Bytecode to text for debugging
- **CompiledFunction** - Compiled code container

Key design decisions:
- Stack-based bytecode for simplicity
- Instruction-level budgets for security
- Separate compilation and execution phases
- Bytecode is serializable for caching

### 3. Values Layer (`values`)

The values layer defines runtime value representations:

- **Value** - Base class for all runtime values
- **Value subclasses** - IntValue, DoubleValue, BoolValue, StrValue, ListValue, MapValue, etc.
- **Operators** - Operator implementation for each value type
- **HostFunction** - Bridge for host-provided functions

Key design decisions:
- Values are never boxed - `Int` *is* a Java `long`
- No reflection-based binding - explicit conversion only
- Operators are type-specific with clear error messages
- Host functions are wrapped, not reflected

### 4. VM Layer (`vm`)

The VM layer executes bytecode:

- **Machine** (`Machine`) - The interpreter with instruction pointer and stack
- **Frame** (`Frame`) - Call frame with locals and return address
- **Instruction dispatch** - Opcode to handler mapping
- **Budget enforcement** - Step and depth limits

Key design decisions:
- Stack-based execution model
- Instruction budgets prevent runaway loops
- Depth limits prevent stack overflow
- Frames are allocated per function call

### 5. Runtime Layer (`rt`)

The runtime layer wires everything together:

- **Runtime** (`Runtime`) - Main engine facade
- **Capabilities** (`Capabilities`) - Security profile
- **StandardLibrary** (`StandardLibrary`) - Built-in functions
- **HostValues** (`HostValues`) - Value conversion utilities

Key design decisions:
- Single `Runtime` per script execution context
- Capabilities checked at every side effect
- Standard library is a table of functions
- Filesystem is confined to a root directory

### 6. API Layer (`api`)

The API layer provides the public host interface:

- **Mandela** (`Mandela`) - Static facade for common operations
- **Interop** (`Interop`) - Value conversion between Java and Mandela
- **EditorServices** (`EditorServices`) - Editor integration (completion, outline)
- **MandelaScriptEngine** (`MandelaScriptEngine`) - JSR-223 implementation

Key design decisions:
- Static facade for simple cases
- Builder pattern for engine configuration
- Editor services are pure functions (no engine needed)
- JSR-223 for standard Java integration

### 7. CLI Layer (`cli`)

The CLI layer provides command-line tools:

- **MandelaCli** (`MandelaCli`) - Main entry point
- **Commands** - run, check, eval, repl, dump, about, help
- **Exit codes** - Contract with scripts and CI

Key design decisions:
- Exit codes are part of the contract
- Sandbox defaults to script directory
- REPL uses eval-line mode
- Check mode compiles without executing

## Data Flow

### Compilation Flow

```
Source text
    → Lexer (tokens)
    → Parser (AST)
    → Compiler (bytecode)
    → CompiledFunction
```

### Execution Flow

```
CompiledFunction
    → Machine (interpreter)
    → Frame (call context)
    → Values (runtime data)
    → StandardLibrary (built-in functions)
    → Capabilities (security checks)
    → Output (host-provided sink)
```

### Host Integration Flow

```
Host application
    → Runtime.Builder (configuration)
    → Runtime (engine)
    → bind (host values)
    → run/compile (script execution)
    → Interop (value conversion)
```

## Security Model

The security model is capability-based:

1. **Capabilities** - Define what a script may do (FILE_READ, FILE_WRITE, STDOUT, etc.)
2. **Profiles** - Pre-configured capability sets (open, console, desktop, webPage)
3. **Budgets** - Instruction and depth limits to prevent resource exhaustion
4. **Filesystem confinement** - All file operations checked against a root directory
5. **JVM access control** - Explicit allow-list only, no reflection

Every side effect goes through `Capabilities.require(grant, what)` which raises `PermissionError` if the grant is not available.

## Error Handling

Three exception shapes:

1. **LangException** - Parse or compile error, carries a `Diagnostic`
2. **MandelaError** - Runtime error, has kind (TypeError, KeyError, etc.) and message
3. **HostException** - Exception from a bound function

Hosts use `check(source, name)` to get diagnostics without throwing, which is how editors can report errors on every keystroke.

## Testing Strategy

The module has comprehensive test coverage:

- **SyntaxTest** - What parses, exact error message fragments
- **LanguageTest** - End-to-end semantics
- **StandardLibraryTest** - Every library member
- **EmbeddingApiTest** - Host contract
- **InteropTest** - Value conversion
- **ScriptEngineTest** - JSR-223 integration
- **EditorServicesTest** - Editor surface
- **CliTest** - Command-line interface
- **DocumentationTest** - Language guide code blocks
- **ShippedScriptsTest** - Repository automation scripts

All tests are headless (`java.awt.headless=true`) and fast.

## Performance Considerations

- **Bytecode interpretation** - Interpreted, not JIT-compiled
- **Instruction budgets** - Default 200M instructions (configurable)
- **Depth limits** - Default 1024 frames (configurable)
- **String concatenation** - Use list join, not `+` in loops
- **Closures** - Capture by reference (like Java effectively-final lambdas)

## Zero Dependencies

The module has no third-party dependencies by design:

- Uses only `java.base` and `javax.script`
- No AWT, Swing, or Java 3D
- No lg3d-core dependency
- This allows embedding anywhere (headless tests, editor JVM, browser)

## Future Extensions

Potential future enhancements (not currently implemented):

- JIT compilation for hot code paths
- Async/coroutine support
- Generics
- Operator overloading
- Metaclasses

Any such extension must maintain the zero-dependency property and the security model.
