# KLua

KLua is a work-in-progress pure Kotlin Lua runtime for JVM 17+. It aims to provide a Lua 5.5 source runtime, a C-Lua-like low-level stack API for Java embedders, Kotlin-friendly convenience APIs, and an interpreter-first runtime architecture that can grow into debugging, tooling, conformance hardening, and benchmark-driven optimization.

## Status

KLua is pre-1.0 and not production-ready. Public APIs may change while the runtime moves toward a complete Lua implementation.

The repository currently includes a multi-module Gradle project, lexer/parser/compiler/VM pieces, internal KLua bytecode and disassembly support, KLua bytecode package compile/load APIs including packaged resource loading, a single Lua 5.5 runtime target, basic globals and native function calls, a Java-friendly `LuaState` API, a higher-level `Lua` facade, Kotlin extension helpers, partial base/math/string/table standard library support, initial debug/DAP/tooling foundations, initial instruction-limit enforcement, tests, and a JMH benchmark module. Broader standard libraries, debug tooling integration, sandboxing, Lua 5.5 conformance hardening, and performance work are still roadmap items.

## Goals

- Build a Lua 5.5 source runtime for JVM 17+.
- Provide a Java-friendly low-level `LuaState` stack API.
- Provide a Kotlin convenience layer for common embedding workflows.
- Keep the runtime interpreter-first: Lua source -> parser -> AST -> compiler -> KLua bytecode -> VM.
- Keep public API modules separate from compiler, VM, bytecode, parser, and AST internals.
- Add source-level debugging support as a runtime feature, not just as a wrapper around the JVM debugger.
- Optimize only after correctness and benchmark baselines exist.

## Modules

- `klua-core`: internal lexer, parser, AST, compiler, bytecode, value model, and VM runtime.
- `klua-api`: stable Java-friendly public API surface, including `LuaState`, `Lua`, `LuaChunk`, and host function types.
- `klua-kotlin`: Kotlin extension helpers for the public API.
- `klua-stdlib`: partial standard library integration, currently focused on base, math, string, and table functions.
- `klua-debug`: planned runtime debugging internals.
- `klua-dap`: planned Debug Adapter Protocol integration.
- `klua-tools`: planned command-line tools.
- `klua-jmh`: JMH benchmarks.
- `klua-tests`: cross-module foundation, integration, and conformance tests.

## Requirements

- JVM 17 or newer.
- The checked-in Gradle wrapper.

## Build And Test

The verified test command for this checkout is:

```sh
sh gradlew test
```

Once `gradlew` has executable permissions in the local checkout, this should also work:

```sh
./gradlew test
```

## Quick Examples

### Kotlin

```kotlin
import io.github.realmlabs.klua.api.Lua
import io.github.realmlabs.klua.kotlin.function
import io.github.realmlabs.klua.kotlin.globals
import io.github.realmlabs.klua.kotlin.set

fun main() {
    val lua = Lua.create()

    val answer = lua.load("return 40 + 2", "answer.lua").evalLong()
    println(answer)

    lua.globals["base"] = 20
    lua.globals["add"] = lua.function { left: Long, right: Long ->
        left + right
    }

    val result = lua.load("return add(base, 22)", "native-call.lua").evalLong()
    println(result)
}
```

### Java

```java
import io.github.realmlabs.klua.api.Lua;
import io.github.realmlabs.klua.api.LuaReturn;

public final class Example {
    public static void main(String[] args) {
        Lua lua = Lua.create();

        long answer = lua.load("return 40 + 2", "answer.lua").evalLong();
        System.out.println(answer);

        lua.globals().set("base", 20L);
        lua.globals().setFunction("add", context -> {
            Long left = context.toInteger(1);
            Long right = context.toInteger(2);
            if (left == null || right == null) {
                throw new IllegalArgumentException("add expects integer arguments");
            }
            return LuaReturn.of(left + right);
        });

        long result = lua.load("return add(base, 22)", "native-call.lua").evalLong();
        System.out.println(result);
    }
}
```

### Bytecode Packages

KLua bytecode packages can be compiled with the tools module command:

```sh
klua --compile scripts/main.lua build/klua/main.kluac
```

Packaged bytecode can be loaded from bytes or classpath resources through the public API:

```kotlin
val result = Lua.create()
    .loadBytecodeResource("scripts/main.kluac")
    .call("arg")
```

### Instruction Limits

`LuaConfig.instructionLimit` can cap VM bytecode instructions for chunk execution, exported Lua function calls, and coroutine resumes. A value of `0` keeps execution unlimited.

```kotlin
val lua = Lua.create(LuaConfig(instructionLimit = 100_000))
lua.load(script, "sandboxed.lua").exec()
```

### Standard Library Selection

`LuaConfig.standardLibraries` can whitelist which standard libraries `LuaStdlib.openLibs` installs. The default installs every available library, while `debugEnabled = false` still suppresses the debug library even when it is listed.

```kotlin
val state = LuaState.create(
    LuaConfig(
        instructionLimit = 100_000,
        standardLibraries = setOf(
            LuaStandardLibrary.BASE,
            LuaStandardLibrary.MATH,
            LuaStandardLibrary.STRING,
            LuaStandardLibrary.TABLE,
        ),
    ),
)
LuaStdlib.openLibs(state)
```

## Architecture

KLua is designed as a Lua-compatible runtime on the JVM, not as a direct port of PUC Lua internals. The intended runtime path is:

```text
Lua source
  -> lexer/parser
  -> AST
  -> KLua compiler
  -> custom register bytecode
  -> Kotlin/JVM interpreter
  -> optional JVM bytecode compiler later
```

See [docs/KLua_Architecture.md](docs/KLua_Architecture.md) for the full architecture notes.

## Roadmap

The project is organized around small, testable milestones: foundation, lexer/parser, compiler skeleton, minimal VM, expressions and control flow, functions, tables, closures, metatables, public APIs, userdata and JVM interop, standard libraries, coroutines, debugging, DAP tooling, packaging, sandboxing, performance work, Lua 5.5 conformance hardening, v1.0, and optional JVM bytecode generation.

See [docs/KLua_Implementation_Milestones.md](docs/KLua_Implementation_Milestones.md) for the milestone plan.

## Contributing

Before making implementation changes, read [docs/KLua_Codex_Goal.md](docs/KLua_Codex_Goal.md). It defines the module boundaries, delivery rules, language target policy, implementation order, testing requirements, and definition of done for this repository.

For behavior-sensitive Lua 5.5 work, inspect the local official Lua 5.5 source tree at `~/Downloads/lua-lua-a5522f0` before deciding semantics. Use that source as the primary reference for actual logic behavior; manuals and local `lua5.5` probes are supporting checks.

## License

KLua is available under the [MIT License](LICENSE).
