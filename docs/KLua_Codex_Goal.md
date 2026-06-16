# KLua Codex Goal

## Purpose

This document is the main execution brief for implementing KLua with Codex. It converts the architecture and milestone references into practical implementation rules that should guide every change.

Reference documents:

- `docs/KLua_Architecture.md`
- `docs/KLua_Implementation_Milestones.md`

Reference implementation:

- Official Lua 5.5 source code is available locally at `~/Downloads/lua-lua-a5522f0`.
- Treat the local source tree as the primary reference for actual Lua 5.5 logic behavior. The manual, local `lua5.5` probes, and existing KLua tests are useful evidence, but they do not replace reading the relevant C implementation.

Do not duplicate those documents here. Use them for deeper rationale and feature detail when a milestone needs more context.

## Codex Goal Prompt

Use this prompt to bootstrap Codex work sessions for this repository:

```text
/goal Follow docs/KLua_Codex_Goal.md to continue implementing KLua as a pure Kotlin Lua 5.5 runtime for JVM 17+. Use the current repository state as authoritative. For behavior-sensitive parser, VM, coroutine, debug, and standard-library work, inspect the official Lua 5.5 source code at ~/Downloads/lua-lua-a5522f0 before deciding semantics, and use that source as the primary reference for actual logic behavior. Work in small verifiable milestone-aligned steps, run the relevant verification for each step, commit each completed verified step with a Conventional Commit message, and keep moving through the remaining gaps without redefining the overall goal as complete.
```

Update this prompt only when the execution rules in this document materially change.

Behavior-sensitive language, VM, coroutine, debug, and standard-library work must refer to the official Lua 5.5 source tree before deciding semantics. The local source checkout is:

```text
~/Downloads/lua-lua-a5522f0
```

Treat that source tree as part of the execution brief. Inspect the relevant C source file, owning function, and helper chain before implementing or changing observable behavior, and prefer the source logic over assumptions from existing KLua behavior, old Lua-version memory, or manual examples that omit edge-case control flow.

## Project Goal

KLua is a greenfield pure Kotlin Lua runtime for JVM 17+. It should provide:

- A Lua 5.5 source runtime as the only supported language target.
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

## Language Target Policy

- Lua 5.5 is the only supported source-language target.
- Do not add old-version runtime modes, flags, aliases, shims, or source-version selection APIs.
- Do not support official PUC Lua `.luac` bytecode in v1.
- Use one internal KLua bytecode format.
- Compiled prototypes should not expose a language-version field. Future bytecode packages may carry a fixed Lua 5.5 marker for validation and diagnostics, but this must not become a public version-selection API.
- Treat Lua 5.5 feature gaps as conformance work, not compatibility-profile work.

## Reference Behavior Policy

- For behavior-sensitive implementation work, inspect the official Lua 5.5 source code in `~/Downloads/lua-lua-a5522f0` before deciding semantics.
- Start from the source file that owns the behavior, for example parser logic in `lparser.c`, VM execution in `lvm.c`, table behavior in `ltable.c`, coroutine behavior in `lcorolib.c`, package loading in `loadlib.c`, and standard-library functions in the matching `l*.c` library file.
- Follow the relevant helper functions far enough to understand the real logic. This includes `luaL_*` argument checks, conversion helpers, metamethod dispatch helpers, string/number formatting helpers, table boundary helpers, stack discipline, and error-message construction.
- Treat the source implementation as the primary reference for actual logic: argument coercion, integer conversion, stack effects, metamethod lookup order, continuation/yield rules, error construction, boundary cases, and library-specific special cases.
- When changing behavior copied from or checked against Lua, record the relevant official source file and function in working notes, tests, commit messages, or concise implementation comments when that context would make the change easier to audit.
- Use the local `lua5.5` executable for observable behavior checks, but use the Lua 5.5 source code to understand the actual control flow, coercion rules, error paths, and edge-case logic behind that behavior.
- The manual and local `lua5.5` checks explain observable behavior; the official source explains the logic that KLua should mirror in Kotlin.
- Do not infer final behavior from existing KLua tests, older Lua manuals, internet examples, or memory when the local official Lua 5.5 source can be inspected.
- When the manual, local tests, or existing KLua behavior are ambiguous or incomplete, treat the Lua 5.5 source code as the implementation reference and document any intentional KLua deviation as a conformance gap.
- Prefer focused tests derived from the reference source path being implemented. When helpful, note the relevant Lua source file or function in the test name, commit message, or implementation comment.
- Existing KLua tests are not authoritative when they conflict with official Lua 5.5 source behavior; update those tests as part of the conformance fix.

## Public API Concepts

These concepts should exist as the initial public API direction. Exact method signatures may evolve before v1.

