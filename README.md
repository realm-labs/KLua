# KLua

KLua is a work-in-progress pure Kotlin Lua runtime for JVM 17+. It aims to provide a Lua 5.5 source runtime, a C-Lua-like low-level stack API for Java embedders, Kotlin-friendly convenience APIs, and an interpreter-first runtime architecture that can grow into debugging, tooling, conformance hardening, and benchmark-driven optimization.

## Status

KLua is a pre-1.0 release candidate and is not yet published. Its public ABI, Maven artifacts, executable distributions, user guides, and canonical JDK 17 performance baseline are locally checked; the version/tag/publishing actions in the release checklist still require explicit authorization.

The repository includes the complete source-to-interpreter path, KLua bytecode packages, low- and high-level embedding APIs, Kotlin helpers, source-backed base/math/string/table/UTF-8/package/coroutine/IO/OS/debug libraries, sandbox controls, lifecycle behavior, source debugging, DAP and CLI tooling cores, conformance coverage, compiled examples, and JMH benchmarks. Accepted JVM/host adaptations are recorded in the conformance matrix.

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
- `klua-stdlib`: source-backed base, math, string, table, UTF-8, package, coroutine, IO, OS, and debug library integration.
- `klua-debug`: source-level debugger state, breakpoints, stepping, frames, variables, and live sessions.
- `klua-dap`: typed Debug Adapter Protocol models, JSON/framing, wire sessions, and live-session integration.
- `klua-tools`: installed `klua` launcher, CLI debugger, KLua bytecode compiler, and standalone stdio DAP adapter.
- `klua-examples`: compiled and executed Java/Kotlin embedding examples; not a release artifact.
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

The local artifact coordinates, supported module surfaces, ABI policy, and non-publishing verification commands are defined in [the release contract](docs/KLua_Release_Contract.md).

Build and smoke-test every local release artifact and executable distribution without publishing:

```sh
./gradlew releaseCandidateCheck
```

The executable archives are written to `klua-tools/build/distributions`. After unpacking one, use `bin/klua` on Unix-like systems or `bin/klua.bat` on Windows. The complete Maven-layout handoff, distributions, and sorted checksum manifest are assembled under `build/release/klua-<version>`.

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

### Process Exit Handling

`os.exit` never calls `System.exit` directly. It maps Lua's boolean or integer status argument to a JVM integer status, invokes `LuaConfig.exitHandler`, then throws `LuaExitException` so embedders can decide how to terminate or report the script.

