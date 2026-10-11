# Mandela Development Guide

This guide is for developers working on the Mandela language implementation itself. For information about using the language, see the [Language Guide](../../docs/mandela-language.md). For embedding the language in applications, see the [Embedding API Guide](../../docs/mandela-embedding-api.md).

## Prerequisites

- **JDK 21** - The module is pinned to JDK 21 via Gradle toolchain
- **Gradle 8.14** - Wrapper included in the project
- No other dependencies required

## Building

```bash
# Build the module
./gradlew :lg3d-mandela:build

# Build output location
# JAR: build-gradle/libs/lg3d-mandela-<version>.jar
```

## Testing

```bash
# Run all tests
./gradlew :lg3d-mandela:test

# Run a specific test class
./gradlew :lg3d-mandela:test --tests SyntaxTest

# Run with coverage (JaCoCo report in build-gradle/reports/jacoco/test/html)
./gradlew :lg3d-mandela:test jacocoTestReport
```

All tests are headless (`java.awt.headless=true`) and fast. The test suite includes:

- **SyntaxTest** - Parser behavior and error messages
- **LanguageTest** - End-to-end language semantics
- **StandardLibraryTest** - Every library function
- **EmbeddingApiTest** - Host API contract
- **InteropTest** - Value conversion
- **ScriptEngineTest** - JSR-223 integration
- **EditorServicesTest** - Editor services
- **CliTest** - Command-line interface
- **DocumentationTest** - Language guide code blocks
- **ShippedScriptsTest** - Repository automation scripts

## Running Scripts During Development

```bash
# Run a script with the freshly built engine
./gradlew :lg3d-mandela:mandelaRun -Pscript=path/to/script.mnd

# Start the REPL
./gradlew :lg3d-mandela:mandelaRepl

# Run with additional arguments
./gradlew :lg3d-mandela:mandelaRun -Pscript=script.mnd -Pargs="--verbose"
```

## Repository Automation

The project's own automation is written in Mandela and lives in `scripts/mandela/`:

```bash
# Run all repository checks
./gradlew :lg3d-mandela:repositoryChecks

# Run individual checks
./gradlew :lg3d-mandela:docLinksCheck
./gradlew :lg3d-mandela:htmlLinksCheck
./gradlew :lg3d-mandela:releaseCheck
./gradlew :lg3d-mandela:startMenuAudit
```

These are intentionally **not** part of the `check` task because they audit the whole repository, not just this module.

## Mutation Testing

```bash
# Run PIT mutation testing (report-only, on-demand)
./gradlew :lg3d-mandela:pitest

# Report location: build-gradle/reports/pitest/
```

The target is 0 surviving mutants, but this is currently report-only (not enforced in CI).

## Code Organization

### Package Structure

```
org.jdesktop.lg3d.mandela
├── api/          # Public API - only package hosts should import
│   ├── EditorServices.java
│   ├── Interop.java
│   ├── Mandela.java
│   └── MandelaScriptEngine.java
├── cli/          # Command-line interface
│   └── MandelaCli.java
├── code/         # Bytecode compiler
│   ├── Bytecode.java
│   ├── CompiledFunction.java
│   ├── Compiler.java
│   └── Disassembler.java
├── lang/         # Lexer, parser, AST
│   ├── AstNode.java
│   ├── Diagnostic.java
│   ├── Keywords.java
│   ├── Lexer.java
│   └── Parser.java
├── rt/           # Runtime, capabilities, standard library
│   ├── Capabilities.java
│   ├── HostFunction.java
│   ├── HostValues.java
│   ├── Runtime.java
│   └── StandardLibrary.java
├── values/       # Runtime values
│   ├── operators/
│   ├── BoolValue.java
│   ├── DoubleValue.java
│   ├── IntValue.java
│   ├── ListValue.java
│   ├── MapValue.java
│   ├── MandelaError.java
│   ├── NullValue.java
│   ├── RangeValue.java
│   ├── StrValue.java
│   └── Value.java
└── vm/           # Virtual machine
    ├── Frame.java
    └── Machine.java
```

### Dependency Rules

Follow these strict dependency rules:

