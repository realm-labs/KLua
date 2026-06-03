# KLua Codex Goal

## Purpose

This document is the main execution brief for implementing KLua with Codex. It converts the architecture and milestone references into practical implementation rules that should guide every change.

Reference documents:

- `docs/KLua_Architecture.md`
- `docs/KLua_Implementation_Milestones.md`

Do not duplicate those documents here. Use them for deeper rationale and feature detail when a milestone needs more context.

## Project Goal

KLua is a greenfield pure Kotlin Lua runtime for JVM 17+. It should provide:

- A Lua-compatible source runtime with Lua 5.4 as the first and default target.
- A C-Lua-like low-level `LuaState` stack API.
- An `mlua`-style high-level embedding API for Java and Kotlin users.
- A clean internal bytecode compiler and interpreter before any JVM bytecode generation work.
- Source-level debugging support as a runtime feature, not as a wrapper around the JVM debugger.

Implementation starts clean. There is no requirement to preserve old project APIs, temporary aliases, transitional shims, or backward behavior before v1.

## Non-Negotiable Architecture Rules

- Keep internal runtime implementation in `klua-core`.
- Keep stable Java-friendly public APIs in `klua-api`.
- Keep Kotlin convenience APIs in `klua-kotlin`.
- Keep standard libraries in `klua-stdlib`.
- Keep version profiles and compatibility behavior in `klua-compat`.
- Keep debugging internals in `klua-debug`.
- Keep Debug Adapter Protocol integration in `klua-dap`.
- Keep command-line tools in `klua-tools`.
- Keep benchmarks in `klua-jmh`.
- Keep language, integration, and conformance tests in `klua-tests`.
- Do not expose VM, compiler, bytecode, stack, frame, parser, or AST internals from public API modules.
- Do not add compatibility aliases for old project APIs.
- Do not add JVM bytecode generation before the interpreter, bytecode format, APIs, and benchmarks are stable.
- Do not optimize without a benchmark baseline.

## Delivery Rules

- Keep each implementation step small enough to review and verify independently.
- Prefer one coherent behavior change per step.
- Every step should have an obvious verification command, test, golden output, or documentation check.
- Do not batch unrelated milestone work into one change.
- Use Conventional Commit messages for all commits.
- Commit messages should use a clear type and scope, such as `feat(core): add token model`, `test(parser): cover numeric literals`, or `docs(goal): clarify delivery rules`.
- Do not mark work complete until its verification has been run or the reason it could not be run is documented.

## File Structure Rules

- Production Kotlin files target `<=1000` lines of code.
- Files approaching `1000` lines should be split by responsibility before adding unrelated behavior.
- Test files may exceed production files when scenarios are cohesive.
- Large test fixtures, golden outputs, generated files, and bytecode snapshots must be separated into clearly named fixture, golden, generated, or snapshot paths.
- A file should have one main responsibility. Split by lexer/parser/compiler/VM/API/debug/stdlib behavior instead of creating broad utility files.
- Prefer small package-private or internal helpers over shared global helpers when behavior belongs to one subsystem.

## Compatibility Policy

- Lua 5.4 is the first implementation target and the default runtime profile.
- The long-term roadmap includes Lua 5.1, Lua 5.2, Lua 5.3, Lua 5.4, Lua 5.5, and LuaJIT-like profiles.
- One `LuaState` owns exactly one `LuaVersionProfile`.
- Do not mix chunks compiled for different source-language versions inside one `LuaState`.
- Do not support official PUC Lua `.luac` bytecode in v1.
- Use one internal KLua bytecode format across profiles.
- Store the source Lua version on compiled prototypes and bytecode packages.
- Add version behavior through profiles and tests, not scattered ad hoc conditionals.

## Public API Concepts

These concepts should exist as the initial public API direction. Exact method signatures may evolve before v1.

- `LuaState`: low-level stack API for Java and Kotlin embedders.
- `Lua`: high-level embedding facade.
- `LuaChunk`: loaded source or bytecode chunk.
- `LuaConfig`: runtime configuration.
- `LuaVersion`: selected Lua source version.
- `LuaVersionProfile`: lexer, parser, compiler, runtime, stdlib, and API behavior profile.
- `LuaStatus`: non-throwing low-level call result.
- `LuaException`: base structured exception type for high-level APIs.
- `LuaReturn`: return values from host functions.
- `LuaCallContext`: arguments and runtime context for host function calls.

