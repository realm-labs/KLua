# KLua Codex Goal

## Purpose

This document is the main execution brief for implementing KLua with Codex. It converts the architecture and milestone references into practical implementation rules that should guide every change.

Reference documents:

- `docs/KLua_Architecture.md`
- `docs/KLua_Implementation_Milestones.md`

Do not duplicate those documents here. Use them for deeper rationale and feature detail when a milestone needs more context.

## Codex Goal Prompt

Use this prompt to bootstrap Codex work sessions for this repository:

```text
/goal Follow docs/KLua_Codex_Goal.md to continue implementing KLua as a pure Kotlin Lua runtime for JVM 17+. Use the current repository state as authoritative, work in small verifiable milestone-aligned steps, run the relevant verification for each step, commit each completed verified step with a Conventional Commit message, and keep moving through the remaining gaps without redefining the overall goal as complete.
```

Update this prompt only when the execution rules in this document materially change.

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
- All production and test packages must start with `io.github.realmlabs.klua`.
- Do not expose VM, compiler, bytecode, stack, frame, parser, or AST internals from public API modules.
- Do not add compatibility aliases for old project APIs.
- Do not add JVM bytecode generation before the interpreter, bytecode format, APIs, and benchmarks are stable.
- Do not optimize without a benchmark baseline.

## Delivery Rules

- Keep each implementation step small enough to review and verify independently.
- Prefer one coherent behavior change per step.
- Every step should have an obvious verification command, test, golden output, or documentation check.
- Do not batch unrelated milestone work into one change.
- Commit each coherent verified implementation step before moving to unrelated milestone work.
- Run the relevant verification before committing. If verification cannot be run, document why and get explicit user approval before committing.
- Use Conventional Commit messages for all commits.
- Commit messages should use a clear type and scope, such as `feat(core): add token model`, `test(parser): cover numeric literals`, or `docs(goal): clarify delivery rules`.
- Keep each commit scoped to one coherent behavior, API, test, or documentation change.
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

## Current Status And Remaining Gaps

This section is a rolling implementation snapshot, not a change log. Update it when milestone reality materially changes; do not record per-commit history here.

Current implemented areas:

- Multi-module Gradle project with Kotlin/JVM 17 modules and tests.
- Lexer and parser for current supported Lua syntax.
- AST model, compiler, internal bytecode, prototype model, constant pool, and disassembler.
- Interpreter VM with core values, stack/frame execution, expressions, locals, branches, loops, functions, calls, returns, varargs, tables, closures, upvalues, metatables, metamethods, globals, native functions, basic userdata bindings, and internal thread/yield/resume plumbing.
- Java-friendly `LuaState` API, high-level `Lua` facade, Kotlin convenience helpers, version/profile scaffolding, and JMH module baseline.
- Partial `klua-stdlib` support with base, math, string, table, utf8, package, and coroutine library installers covered by focused Lua-source tests, including Lua-backed coroutine yield/resume, protected `pcall`/`xpcall` yield continuation, wrap/close/isyieldable behavior, and host/native yield-boundary checks.
- String pattern support covers literals, dot wildcard, anchors, Lua character classes, bracket classes/ranges, bracketed percent classes, optional single-item matches, greedy/minimal single-item repetitions, basic captures for `find`, `match`, `gsub`, and `gmatch`, backreferences, balanced matches, and frontier matches.
- `string.gsub` supports string, function, and table replacements with Lua-style capture arguments and nil/false preservation.
- Focused parser, compiler, VM, API, Kotlin helper, compatibility, and foundation tests.

Remaining major gaps:

- Broader Lua language and conformance hardening.
- Broader standard library implementation, including table edge cases, string pattern/format, math edge cases, and utf8 coverage.
- Broader coroutine runtime hardening, including additional nested coroutine edge cases and Lua 5.4 conformance coverage beyond the current Lua-backed and protected-call yield/resume paths.
- Error handling, tracebacks, and debug metadata.
- Debug hooks and source-level debugger.
- DAP adapter and command-line/debug tooling.
- Script packaging and KLua bytecode loading.
- Sandbox and game-server execution limits.
- Benchmark-driven performance pass.
- Multi-version compatibility hardening.

## Completed Initial Proof Point

The initial implementation slice has already proven a minimal language pipeline:

- Multi-module Gradle project using Kotlin/JVM 17.
- Lexer and parser for simple chunks.
- AST model for literals, expressions, locals, branches, and returns.
- Prototype and bytecode instruction encoding.
- Bytecode disassembler for tests and debugging.
- Simple boxed `LuaValue` model.
- Minimal VM stack, call frame, and interpreter loop.
- Behavior tests that can compile and run `return 42`.

This proof point should remain covered by tests while later milestones evolve the runtime.

## Next Implementation Focus

Continue from the current milestone frontier rather than restarting the initial proof point. The active frontier is around M13 coroutine runtime work, especially VM-managed yield/resume continuation semantics, while M11/M12 and earlier milestones still need conformance hardening as gaps are discovered.

Follow milestone order unless a later task is strictly necessary to unblock an earlier one. Keep new work tied to named milestone behavior, focused tests, and a verification command.

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

## Maintaining This Document

- Keep this document aligned with the current committed repository state.
- Update the status and gap snapshot when milestone reality materially changes.
- Do not record per-commit history, dates, release notes, or detailed change logs here.
- Keep detailed roadmap content in `docs/KLua_Implementation_Milestones.md`.
- Keep user-facing project status in `README.md`.

## Assumptions

- The repository already contains working compiler, VM, API, Kotlin helper, userdata, and test slices.
- This document is the operational execution brief and must stay aligned with the current repo state.
- "No backward capability" means no legacy project API preservation.
- It does not mean removing the planned Lua compatibility profiles.
- Lua 5.4 remains the first real runtime target.
- Interpreter-first architecture remains the required path before any JVM bytecode compiler.
- Clean module structure is more important than minimizing module count.
- Performance work starts after correctness and benchmark baselines exist.
