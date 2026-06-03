# KLua

KLua is a work-in-progress pure Kotlin Lua runtime for JVM 17+. It aims to provide a Lua-compatible source runtime, a C-Lua-like low-level stack API for Java embedders, Kotlin-friendly convenience APIs, and an interpreter-first runtime architecture that can grow into debugging, tooling, compatibility profiles, and benchmark-driven optimization.

## Status

KLua is pre-1.0 and not production-ready. Public APIs may change while the runtime moves toward a complete Lua implementation.

The repository currently includes a multi-module Gradle project, lexer/parser/compiler/VM pieces, internal KLua bytecode and disassembly support, a default Lua 5.4 configuration, basic globals and native function calls, a Java-friendly `LuaState` API, a higher-level `Lua` facade, Kotlin extension helpers, tests, and a JMH benchmark module. Standard libraries, debug tooling, DAP support, bytecode loading, sandboxing, broader compatibility profiles, and performance work are still roadmap items.

## Goals

- Build a Lua-compatible source runtime for JVM 17+, with Lua 5.4 as the first and default target.
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
- `klua-stdlib`: planned standard library integration.
- `klua-compat`: Lua version profile and compatibility behavior.
- `klua-debug`: planned runtime debugging internals.
- `klua-dap`: planned Debug Adapter Protocol integration.
- `klua-tools`: planned command-line tools.
- `klua-jmh`: JMH benchmarks.
- `klua-tests`: cross-module foundation, integration, and compatibility tests.

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

The project is organized around small, testable milestones: foundation, lexer/parser, compiler skeleton, minimal VM, expressions and control flow, functions, tables, closures, metatables, public APIs, userdata and JVM interop, standard libraries, coroutines, debugging, DAP tooling, packaging, sandboxing, performance work, compatibility hardening, v1.0, and optional JVM bytecode generation.

See [docs/KLua_Implementation_Milestones.md](docs/KLua_Implementation_Milestones.md) for the milestone plan.

## Contributing

Before making implementation changes, read [docs/KLua_Codex_Goal.md](docs/KLua_Codex_Goal.md). It defines the module boundaries, delivery rules, compatibility policy, implementation order, testing requirements, and definition of done for this repository.