Public APIs may change freely before v1. After v1, compatibility becomes a release concern and breaking changes require explicit migration notes.

## Implementation Order

Follow this order unless a later task is strictly necessary to unblock an earlier one:

1. M0 project foundation.
2. M1 lexer and parser.
3. M2 AST and compiler skeleton.
4. M3 minimal bytecode VM.
5. M4 expressions, locals, branches, and loops.
6. M5 functions, calls, returns, and varargs.
7. M6 tables.
8. M7 closures and upvalues.
9. M8 metatables and metamethods.
10. M9 low-level `LuaState` API.
11. M10 high-level embedding API.
12. M11 userdata and JVM interop.
13. M12 standard library.
14. M13 coroutines.
15. M14 error handling, tracebacks, and debug metadata.
16. M15 debug hooks and source-level debugger.
17. M16 DAP adapter and debug tooling.
18. M17 script packaging and bytecode loading.
19. M18 sandbox and game-server limits.
20. M19 benchmark-driven performance pass.
21. M20 multi-version compatibility hardening.
22. M21 v1.0 release.
23. M22 optional JVM bytecode compiler.

The first major proof point is:

```text
Lua source -> lexer/parser -> AST -> compiler -> KLua bytecode -> VM -> observable result
```

## First Implementation Target

The first implementation slice should prove a minimal language pipeline:

- Multi-module Gradle project using Kotlin/JVM 17.
- Lexer and parser for simple chunks.
- AST model for literals, expressions, locals, branches, and returns.
- Prototype and bytecode instruction encoding.
- Bytecode disassembler for tests and debugging.
- Simple boxed `LuaValue` model.
- Minimal VM stack, call frame, and interpreter loop.
- Behavior tests that can compile and run `return 42`.

After this works, extend through locals, arithmetic, branches, loops, functions, tables, closures, and metatables in milestone order.

## Testing Requirements

Every implemented feature should include the relevant tests:

- Parser tests where syntax changes.
- Compiler bytecode or golden tests where lowering changes.
- VM behavior tests where runtime behavior changes.
- Error and traceback tests where source locations matter.
- Debug metadata tests where line, local, upvalue, or breakpoint data changes.
- Java API tests for `klua-api`.
- Kotlin API tests for `klua-kotlin`.
- Profile-specific tests once multi-version support begins.
- JMH benchmarks only after correctness exists for hot paths.

Minimum CI target after M0:

```text
./gradlew test
```

Do not mark a milestone complete if it lacks tests for its core behavior.

## Definition of Done

A feature is done only when:

- It is implemented in the correct module.
- It does not leak internal types into public APIs.
- It has focused tests at the parser, compiler, VM, API, or integration level as appropriate.
- Errors include useful source context when the feature can fail at compile time or runtime.
- The implementation follows the file-size and responsibility rules.
- Any compatibility behavior is tied to a named profile or explicitly documented as Lua 5.4-only.

## Engineering Defaults

- Prefer correctness and clear semantics before speed.
- Prefer explicit runtime structures over clever Kotlin abstractions in VM hot paths.
- Avoid allocation per opcode in the interpreter loop.
- Avoid Kotlin lambdas inside the VM dispatch loop.
- Avoid exceptions for normal control flow.
- Keep debug checks disabled or isolated from fast execution when debugging is off.
- Use Java-friendly public API shapes: factories, explicit result types, Java functional interfaces, and no Kotlin-only public types in `klua-api`.
- Use reflection only at registration time for host bindings; runtime calls should use cached adapters.
- Use VM-managed coroutine state, not JVM threads.

## Assumptions

- The repository starts as documentation-only, so implementation can be structured cleanly from the beginning.
- "No backward capability" means no legacy project API preservation.
- It does not mean removing the planned Lua compatibility profiles.
- Lua 5.4 remains the first real runtime target.
- Clean structure is more important than minimizing module count.
- Performance work starts after correctness and benchmark baselines exist.