- `LuaState`: low-level stack API for Java and Kotlin embedders.
- `Lua`: high-level embedding facade.
- `LuaChunk`: loaded source or bytecode chunk.
- `LuaConfig`: runtime configuration.
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
21. M20 Lua 5.5 conformance hardening.
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
- AST model, compiler, internal bytecode, prototype model with a consolidated debug-info view and serialization-friendly snapshot hook covering source IDs, valid breakpoint line metadata, local variable debug ranges, upvalue name metadata, function definition line ranges, Lua-style const local and for-control assignment rejection, conservative block-scoped, close-aware `goto`/label compilation including end-of-block labels and exported pending gotos, and close-aware `break` exits, plus constant pool, disassembler, initial internal constant-pool, instruction-stream, recursive prototype serialization, prototype bytecode package encoding/decoding with fixed KLua/Lua 5.5 format markers plus payload size/checksum metadata, and core/API runtime bytecode package compile/load helpers.
- Interpreter VM with core values, stack/frame execution, expressions, locals, branches, loops, functions, calls, returns, varargs, tables, closures, upvalues, metatables, metamethods, globals, native functions, basic userdata bindings, initial instruction-limit enforcement for chunk execution, exported Lua function calls, and coroutine resumes, and internal thread/yield/resume/dead-state plumbing.
- Java-friendly `LuaState` API, high-level `Lua` facade, Kotlin convenience helpers, single-target runtime configuration, bytecode package resource loading, configurable instruction limits, standard-library whitelisting, and JMH module baseline.
- Runtime errors preserve structured source-name, line, Lua call-frame metadata including function definition, arity, upvalue, and active-line data, registered global and userdata host/native call-frame metadata, readable traceback strings, and API-visible explicit error objects from VM bytecode positions through core execution results, API runtime exceptions, direct native protected calls, and API coroutine runtime results, and host exceptions can survive as runtime error causes.
- Partial `klua-stdlib` support with base, math, string, table, utf8, package, coroutine, initial os, and minimal debug library installers covered by focused Lua-source tests, with config-level standard-library whitelisting and debug-library opt-out for production-style configs, including Lua 5.5-style math random xoshiro state and range projection, Lua warning control handling, Lua-style `require` searcher diagnostic prefixing, raw searcher iteration, original loaded/preload table binding, `package.cpath`, pure-Kotlin `package.loadlib` and C/C-root searcher diagnostics including disabled-library reporting, Lua-style source-prefixing and nil error-object normalization for `assert`/`error` through protected calls and coroutine resume/close errors, table-backed `load`/`loadfile` environment arguments, Lua-style `load` reader failure results, Lua-style `loadfile` mode validation ordering, stateless `utf8.codes` iterator controls, Lua-backed coroutine yield/resume, protected `pcall`/`xpcall` yield continuation, `xpcall` message-handler result/error behavior, wrap/close/isyieldable behavior, main/normal coroutine status and close reporting, coroutine thread type/string reporting, host/native yield-boundary checks, Lua-style `tostring` userdata identity and `__name` handling, Lua-frame-backed `debug.traceback`, suspended-thread and empty-stack coroutine arguments for `debug.traceback`/`debug.getinfo`/`debug.getlocal`/`debug.setlocal`/`debug.sethook`/`debug.gethook`, level-based and function-value `debug.getinfo` source/currentline/function-definition metadata and common call-site name metadata for Lua and host/native functions, `debug.getinfo` option filtering/default metadata including arity/upvalue/active-line/transfer/tail-call fields plus call/return hook transfer metadata, level-based `debug.getlocal` local name/value inspection with out-of-range level errors, function-value `debug.getlocal` local-name inspection, `debug.setlocal` local mutation with out-of-range level errors, `debug.getupvalue` closure upvalue inspection, `debug.setupvalue` closure upvalue mutation, `debug.upvalueid`/`debug.upvaluejoin` closure upvalue identity and sharing, host-userdata `debug.getuservalue`/`debug.setuservalue` slots, table and supported non-table `debug.getmetatable`/`debug.setmetatable` including host-userdata metatables, `table.concat`/`table.insert`/`table.move`/`table.remove`/`table.sort` support for table-like non-table values with required metamethods, source-like `table.unpack` length/index behavior for table-like non-table values, userdata index/newindex/call/length/comparison/arithmetic/bitwise/concat/unary metamethods, userdata `__name` index/call/length/operator errors, `os.clock`/`os.date` string and table formats/`os.difftime`/`os.exit` embedder-safe exit signaling/`os.getenv`/`os.remove`/`os.rename`/`os.setlocale`/`os.time` date-table conversion/`os.tmpname`, `debug.getregistry`, non-interactive `debug.debug`, Lua-style `debug.sethook`/`debug.gethook` call/return/line/count hook support including targeted coroutine hooks, and Lua-style argument validation for these debug/package/os entry points plus selected base/table iterator edge cases.
- Initial `klua-debug` source-line breakpoint manager for setting, replacing, source-wide replacement, clearing, listing, and enabled-hit lookup by source ID and line, public Lua stack-frame, local-variable, locals-scope, and paged table-variable debugger views with stable value summaries, opt-in userdata display adapters, and a small debug controller for pause, breakpoint, conditional-breakpoint, and step stop decisions.
- Initial `klua-dap` typed session surface for initialize requests, launch/attach/disconnect lifecycle modes, advertised debugger capabilities, `setBreakpoints` source breakpoint replacement, `configurationDone`, pause/continue/step controls backed by the debug controller, thread listing, stackTrace/scopes/variables adapters over debug frame views, an expression-evaluation hook, transport-independent typed command routing, `Content-Length` framed JSON message transport primitives, a dependency-free JSON value parser/stringifier for DAP wire messages, generic DAP request/response/event protocol envelopes, a wire-session bridge from JSON request envelopes to typed DAP session responses, initialized-event emission, and chunked framed-request handling that emits framed response/event bytes.
- Initial `klua-tools` CLI debugger runner core with `klua --debug <script.lua> [args...]` invocation parsing, command-loop entry wrapper, command parsing for `break`, `run`, `continue`, `next`, `step`, `out`, `bt`, `locals`, `print`, and `quit`, source breakpoint registration, public-API script execution, `.kluac` bytecode package execution from the runner, top-level expression evaluation, `klua --compile <script.lua> <output.kluac>` bytecode package writing, debug tooling documentation, and an example VS Code launch configuration.
- String pattern support covers literals, dot wildcard, anchors, Lua character classes, bracket classes/ranges, bracketed percent classes, optional single-item matches, greedy/minimal single-item repetitions, basic captures for `find`, `match`, `gsub`, and `gmatch`, backreferences, balanced matches, and frontier matches.
- `string.gsub` supports string, function, and table replacements with Lua-style capture arguments and nil/false preservation.
- `string.packsize`, `string.pack`, and `string.unpack` cover Lua 5.5 format parsing, endian directives, alignment, fixed and variable strings, integer formats, and floating-point formats.
- Table-library conformance includes table-like primitive receivers for `table.concat`, `table.unpack`, `table.insert`, `table.remove`, `table.move`, and `table.sort` when Lua 5.5 `checktab`/metamethod requirements are met.
- Focused parser, compiler, VM, API, Kotlin helper, conformance, and foundation tests.

