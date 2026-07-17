# KLua Sandbox and Standard Library Guide

KLua provides policy controls for embedding untrusted or game/server scripts, but configuration is only one layer of a sandbox. The host remains responsible for limiting exposed userdata, callbacks, files, processes, environment data, network access in host functions, and surrounding JVM resources.

## Start with the production preset

```kotlin
val config = LuaConfig.production(instructionLimit = 250_000)
val state = LuaState.create(config)
LuaStdlib.openLibs(state)
```

`LuaConfig.production()`:

- disables debugger attachment;
- applies a one-million-instruction default limit, or the supplied limit;
- selects base, math, string, table, UTF-8, and coroutine libraries;
- disables package-path environment lookup;
- disables unsafe standard-library host access.

The general-purpose `LuaConfig()` default is intentionally permissive for compatibility and local tooling: debugging and unsafe standard-library access are enabled and execution is unlimited. Do not use that default for untrusted scripts.

## Standard library selection

`LuaStdlib.openLibs(state)` reads `state.config.standardLibraries`. Selection and host-access policy are separate gates.

| Library | Global/table | Safe preset | Additional gate |
| --- | --- | ---: | --- |
| Base | globals and `_G` | Yes | Unsafe mode adds `dofile` and `loadfile`; other base functions remain available. |
| Math | `math` | Yes | None. |
| String | `string` and string methods | Yes | None. |
| Table | `table` | Yes | None. |
| UTF-8 | `utf8` | Yes | None. |
| Coroutine | `coroutine` | Yes | None. |
| Package | `package`, `require` | No | Requires `unsafeStandardLibraryAccessEnabled`. Native C modules remain unavailable. |
| IO | `io` | No | Requires `unsafeStandardLibraryAccessEnabled`; accesses host files and standard streams. |
| OS | `os` | No | Requires `unsafeStandardLibraryAccessEnabled`; exposes time, environment, file/process-adjacent operations, and the configured exit handler. |
| Debug | `debug` | No | Requires `debugEnabled`; it is independent of the unsafe-library gate. |

`openBase`, `openMath`, and the other individual installers are available when the host wants a smaller surface. Direct attempts to open package, IO, or OS while unsafe access is disabled leave the state unchanged.

The detailed source-backed behavior and accepted JVM adaptations are recorded in [the conformance matrix](KLua_Conformance_Gaps.md). KLua is pure Kotlin: native C module loading, C errno/signal metadata, C locale/ABI behavior, and official PUC binary chunks are not provided.

## Instruction limits

`instructionLimit` caps VM bytecode instructions for chunk execution, exported Lua-function calls, and coroutine resumes. Zero means unlimited. Exhaustion raises a structured runtime error at the exact instruction boundary.

An instruction limit is a deterministic runaway-script guard, not a wall-clock deadline. Host callbacks can block or consume CPU without executing Lua instructions. Put blocking host work behind application-level timeouts, cancellation, or isolation appropriate to the deployment.

## Host exposure

Production mode still permits explicitly registered globals, functions, and userdata. This is intentional: the host defines the capability surface.

```kotlin
val state = LuaState.create(LuaConfig.production())
state.register("readScore") { LuaReturn.of(scoreService.currentScore()) }
state.pushUserData(restrictedPlayerView)
state.setGlobal("player")
LuaStdlib.openLibs(state)
```

Do not pass service locators, class loaders, reflection objects, unrestricted file abstractions, database connections, or application objects whose registered methods exceed the script's authority. KLua does not reflect arbitrary public methods automatically, but every callback and userdata property you register is a capability.

## Input, output, warnings, and exits

- `LuaConfig.standardInput` supplies text used by standard-input-backed operations.
- `LuaStdlib.openLibs(state, output)` routes `print` and warnings to a `Consumer<String>`.
- `LuaState.setFinalizerWarningOutput` controls protected finalizer failure reporting.
- `LuaConfig.exitHandler` observes `os.exit`; KLua then throws `LuaExitException` and never calls `System.exit` itself.

Treat output consumers and exit handlers as untrusted-input boundaries. Apply size limits, escaping, and logging policy outside KLua.

## Debug policy

With `debugEnabled = false`, the debug library is not installed and execution observers are rejected. This removes script-visible debug operations and debugger attachment, but it is not a substitute for library selection or host capability review. Debug-enabled states expose live frames and mutation facilities by design.

## Garbage collection and memory

KLua implements Lua-visible weak tables, ephemerons, finalization, and logical collector controls on top of the JVM. `collectgarbage("count")` reflects JVM heap usage, and logical step sizes are not hard byte quotas. The current API does not provide a per-state heap limit.

For adversarial workloads, combine instruction limits with process/container memory controls, bounded script/input sizes, bounded host-return collections, and application-level admission controls.

## Deployment checklist

- Use JVM 17+ and `LuaConfig.production()`.
- Set a workload-tested nonzero instruction limit.
- Install only required libraries.
- Keep package, IO, and OS disabled unless explicitly needed.
- Keep debugging disabled in untrusted production execution.
- Review every host function and userdata member as a capability.
- Bound host I/O, time, memory, output, and returned collection sizes outside the VM.
- Use unique chunk names without embedding secrets.
- Close states with try-with-resources or an equivalent lifecycle.
- Test limit exhaustion, host exceptions, finalizer warnings, and `LuaExitException` handling.
