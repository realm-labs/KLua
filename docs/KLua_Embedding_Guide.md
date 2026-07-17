# KLua Embedding Guide

KLua targets JVM 17+ and Lua 5.5 source. It offers two public entry styles:

- `Lua` and `LuaChunk` are the high-level facade for loading scripts, setting host globals, registering userdata, and reading typed results.
- `LuaState` is the low-level stack API for explicit library installation, protected calls, stack manipulation, exact byte strings, and C-Lua-shaped integration.

The runtime is not published to a remote repository yet. In this checkout, depend on the relevant Gradle projects. The eventual Maven coordinates and compatibility policy are listed in [the release contract](KLua_Release_Contract.md).

## Kotlin getting started

Add `klua-kotlin` for the facade extensions. Add `klua-stdlib` when using `LuaState` with standard libraries.

```kotlin
dependencies {
    implementation(project(":klua-kotlin"))
    implementation(project(":klua-stdlib"))
}
```

The repository's complete, compiled example is [KotlinEmbeddingExample.kt](../klua-examples/src/main/kotlin/io/github/realmlabs/klua/examples/KotlinEmbeddingExample.kt). Its core flow is:

```kotlin
val lua = Lua.create()
val counter = Counter()

lua.registerType<Counter> {
    property("value", getter = { value -> LuaReturn.of(value.count) })
    method("add") { value, context ->
        value.count += context.toInteger(1) ?: error("integer delta expected")
        LuaReturn.none()
    }
}
lua.globals["counter"] = counter
lua.globals["twice"] = lua.function { value: Long -> value * 2 }

val result = lua.load(
    "counter:add(20); return twice(counter.value + 1)",
    "kotlin-example.lua",
).evalLong()
```

Use explicit chunk names. They appear in syntax errors, tracebacks, debugger frames, and packaged-bytecode metadata.

## Java getting started

Java applications normally depend on `klua-api`; add `klua-stdlib` when installing libraries. The complete compiled example is [JavaEmbeddingExample.java](../klua-examples/src/main/java/io/github/realmlabs/klua/examples/JavaEmbeddingExample.java).

```java
LuaConfig config = LuaConfig.production(50_000);
try (LuaState state = LuaState.create(config)) {
    LuaStdlib.openLibs(state);
    state.register("hostBase", context -> LuaReturn.of(40L));

    check(state.load(
        "return math.max(hostBase(), 20) + 2",
        "java-example.lua"
    ), state);
    check(state.pcall(0, 1), state);
    long result = state.toInteger(-1);
}
```

`LuaState` implements `AutoCloseable`. Closing a state releases roots and runs pending finalizers; use try-with-resources when the state has a bounded lifetime.

## High-level facade

`Lua.create(config)` owns a private state. It is suited to source or KLua-bytecode chunks that use language features and explicitly registered host values:

- `load(source, chunkName)` creates a reusable `LuaChunk`.
- `LuaChunk.call(arguments...)` returns all values in a `LuaReturn`.
- `evalLong`, `evalDouble`, `evalString`, and `evalBoolean` require the first return value to have the requested type.
- `globals().get`, `set`, and `setFunction` exchange primitives, strings, functions, and userdata.
- `registerType(Class, Consumer)` binds userdata methods and properties.

The facade does not automatically install standard libraries. Use `LuaState` with `LuaStdlib.openLibs` when a script needs base, math, string, table, UTF-8, coroutine, package, IO, OS, or debug library globals.

## Low-level stack API

Stack indices follow Lua conventions: positive indices count from the bottom, negative indices count from the top, and `absIndex` resolves a stable absolute index. `getTop`, `setTop`, `pop`, `pushValue`, `copy`, and `remove` manage stack shape.

Typical protected execution is:

1. Record or clear the current stack as appropriate.
2. Call `load` or `loadBytecode`; a successful load pushes a callable chunk.
3. Push arguments in call order.
4. Call `pcall(argumentCount, resultCount)`. Use `-1` for all results.
5. Read results with `toBoolean`, `toInteger`, `toNumber`, `toString`, `toBytes`, or `toUserData`.
6. Pop results or restore the prior top.

`LuaStatus.OK`, `SYNTAX_ERROR`, and `RUNTIME_ERROR` are explicit. On failure, `getLastError()` returns the structured exception and the stack contains the Lua error value. High-level chunk calls throw `LuaSyntaxException` or `LuaRuntimeException` instead.

## Host values and functions

The public mapping is intentionally small:

| JVM value | Lua value |
| --- | --- |
| `null` | `nil` |
| `Boolean` | boolean |
| `Byte`, `Short`, `Int`, `Long` | integer |
| `Float`, `Double` | float |
| `CharSequence` | string |
| `LuaFunction` | function |
| Other non-null object | userdata |

Lua strings are byte strings. `pushString` and `toString` are text-oriented boundaries; use defensive-copying `pushBytes` and `toBytes` when embedded NULs or malformed UTF-8 must be preserved exactly.

Register Java/Kotlin callbacks with `LuaFunction`, a Java functional interface. Arguments are one-based in `LuaCallContext`. Prefer nullable conversion methods for validation and return values with `LuaReturn.none()`, `of(...)`, or `ofValues(...)`. Throw `LuaRuntimeException` for a deliberate Lua-facing host error; other runtime exceptions are wrapped with host-frame context.

## Userdata

Register the most general applicable Java type first and more specific types afterward when bindings should merge across assignable classes. Methods receive the typed receiver and a `LuaCallContext`. Properties can have a getter, setter, or both.

Only explicitly registered members are visible through userdata lookup. Arbitrary reflection over host objects is not enabled. Passing a host object as a global or argument preserves its identity when Lua returns it.

## Coroutines and debugging

`LuaChunk.asCoroutineFunction()` and Lua-returned `LuaCoroutineFunction` values create `LuaCoroutineHandle` instances. `resume` returns one of `Returned`, `Yielded`, `RuntimeError`, or `DebugSuspended`; never infer the state from a nullable value. `LuaDebuggableCoroutineHandle` adds observers, live frames, local mutation, and hook access when `LuaConfig.debugEnabled` permits attachment.

For source breakpoints, stepping, frame inspection, the CLI debugger, and DAP hosting, see [KLua Debug Tooling](KLua_Debug_Tooling.md).

## Bytecode packages

`compileBytecode` creates KLua's versioned `.kluac` package, not a PUC Lua `.luac` file. Load it with `loadBytecode` or `loadBytecodeResource`. Resource names are classpath-relative and may be written with or without one leading slash. Packages validate their format marker and checksum before execution.

Do not treat bytecode as a sandbox boundary or a portable PUC Lua artifact. Rebuild packages when KLua's bytecode format changes.

## LuaJ-style migration notes

KLua does not expose LuaJ's `Globals` or `LuaValue` object model. Use `Lua` for host-value mapping or `LuaState` for explicit stack operations. KLua targets only Lua 5.5 source; it does not provide old-version modes or LuaJ compatibility aliases. LuaJ bytecode and official PUC Lua binary chunks cannot be loaded as KLua packages.

The most common migration changes are:

- replace `Globals.load(...).call()` with `Lua.load(...).call()` or `LuaState.load` plus `pcall`;
- replace `LuaValue` subclasses with JVM primitives, `LuaFunction`, or registered userdata;
- install libraries explicitly on `LuaState` and select unsafe host libraries through `LuaConfig`;
- handle structured `LuaRuntimeException` frames or explicit `LuaStatus` values instead of parsing error strings.

The examples are compiled and executed by `./gradlew :klua-examples:test`.