Remaining major gaps:

- Broader Lua language and conformance hardening.
- Known Lua 5.5 language gaps include full cross-scope `goto`/label semantics and to-be-closed local semantics; `<const>` locals are parsed and enforced, while `<close>` locals are currently rejected before execution.
- Broader standard library implementation, including table edge cases, string pattern/format, math edge cases, and utf8 coverage.
- Known debug-library gaps include broader cross-thread debug behavior beyond suspended and empty-stack KLua coroutine snapshots.
- Broader coroutine runtime hardening, including additional nested coroutine edge cases and Lua 5.5 conformance coverage beyond the current Lua-backed and protected-call yield/resume paths.
- Error handling, tracebacks, and debug metadata.
- Debug hooks and source-level debugger.
- DAP adapter and command-line/debug tooling.
- Broader script packaging workflow, including optional Gradle/plugin integration.
- Broader sandbox and game-server controls beyond the initial chunk/function/coroutine instruction budget and standard-library whitelist.
- Benchmark-driven performance pass.
- Lua 5.5 conformance hardening.

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

Continue from the current milestone frontier rather than restarting the initial proof point. The active frontier spans remaining M13 coroutine hardening and the first M14 error/debug metadata work, while M11/M12 and earlier milestones still need conformance hardening as gaps are discovered.

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
- Lua 5.5 conformance tests for language and standard-library behavior.
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
- Behavior is implemented as Lua 5.5 semantics; deviations and incomplete Lua 5.5 features are documented as conformance gaps.
- Behavior-sensitive changes have been checked against the relevant official Lua 5.5 source file under `~/Downloads/lua-lua-a5522f0`.

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
- "No backward capability" means no legacy project API preservation and no old Lua-version compatibility profile preservation.
- Lua 5.5 is the only runtime target.
- Interpreter-first architecture remains the required path before any JVM bytecode compiler.
- Clean module structure is more important than minimizing module count.
- Performance work starts after correctness and benchmark baselines exist.