- **`lang`** is a leaf - never imports `rt`, `api`, or `cli`
- **`code`** and **`values`** may reference each other (the only allowed cycle)
- **`vm`** depends on `code` and `values`
- **`rt`** depends on `vm`, `values`, and `code`
- **`api`** depends on `rt`, `vm`, `values`, and `lang`
- **`cli`** depends on everything

**Never allow**: `lang`, `values`, or `code` to import `rt`, `api`, or `cli`.

## Adding Language Features

### Adding a New Keyword

1. Add the keyword to `Keywords.RESERVED` or `Keywords.CONTEXTUAL`
2. Update the parser to recognize the keyword in the appropriate position
3. Add the keyword to `EditorServices.vocabulary` (automatic via `Keywords.ALL`)
4. Update the language guide (`../../docs/mandela-language.md`)
5. Add tests in `SyntaxTest` for both acceptance and rejection
6. Run `EditorServicesTest` to verify keyword list consistency

**Rule**: New keywords are **contextual by default**. Only add to `RESERVED` when the parser position already exists for it.

### Adding a New Operator

1. Add the operator to the appropriate `Value` subclass in `values/operators/`
2. Implement the operator logic with proper error handling
3. Add precedence information to the language guide
4. Add tests in `LanguageTest` for the operator behavior
5. Test error cases (type mismatches, etc.)

### Adding a Standard Library Function

1. Add the function to `StandardLibrary` in the appropriate module
2. Implement the function with capability checks if needed
3. Add the function to the language guide's standard library section
4. Add tests in `StandardLibraryTest`
5. If the function needs a capability, update `Capabilities` documentation

**Rule**: Every side effect must go through `Capabilities.require(grant, what)` with a clear error message.

### Adding a New Error Kind