```kotlin
val state = LuaState.create(
    LuaConfig(
        exitHandler = LuaExitHandler { status, closeState ->
            println("Lua requested exit $status")
        },
    ),
)
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

For untrusted or game-server scripts, start with `LuaConfig.production()`. The preset disables debugger attachment, applies a default one-million-instruction limit, selects the base/math/string/table/utf8/coroutine libraries, and disables unsafe standard-library host access. That last policy removes base `dofile`/`loadfile` and prevents package, I/O, and OS libraries from opening even if they are accidentally included in `standardLibraries` or opened directly. Explicit host globals and registered userdata types remain available.

```kotlin
val state = LuaState.create(LuaConfig.production(instructionLimit = 250_000))
state.register("hostTick") { LuaReturn.of(42L) }
LuaStdlib.openLibs(state)
```

Filesystem, module-path, process, environment, and OS access require an explicit compatibility opt-in:

```kotlin
val config = LuaConfig(
    instructionLimit = 250_000,
    standardLibraries = LuaStandardLibrary.all(),
    unsafeStandardLibraryAccessEnabled = true,
)
```

The general-purpose `LuaConfig()` default keeps `unsafeStandardLibraryAccessEnabled = true` for local tooling and trusted embedding; use the production preset when executing untrusted scripts.

The corresponding repository programs are compiled and executed by `./gradlew :klua-examples:test`:

- [KotlinEmbeddingExample.kt](klua-examples/src/main/kotlin/io/github/realmlabs/klua/examples/KotlinEmbeddingExample.kt)
- [JavaEmbeddingExample.java](klua-examples/src/main/java/io/github/realmlabs/klua/examples/JavaEmbeddingExample.java)

## Documentation

- [Embedding guide](docs/KLua_Embedding_Guide.md): Java/Kotlin setup, high- and low-level APIs, host functions, userdata, coroutines, bytecode, and LuaJ-style migration notes.
- [Sandbox and standard library guide](docs/KLua_Sandbox_and_Standard_Library.md): production configuration, library gates, capabilities, limits, I/O, and deployment checks.
- [Debug tooling guide](docs/KLua_Debug_Tooling.md): installed CLI debugging, bytecode tooling, and standalone stdio DAP integration.
- [Performance guide](docs/KLua_Performance_Guide.md): workload advice, benchmark reproduction, comparable measurement, and release gates.
- [Conformance matrix](docs/KLua_Conformance_Gaps.md): source-backed coverage and accepted JVM adaptations.
- [Release contract](docs/KLua_Release_Contract.md): artifacts, ABI checks, compatibility, and versioning.
- [Draft v1 release notes](docs/KLua_Release_Notes_1.0.0.md): release highlights, supported surfaces, and known host adaptations.
- [Release checklist](docs/KLua_Release_Checklist.md): clean-checkout verification and the explicitly gated version/tag/publication sequence.

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

## Benchmarks

The `klua-jmh` module contains JMH baselines for deliberately separate costs:

- `CompileBenchmark.compileNumericLoop` recompiles a fixed numeric-loop source through parsing, compilation, and KLua bytecode-package encoding.
- `VmExecutionBenchmark.executeNumericLoop` reuses a compiled top-level function and measures creation and execution of a fresh VM coroutine running 10,000 numeric-loop iterations.
- `RuntimeWorkloadBenchmark` compares 10,000 Lua calls, 10,000 host calls, and 10,000 table writes followed by 10,000 reads using the same fresh-coroutine boundary.

Build and list the generated benchmark jar:

```text
./gradlew :klua-jmh:jmhJar
java -jar klua-jmh/build/libs/klua-jmh-0.1.0-SNAPSHOT-jmh.jar -l
```

Run the configured warmup, measurement, and fork settings with:

```text
./gradlew :klua-jmh:jmh
```

Benchmark numbers are only comparable when the JVM, hardware, operating system, power settings, and JMH arguments are held constant. The short smoke commands used during development prove that benchmarks execute; they are not optimization evidence.

Recorded checkpoints and the evidence used to select optimization targets live in [docs/KLua_Benchmark_Baseline.md](docs/KLua_Benchmark_Baseline.md). For user-facing reproduction and interpretation guidance, see [docs/KLua_Performance_Guide.md](docs/KLua_Performance_Guide.md).

See [docs/KLua_Architecture.md](docs/KLua_Architecture.md) for the full architecture notes.

## Roadmap

The project is organized around small, testable milestones: foundation, lexer/parser, compiler skeleton, minimal VM, expressions and control flow, functions, tables, closures, metatables, public APIs, userdata and JVM interop, standard libraries, coroutines, debugging, DAP tooling, packaging, sandboxing, performance work, Lua 5.5 conformance hardening, v1.0, and optional JVM bytecode generation.

See [docs/KLua_Implementation_Milestones.md](docs/KLua_Implementation_Milestones.md) for the milestone plan.

## Contributing

Before making implementation changes, read [docs/KLua_Codex_Goal.md](docs/KLua_Codex_Goal.md). It defines the module boundaries, delivery rules, language target policy, implementation order, testing requirements, and definition of done for this repository.

For behavior-sensitive Lua 5.5 work, inspect the local official Lua 5.5 source tree at `~/Downloads/lua-lua-a5522f0` before deciding semantics. Use that source as the primary reference for actual logic behavior; manuals and local `lua5.5` probes are supporting checks.

## License

KLua is available under the [MIT License](LICENSE).