1. Add the kind to `MandelaError` (if it's a runtime error)
2. Update the language guide's error section
3. Add tests for the new error kind
4. Update `EditorServices` if the error affects diagnostics

## Adding Host API Features

### Adding a New Editor Service

1. Add the method to `EditorServices` as a pure function (no engine needed)
2. Implement the logic using only the lexer/parser (no runtime)
3. Add tests in `EditorServicesTest`
4. Update the embedding guide if the service is public

### Adding a New Interop Conversion

1. Add the conversion logic to `Interop.toMandela` or `Interop.toJava`
2. Add tests in `InteropTest`
3. Update the embedding guide's conversion table
4. **Never** add reflection-based conversion - explicit only

## Testing Guidelines

### Test Organization

- **SyntaxTest** - Test what parses and exact error message fragments
- **LanguageTest** - Test end-to-end semantics
- **StandardLibraryTest** - Test every library member
- **EmbeddingApiTest** - Test host API contract
- **InteropTest** - Test value conversion
- **ScriptEngineTest** - Test JSR-223 integration
- **EditorServicesTest** - Test editor services
- **CliTest** - Test command-line interface
- **DocumentationTest** - Test language guide code blocks
- **ShippedScriptsTest** - Test repository automation scripts

### Writing Tests

1. **Test both success and failure cases** - A grammar change needs both the accepted form and the construct that must not be swallowed
2. **Assert error message fragments** - Error message text is part of the contract
3. **Use the real parser** - Don't re-implement parsing in tests
4. **Test with the real runtime** - Use `Runtime` for integration tests
5. **Keep tests headless** - Always use `java.awt.headless=true`

### Documentation Testing

The language guide is a tested artifact:

- `DocumentationTest` extracts every `` ```mandela `` block from the language guide
- Each block is parsed and compiled with the real parser
- Blocks with elision markers (`…`) are skipped
- The test fails if too few blocks are found

**Rule**: When updating the language guide, always run `DocumentationTest` to ensure code blocks are valid.

## Debugging

### Bytecode Inspection

```bash
# Disassemble a script to see the bytecode
./gradlew :lg3d-mandela:mandelaRun -Pscript=script.mnd dump
# Or:
java -jar build-gradle/libs/lg3d-mandela-<version>.jar dump script.mnd
```

### Tracing Script Execution

```bash
# Run with trace to see the frame trace on failure
./gradlew :lg3d-mandela:mandelaRun -Pscript=script.mnd -Pargs="--trace"
```

### REPL Debugging

```bash
# Start REPL for interactive testing
./gradlew :lg3d-mandela:mandelaRepl
```

## Common Tasks

### Adding a Test for a Language Feature

```java
@Test
void testNewFeature() {
    // Success case
    CompiledFunction body = runtime.compile("newFeature()", "test.mnd");
    Object result = runtime.run(body);
    assertEquals(expected, result);

    // Error case
    LangException ex = assertThrows(LangException.class, () ->
        runtime.compile("invalid newFeature", "test.mnd")
    );
    assertTrue(ex.getDiagnostic().getMessage().contains("expected error fragment"));
}
```

### Adding a Standard Library Function

```java
// In StandardLibrary.java
private static void registerMathModule(StandardLibrary lib) {
    lib.register("math", Map.of(
        "newFunction", HostFunction.of("math.newFunction", args -> {
            // Implementation
            return result;
        })
    ));
}
```

### Updating the Language Guide

When updating the language guide (`../../docs/mandela-language.md`):

1. Add the feature to the appropriate section
2. Include code examples in `` ```mandela `` blocks
3. Run `DocumentationTest` to verify all blocks compile
4. Update the CHANGELOG if it's a user-visible change

## Code Style

- **Naming**: `lowerCamelCase` for values and functions, `UpperCamelCase` for classes
- **Prefer `let` over `var`** in examples
- **One blank line between declarations**
- **No formatter** - manual style is the style guide
- **Keep javadoc examples valid** - They must parse with the real parser

## Security Considerations

### Capability Checks

Every side effect must go through `Capabilities.require(grant, what)`:

```java
Capabilities.require(Grant.FILE_READ, "reading file " + path);
```

The error message must name the grant and the operation.

### Value Conversion

Never use reflection to bind arbitrary objects:

```java
// WRONG - allows script to access arbitrary fields
engine.bind("obj", somePojo);

// RIGHT - bind only what the script needs
engine.bind("getValue", Interop.fn("getValue", args -> somePojo.getValue()));
```

### Budget Enforcement

The VM enforces instruction and depth budgets. When adding new bytecode instructions, ensure they increment the instruction counter.

## Performance Guidelines

- **Compile once, run many** - Use `compile(source, name)` then `run(body)` for repeated execution
- **Avoid string concatenation in loops** - Use list join instead
- **Closures capture by reference** - Be aware of shared loop variables
- **Tail calls are not optimized** - Use iteration for deep recursion

## Release Process

1. Update version in `build.gradle` and root `settings.gradle`
2. Update `CHANGELOG.md`
3. Run full test suite: `./gradlew :lg3d-mandela:test`
4. Run repository checks: `./gradlew :lg3d-mandela:repositoryChecks`
5. Update language guide if needed
6. Update embedding guide if needed
7. Commit with clear message
8. Create PR against `main`

## Troubleshooting

### Build Fails with "Cannot find symbol"

- Ensure you're using JDK 21: `java -version`
- Clean build: `./gradlew :lg3d-mandela:clean build`

### Tests Fail with Headless Error

- The tests require `java.awt.headless=true` (set in build.gradle)
- If running manually, pass: `-Djava.awt.headless=true`

### DocumentationTest Fails

- Check that all `` ```mandela `` blocks in the language guide are valid
- Look for elision markers (`…`) - blocks with them are skipped
- Ensure the extractor is finding the expected number of blocks

### ShippedScriptsTest Fails

- Check that all scripts in `scripts/mandela/` compile
- Verify shebang lines: `#!/usr/bin/env mandela`
- Ensure each script has a one-line purpose comment
- Check that scripts are run with `-s .` for repository root sandbox

## Additional Resources

- [Language Guide](../../docs/mandela-language.md) - Complete language reference
- [Embedding API Guide](../../docs/mandela-embedding-api.md) - Host integration
- [AGENTS.md](../AGENTS.md) - Module-specific guidance for all roles
- [Root AGENTS.md](../../AGENTS.md) - Project-wide conventions
