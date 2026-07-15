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
/goal Follow docs/KLua_Codex_Goal.md to continue implementing KLua as a pure Kotlin Lua 5.5 runtime for JVM 17+. Use the current repository state as authoritative. For behavior-sensitive parser, VM, coroutine, debug, and standard-library work, inspect the official Lua 5.5 source code at ~/Downloads/lua-lua-a5522f0 before deciding semantics, and use that source as the primary reference for actual logic behavior. Work through bounded milestone-aligned work packages with an observable outcome, explicit exit criteria, and relevant verification. Prefer one to three coherent verified commits per work package; group fixes, tests, and gap-documentation updates for the same Lua semantic rule instead of committing every probe or assertion separately. Use Conventional Commit messages, and keep moving through the prioritized execution plan without redefining the overall goal as complete.
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

- Define a work package around one observable capability, Lua semantic rule, or tightly related official-source helper chain. A package may cross core, API, and standard-library entry points when they implement the same rule.
- Before implementation, identify the owning milestone, intended outcome, affected modules, relevant Lua 5.5 source path, verification, and exit criteria.
- Prefer one to three coherent commits per work package. Commit count is not a progress metric.
- Keep each commit independently reviewable, revertible, and green, but do not treat one file, function, probe, assertion, or test method as an automatic commit boundary.
- Put a direct behavior fix and its regression tests in the same commit unless a prerequisite refactor is independently meaningful and verified.
- Group test-only coverage discovered in the same semantic audit into a case matrix or focused test commit. Use a standalone test commit only when the coverage is independently valuable and the implementation is already correct.
- When a behavior change resolves or materially narrows a documented conformance gap, update the gap documentation in the same work package and normally in the final behavior commit.
- Keep unrelated milestone work, independently risky refactors, and public API changes in separate commits.
- Use no hard line-count or file-count target. The semantic and rollback boundary decides commit size.
- Every commit should have an obvious verification command, test, golden output, or documentation check.
- Run the relevant verification before committing. If verification cannot be run, document why and get explicit user approval before committing.
- Use Conventional Commit messages for all commits.
- Commit messages should use a clear type and scope, such as `feat(core): add token model`, `test(parser): cover numeric literals`, or `docs(goal): clarify delivery rules`.
- Do not mark work complete until its verification has been run or the reason it could not be run is documented.

## Execution Planning And Milestone Closure

- Treat `docs/KLua_Implementation_Milestones.md` as the capability roadmap, not as a live task queue or a commit map.
- Keep the live work-package order in `Next Implementation Focus` below. Limit it to a small set of current and immediately following packages.
- Mark relevant milestone success criteria as `Done`, `Partial`, or `Gap` before declaring a milestone closed or making a later milestone the primary frontier.
- Finish or explicitly defer confirmed blockers in the earliest active milestone before expanding later surface area. A later task may still be done when it strictly unblocks the earlier milestone.
- Do not use isolated conformance probes as default filler work. Select related findings into a named, source-backed campaign with a case matrix and an exit condition.
- A conformance gap document is evidence and scope tracking, not a FIFO backlog. Prioritization belongs in the live execution plan.
- Update the live plan when a work package closes, milestone reality changes, or new evidence materially changes priority. Do not update it for every commit.

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
- AST model, compiler, internal bytecode, prototype model with a consolidated debug-info view and serialization-friendly snapshot hook covering source IDs, valid breakpoint line metadata, local variable debug ranges, upvalue name metadata, function definition line ranges, Lua-style const and `<close>` local assignment rejection, for-control assignment rejection, regular and `<const>` named/wildcard `global` declaration scopes, initialized `global` declarations, local-shadowing resolution, lexical `_ENV` lowering, closure-shared default environment assignment, plain function declarations through resolved bindings including const-global rejection, `global function` declarations with Lua-style already-defined checks, conservative block-scoped, close-aware `goto`/label compilation including end-of-block labels and exported pending gotos, close-aware `break` exits, and generic-for fourth-value closing, plus constant pool, disassembler, initial internal constant-pool, instruction-stream, recursive prototype serialization, prototype bytecode package encoding/decoding with fixed KLua/Lua 5.5 format markers plus payload size/checksum metadata, and core/API runtime bytecode package compile/load helpers.
- Interpreter VM with core values, stack/frame execution, expressions, locals, branches, loops, functions, calls, returns, varargs, tables, closures, upvalues, metatables, metamethods, globals, native functions, basic userdata bindings, initial instruction-limit enforcement for chunk execution, exported Lua function calls, and coroutine resumes, plus internal thread/yield/resume/dead-state plumbing, full to-be-closed lifecycle unwinding across normal, control-flow, error, yield/resume, and coroutine-close boundaries, and Lua/native continuation for `__index`/`__newindex` reads and writes and value-producing arithmetic, bitwise, unary, length, comparison, and concatenation metamethods.
- Java-friendly `LuaState` API, high-level `Lua` facade, Kotlin convenience helpers, single-target runtime configuration, bytecode package resource loading, configurable instruction limits, standard-library whitelisting, a bounded `LuaConfig.production()` preset that suppresses debugger and unsafe standard-library host access while preserving explicit host bindings, and initial JMH baselines separating parse/compile/package encoding from fresh-coroutine VM numeric-loop execution.
- Runtime errors preserve structured source-name, line, Lua call-frame metadata including function definition, arity, upvalue, and active-line data, registered global and userdata host/native call-frame metadata, readable traceback strings, and API-visible explicit error objects from VM bytecode positions through core execution results, API runtime exceptions, direct native protected calls, and API coroutine runtime results, and host exceptions can survive as runtime error causes.
- Partial `klua-stdlib` support with base, math, string, table, utf8, package, coroutine, initial os, source-backed io, and minimal debug library installers covered by focused Lua-source tests, with config-level standard-library whitelisting and debug-library opt-out for production-style configs, including Lua 5.5-style math random xoshiro state and range projection, Lua warning control handling, Lua-style `require` searcher diagnostic prefixing, raw searcher iteration, original loaded/preload table binding, `package.cpath`, pure-Kotlin `package.loadlib` and C/C-root searcher diagnostics including disabled-library reporting, Lua-style source-prefixing and nil error-object normalization for `assert`/`error` through protected calls and coroutine resume/close errors, table-backed `load`/`loadfile` environment arguments, Lua-style `load` reader failure results, Lua-style `loadfile` mode validation ordering, stateless `utf8.codes` iterator controls, Lua-backed coroutine yield/resume, protected `pcall`/`xpcall` yield continuation, `xpcall` message-handler result/error behavior, wrap/close/isyieldable behavior, main/normal coroutine status and close reporting, coroutine thread type/string reporting, host/native yield-boundary checks, Lua-style `tostring` userdata identity and `__name` handling, Lua-frame-backed `debug.traceback`, optional coroutine arguments for `debug.traceback`/`debug.getinfo`/`debug.getlocal`/`debug.setlocal`/`debug.sethook`/`debug.gethook` across current/main, fresh, yielded, normal, dead-success, failed-before-close, and reset-after-close thread states, level-based and function-value `debug.getinfo` source/currentline/function-definition metadata and common call-site name metadata for Lua and host/native functions including Lua 5.5 compile-time `<const>` substitution for callees and field keys, `debug.getinfo` option filtering/default metadata including arity/upvalue/active-line/transfer/tail-call fields plus call/return hook transfer metadata, level-based `debug.getlocal` local name/value inspection with out-of-range level errors, function-value `debug.getlocal` local-name inspection, `debug.setlocal` local mutation with out-of-range level errors, `debug.getupvalue` closure upvalue inspection, `debug.setupvalue` closure upvalue mutation, `debug.upvalueid`/`debug.upvaluejoin` closure upvalue identity and sharing, host-userdata `debug.getuservalue`/`debug.setuservalue` slots, table and supported non-table `debug.getmetatable`/`debug.setmetatable` including host-userdata metatables, `table.concat`/`table.insert`/`table.move`/`table.remove`/`table.sort` support for table-like non-table values with required metamethods, source-like `table.unpack` length/index behavior for table-like non-table values, userdata index/newindex/call/length/comparison/arithmetic/bitwise/concat/unary metamethods, userdata `__name` index/call/length/operator errors, `os.clock`/`os.date` string and table formats/`os.difftime`/`os.exit` embedder-safe exit signaling/`os.getenv`/`os.remove`/`os.rename`/`os.setlocale`/`os.time` date-table conversion/`os.tmpname`, `debug.getregistry`, non-interactive `debug.debug`, Lua-style `debug.sethook`/`debug.gethook` call/return/line/count hook support including targeted coroutine hooks, and Lua-style argument validation for these debug/package/os entry points plus selected base/table iterator edge cases.
- Initial `klua-debug` source-line breakpoint manager for setting, replacing, source-wide replacement, clearing, listing, and enabled-hit lookup by source ID and line, public Lua stack-frame, local-variable, locals/upvalues/globals-scope, and paged table-variable debugger views with stable value summaries, opt-in userdata display adapters, a debug controller for pause, breakpoint, conditional-breakpoint, and step stop decisions, and a transport-independent live session that suspends coroutine-backed VM execution at breakpoint/step line events, exposes stopped source lines plus live locals/upvalues/globals, and evaluates scalar expressions in a fresh read-only snapshot environment.
- `klua-dap` typed and wire sessions for the core DAP lifecycle, breakpoint, execution-control, thread, stack, scope, variable, and evaluation requests, including a `LiveDapSession` that shares breakpoint definitions across independently stepped coroutine threads, resumes real VM suspensions, exposes live frames/scopes/variables/evaluation, and emits stopped/thread/terminated events through the wire bridge.
- `klua-tools` CLI debugger support for `klua --debug <script.lua> [args...]` and its `break`, `run`, `continue`, `next`, `step`, `out`, `bt`, `locals`, `print`, and `quit` commands over a live source or `.kluac` top-level coroutine, including stopped-frame inspection and read-only scalar evaluation; bytecode package compilation remains available through `klua --compile <script.lua> <output.kluac>`.
- String pattern support covers literals, dot wildcard, anchors, Lua character classes, bracket classes/ranges, bracketed percent classes, optional single-item matches, greedy/minimal single-item repetitions, basic captures for `find`, `match`, `gsub`, and `gmatch`, backreferences, balanced matches, and frontier matches.
- `string.gsub` supports string, function, and table replacements with Lua-style capture arguments and nil/false preservation.
- `string.packsize`, `string.pack`, and `string.unpack` cover Lua 5.5 format parsing, endian directives, alignment, fixed and variable strings, integer formats, and floating-point formats.
- Table-library conformance includes table-like primitive receivers for `table.concat`, `table.unpack`, `table.insert`, `table.remove`, `table.move`, and `table.sort` when Lua 5.5 `checktab`/metamethod requirements are met.
- Live debugger runtime registration assigns stable thread IDs to independent coroutine sessions; debug-disabled API configurations reject execution observers, and the normal VM opcode path performs only a null-observer check.
- Focused parser, compiler, VM, API, Kotlin helper, conformance, and foundation tests.

Remaining major gaps:

- Broader Lua language and conformance hardening.
- Broader standard library implementation, including table edge cases, string pattern/format, math edge cases, and utf8 coverage.
- Broader coroutine runtime hardening and Lua 5.5 conformance coverage beyond the current Lua-backed, protected-call, index/newindex read/write-opcode, and arithmetic/bitwise/unary/length/comparison/concatenation metamethod yield/resume paths.
- Broader error, traceback, and debug-library conformance beyond the closed M14 foundation criteria.
- A packaged standalone DAP adapter host and richer debugger scheduling/evaluation policy beyond the closed M16 library-level integration.
- Broader script packaging workflow, including optional Gradle/plugin integration.
- Broader sandbox and game-server controls beyond the initial chunk/function/coroutine instruction budget and standard-library whitelist.
- Benchmark-driven performance pass.
- Lua 5.5 conformance hardening.

### M13-M18 Closure Audit

`Done` means the milestone success criterion is implemented and covered by committed tests. `Partial` means reusable pieces exist but the criterion is not yet proven end to end. `Gap` means the required runtime behavior is not connected yet. Broader conformance hardening that is not required by a criterion remains M20 work.

#### M13 Coroutines

| Success criterion | Status | Evidence |
| --- | --- | --- |
| Yield/resume works across Lua frames. | Done | Core/API coroutine runners and the coroutine standard library cover repeated Lua-frame yield/resume and completion. |
| Coroutine errors are reported correctly. | Done | Runtime, API, and standard-library tests cover source-bearing errors, dead-state resumes, close behavior, and protected-call propagation. |
| Non-yieldable host calls are detected. | Done | Core and standard-library tests cover native boundary tracking and the Lua-style `attempt to yield across a C-call boundary` failure. |

M13 is closed against its success criteria. Additional nested-coroutine and cross-thread conformance cases are M20 hardening, not closure blockers.

#### M14 Error Handling, Tracebacks, And Debug Metadata

| Success criterion | Status | Evidence |
| --- | --- | --- |
| Errors identify source file and line. | Done | Core and API runtime-error tests assert structured source names and bytecode-derived lines. |
| Stack traces include Lua frames. | Done | Core, API, coroutine, and `debug.traceback` tests cover nested Lua frames and readable tracebacks. |
| Stack traces can include useful Java/Kotlin host call frames. | Done | Registered global and userdata host-call tests assert `[Kotlin]` frames and host names. |
| Function prototypes carry enough metadata for breakpoints and later local inspection. | Done | Compiler tests cover source IDs, line maps, valid breakpoint lines, function ranges, locals, and upvalue names. |
| Local variable metadata can be queried in tests. | Done | Compiler and debug-library tests query local names, slots, lifetimes, and active frame values. |
| Debug metadata survives compile/load round trips. | Done | Recursive prototype codec tests round-trip line, local, upvalue, call-site, and breakpoint metadata. |
| Bytecode packages can optionally include or strip debug metadata. | Done | Recursive stripped-prototype and `string.dump(..., true)` tests cover debug removal while retaining executable structure. |
| Java/Kotlin host exceptions are wrapped clearly. | Done | API tests preserve the host cause and expose structured Lua and host frames. |

M14 is closed against its foundation criteria. Broader debug-library and traceback conformance remains M20 work.

#### M15 Debug Hooks And Source-Level Debugger

| Success criterion | Status | Evidence |
| --- | --- | --- |
| A script can stop at a breakpoint. | Done | A VM debug observer produces a distinct suspension before the selected line's opcode and `LiveDebugSession` returns the real stopped context. |
| A script can step into, over, and out. | Done | Live integration tests drive line events and call depth through nested Lua frames for all three step modes. |
| The debugger can show the current source line. | Done | Stopped frame snapshots preserve the suspended PC and report the exact source line. |
| Locals, upvalues, and globals are inspectable. | Done | Live stopped frames populate all three scopes with local/upvalue shadowing information preserved by separate views. |
| Debug expression evaluation works for simple expressions. | Done | Selected-frame evaluation copies scalar global/upvalue/local bindings into a fresh library-free Lua state, preserving shadow order without mutating the stopped program. |
| Coroutines appear as debuggable Lua threads. | Done | `LiveDebugRuntime` assigns stable IDs and independent sessions to registered Lua coroutine functions; integration tests suspend multiple registered threads separately. |
| Debug checks are cheap or disabled in normal fast execution. | Done | Debug-disabled API configurations reject execution observers, and the opcode loop guards observer dispatch with a single null check when no live debugger is attached. |

M15 is closed against its success criteria. Table/function-aware evaluation, mutation-enabled trusted evaluation, and richer multi-thread scheduling policy remain later debugger hardening rather than closure blockers.

#### M16 DAP Adapter And Debug Tooling

| Success criterion | Status | Evidence |
| --- | --- | --- |
| A script can be launched under the CLI debugger. | Done | CLI command-loop and runner tests launch source and packaged-bytecode chunks as coroutine-capable top-level functions and stop live VM execution. |
| Breakpoints and stepping work through the debug controller. | Done | CLI and DAP integration tests set breakpoints, observe exact live stop lines, and resume with step-over through each thread's controller. |
| A DAP client can set breakpoints and step through Lua code. | Done | Wire-level tests drive `setBreakpoints`, start the registered coroutine, issue `next`/`continue`, and receive live stopped and lifecycle events. |
| A DAP client can request stack frames and variables. | Done | The live wire integration test reads the stopped stack, all three scopes, locals, and selected-frame evaluation from the VM context. |
| Coroutines appear as debugger threads. | Done | `LiveDapSession` registers stable runtime thread IDs, and the wire test lists multiple named coroutine threads while controlling one independently. |
| Large tables do not freeze the debugger UI. | Done | Variable expansion is paged and paging behavior is covered by debug/DAP tests. |
| Debugger can be disabled completely through production runtime configuration. | Done | Live CLI and DAP registration tests prove that `debugEnabled = false` rejects debugger observer attachment. |

M16 is closed against its library-level success criteria. Packaging a standalone DAP server process remains optional tooling expansion rather than a runtime-integration blocker.

#### M17 Script Packaging And Bytecode Loading

| Success criterion | Status | Evidence |
| --- | --- | --- |
| Runtime can load compiled bytecode. | Done | Core, API, resource-loading, CLI, and integration tests compile and execute `.kluac` packages. |
| Bytecode version mismatch is detected. | Done | Package/core tests reject unsupported fixed KLua/Lua format markers and checksum/size corruption. |
| Source mode and bytecode mode produce the same results. | Done | Cross-module integration tests compare nontrivial source and packaged-bytecode results. |

M17 is closed against its success criteria. Gradle/plugin integration remains an optional packaging expansion.

#### M18 Sandbox And Game-Server Limits

| Success criterion | Status | Evidence |
| --- | --- | --- |
| Infinite loops can be stopped. | Done | Instruction limits are enforced for chunks, exported functions, and coroutine resumes with source-bearing errors. |
| Scripts cannot access OS/files unless allowed. | Done | `LuaConfig.production()` excludes package/I/O/OS, removes base `dofile`/`loadfile`, and enforces the unsafe-library gate even if those libraries are accidentally listed or opened directly; an explicit compatibility opt-in restores them. |
| Host APIs can be selectively exposed. | Done | Host globals/functions and registered userdata types are exposed explicitly by the embedder; unregistered host members are unavailable. |

M18 is closed against its success criteria. Memory accounting, wall-clock timeout integration, deterministic-random policy, and finer-grained permissions remain optional sandbox hardening rather than closure blockers.

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

The closure audit above closes M13 through M18 against their success criteria. The bounded M19 continuation is complete and M20 is the current frontier. Compile, numeric-loop, Lua-call, closure-counter, host-call, table read/write, function-valued `__index`, JVM-to-Lua call, method-call, recursive Fibonacci, growing string-concatenation, coroutine yield/resume, fixed-arity vararg/multi-return, and table-backed entity-update baselines now exist. Profiling the first selected hotspot showed that eager Lua debug-frame snapshots dominated the host-call bridge; lazy on-access frame materialization reduced profiled allocation by 65.4% and the matched full-suite host-call score by 55.5%. Profiling the second hotspot showed that ordinary table accesses eagerly allocated metamethod cycle-detection sets; matching Lua 5.5's raw-fast-path/finish-operation split reduced table-workload allocation by 59.5% and profiled time by 10.3%. JFR profiling of the JVM-to-Lua control then showed scalar conversions eagerly allocating table-identity caches; lazy graph-cache creation reduced that workload's allocation by 44.3% and profiled time by 16.0%. The same evidence on coroutine transfer showed specialized argument-synchronization caches on every scalar yield/resume; lazy creation reduced allocation by 35.5% and the matched full-suite score by 15.9%. Method-control profiling then showed repeated Lua-byte reconstruction in every string-key hash/equality probe; caching immutable raw bytes and hashes on demand reduced method allocation by 84.9%, matched profiled time by 65.0%, and the full-suite method score by 64.2% without materializing bytes for transient non-key strings. Residual coroutine profiling then selected eager VM native-context service callbacks; consolidating them into one VM-backed context reduced coroutine allocation by 7.4%, matched profiled time by 11.6%, and the post-string-cache full-suite score by 24.5%. A matched host/coroutine comparison next selected API-side service lambdas; passing one core-backed call context directly reduced coroutine allocation by a further 7.0% and host allocation by 10.8%. Timing was too noisy for a supported claim in that package. Matched result profiling then rejected a broad scalar/list rewrite but selected the coroutine-only double copy used to prefix successful resume values; single-pass prefixing reduced coroutine allocation by 3.1% with host and JVM-to-Lua controls unchanged. The first application-kernel follow-up removed an intermediate vararg-range materialization, reducing vararg/multi-return allocation by 10.8%; timing was too noisy for a supported claim. A numeric-loop follow-up rejected a redundant-looking integer-limit fast path after matched GC proved that HotSpot already eliminates its temporary wrapper and JFR showed immutable value boxing is the representation-level residual. Static `NEW_TABLE` entry hints then reduced entity-kernel allocation by 2.0% and removed the selected resize hotspot without affecting evaluation order. Lazy stack capture storage reduced ordinary Lua-call allocation by another 14.0% while preserving shared and closed upvalue identity. Splitting suspension and debug/hook fields out of ordinary call frames then reduced Lua-call allocation by a further 6.1%. Retaining one shared call-site metadata reference instead of two expanded fields reduced it by another 2.2%. Array-backed stack slots then removed another 24 bytes per created Lua frame, shared empty vararg views removed a further 24 bytes from fixed-arity frames, direct Lua-to-Lua argument transfer removed the remaining intermediate argument collection, singleton return snapshots removed another 24 bytes per one-result call, co-locating stack state with its frame removed the separate wrapper object, removing obsolete shared-stack offsets reduced frames by another 16 bytes, deriving prototype/upvalue metadata from each frame's exact closure removed another 8 bytes per frame, direct post-hook Lua-to-Lua result transfer removed the remaining per-call return wrapper and snapshot, reusing fixed metamethod call-site records removed repeated diagnostic name construction, and direct fixed-arity Lua metamethod entry removed the remaining two- and three-argument snapshot wrappers. The proposed single-result metamethod specialization was then rejected when the Lua 5.5 continuation audit exposed missing yield completion; correctness work interrupted the performance sequence and completed read-opcode `__index`, write-opcode `__newindex`, value-producing arithmetic/bitwise/unary/length, comparison, and concatenation continuation slices. Direct core-backed protected calls and a shared live metatable provider then removed the latest API/VM bridge residuals. All retained packages pass the full correctness suite.

### M19 Application-Kernel Coverage Checkpoint

The fixed-arity control rotates three values through a vararg function and three-result assignment 10,000 times, with setup requiring checksum `312`. Its behavior follows Lua 5.5's `OP_VARARG` handling in `lvm.c`, result movement in `ldo.c:moveresults`, and parser emission of `OP_VARARG`/`OP_VARARGPREP` in `lparser.c`. The table-backed control creates 1,000 entity records, runs ten position-update passes, and requires checksum `1,511,500`. Compilation remains outside both measured operations.

Focused GC measurement records `3,886.439 us/op` and `5,201,811.110 B/op` for vararg/multi-return, versus `5,477.484 us/op` and `1,563,492.372 B/op` for the entity kernel. The all-benchmark 14-control run records compile `8.040`, numeric loop `1,108.178`, Lua calls `2,475.068`, closure counter `2,236.065`, host calls `2,762.559`, table read/write `2,469.676`, function-valued `__index` `3,747.251`, JVM-to-Lua calls `3,540.765`, method calls `5,052.486`, recursive Fibonacci `6,350.335`, string concatenation `3,634.071`, coroutine yield/resume `14,935.196`, vararg/multi-return `3,800.991`, and entity update `5,297.183 us/op`. JFR selects the vararg workload for the next package: `CollectionsKt.toMutableList` contributes 48.46% of sampled allocation pressure, followed by array copying at 10.84%, call-frame push at 10.44%, and stack slicing at 10.20%.

### M19 Vararg Copy Checkpoint

KLua previously formed each stored vararg sequence with `arguments.drop(numParams).toMutableList()`, allocating an intermediate range before the required mutable snapshot. Lua 5.5's `ltm.c:luaT_adjustvarargs` and `luaT_getvarargs` require independent extra-argument storage, nil preservation, all-result expansion, and nil padding for fixed-result reads, but do not require that intermediate collection. One exact-capacity range copy preserves those semantics; a focused ownership test proves later caller-list mutation cannot alter a frame's varargs and frame mutation cannot alter the caller list, including an embedded nil.

Matched GC measurement reduces vararg/multi-return allocation from `5,201,811.110` to `4,641,739.572 B/op`, a reduction of `560,071.538 B/op` or 10.8% (about 56 bytes per rotated call). Entity-update allocation remains effectively unchanged at `1,563,477.874 B/op`. Post-change JFR no longer contains `CollectionsKt.toMutableList`; the necessary single-copy `copyVarargs` snapshot accounts for 8.16% of sampled pressure. Focused and full-suite timing remains too noisy to support a speed claim; the final 14-control run completes with vararg/multi-return at `3,983.178 us/op`. The same JFR now selects `LuaVm.advanceForLoop` at 51.80% of sampled allocation pressure for a separate numeric-loop audit.

### M19 Numeric-Loop Rejection Checkpoint

Lua 5.5's `lvm.c:forprep`, `floatforloop`, and `OP_FORLOOP` keep integer loop count, step, and visible control value in inline tagged stack slots; unsigned iteration counts preserve termination across integer overflow. KLua already preserves the corresponding visible-value and overflow behavior, but its immutable `LuaInteger` value model boxes each updated control value and arithmetic result. A proposed fast path avoided constructing `ForIntegerLimit.Run` after `FOR_TEST` had normalized the limit, but matched GC was unchanged: numeric-loop allocation moved only from `961,433.021` to `961,433.523 B/op`, and vararg/multi-return from `4,641,739.277` to `4,641,744.162 B/op`. The change was removed.

Allocation-by-class JFR attributes 98.49% of numeric-loop pressure to `LuaInteger`, with 42.56% sampled at loop advancement, 39.04% in body arithmetic, and 16.89% in frame/return execution. This makes further improvement a value/stack representation package rather than a safe local loop change, so it is deferred. Profiling the entity-update control instead selects a bounded next target: `HashMap.resize` accounts for 67.22% of sampled allocation pressure while each four-field record grows its initially empty hash storage.

### M19 Table-Literal Capacity Checkpoint

Lua 5.5's `lvm.c:OP_NEWTABLE` decodes separate array and hash size operands and calls `luaH_resize` before constructor field writes. KLua uses one insertion-ordered map for both key classes, so its compiler now records one conservative total-entry hint in the unused `NEW_TABLE` B operand, capped at 255, and the VM converts that hint to a load-factor-safe `LinkedHashMap` capacity. Empty and dynamically grown tables retain their prior zero-hint path. Compiler disassembly/cap tests and a mixed keyed, named, and list-field VM test cover the encoding and evaluation order.

Matched GC reduces the 1,000-record entity kernel from `1,563,477.874` to `1,531,429.602 B/op`, a reduction of `32,048.272 B/op` or 2.0% (about 32 bytes per record). The focused entity score is effectively flat at `5,439.685 us/op`, and a machine-wide shift during the final 14-control run prevents a supported suite timing claim. Post-change JFR reduces sampled `HashMap.resize` pressure from 67.22% to 0.90%; map entries and boxed arithmetic are now the representation-level entity residual. A fresh Lua-call profile selects the next local cost: eagerly empty `LuaStack.captures` maps account for 56.45% of sampled allocation pressure.

### M19 Lazy Stack-Capture Checkpoint

Lua 5.5's `lfunc.c:luaF_findupval` searches the open-upvalue list and creates storage only when a closure first captures a stack slot; repeated capture of that slot reuses the same `UpVal`, and `luaF_closeupval` unlinks it while preserving its value. KLua now follows the same lifetime more closely: `LuaStack` creates its slot-to-upvalue map on first capture, reuses entries, drops the map after its last open capture closes, and otherwise performs only a nullable check. A focused stack test proves open identity, value synchronization, close detachment, and independent reopening; the existing closure/upvalue suite remains green.

Matched GC reduces the 10,000-call Lua control from `4,561,530.081` to `3,921,465.806 B/op`, a reduction of `640,064.275 B/op` or 14.0% (about 64 bytes per call). The capture-bearing closure control completes at `3,602,193.695 B/op`, and the final 14-control run records Lua calls at `2,432.232 us/op` and closure calls at `2,354.365 us/op`; focused timing is directionally lower but too noisy for a supported claim. Post-change JFR removes the empty `LinkedHashMap` and dominant `LuaStack` construction sites. `CallFrame` now accounts for 72.05% of sampled Lua-call allocation pressure and is the next bounded audit target.

### M19 Call-Frame Cold-State Checkpoint

Lua 5.5's `lstate.h:CallInfo` uses unions and status bits so Lua-only, C-continuation, yield, and close state share storage rather than expanding every active call. KLua's ordinary frame previously carried two pending-result integers, a continuation reference, and six hook/debugger integers even when execution could neither suspend nor be observed. Pending-call data is now one atomically installed lazy state used only across yield or debugger suspension, while hook-transfer and debugger-PC data share a second lazy state. Core suspension tests plus the complete coroutine, debug-hook, live-debugger, and DAP suites cover the cold paths.

Matched GC reduces Lua-call allocation from `3,921,465.806` to `3,681,441.811 B/op`, a reduction of `240,023.995 B/op` or 6.1% (about 24 bytes per call). The closure control falls from `3,602,193.695` to `3,362,145.779 B/op`, the same per-call effect. Coroutine yield/resume remains green with lazy pending state and measures `22,878,495.070 B/op`. Timing remains too variable for a supported claim; the final 14-control run completes with Lua calls at `3,396.977 us/op`. Post-change JFR still selects `CallFrame` at 57.12% of sampled runtime allocation, but pooling is excluded because frames can remain suspended and debugger-visible. The next audit instead targets the two call-site metadata references already represented by one shared immutable `CallSiteInfo` in the prototype.

### M19 Call-Site Metadata Checkpoint

Lua 5.5's `ldebug.c:funcnamefromcode`, `funcnamefromcall`, and `getfuncname` derive a callee's contextual name from the caller prototype and calling program counter instead of expanding separate name strings into every `CallInfo`. KLua's compiler already records one immutable `CallSiteInfo` per relevant prototype instruction. Each frame now retains that shared object as one reference, and computed accessors preserve the existing traceback, runtime-error, and debug-name behavior.

Matched GC reduces Lua-call allocation from `3,681,441.811` to `3,601,433.679 B/op`, a reduction of `80,008.132 B/op` or 2.2% (about 8 bytes per call). Recursive Fibonacci falls from `9,195,942.593` to `9,020,804.720 B/op`, a reduction of `175,137.873 B/op` across its 21,891 calls, again about 8 bytes per call. Timing is too noisy for a supported claim; the final 14-control run completes with Lua calls at `2,423.077 us/op` and recursive Fibonacci at `6,033.997 us/op`. The complete correctness suite, including bytecode round trips and call-site name/traceback assertions, remains green. Post-change runtime JFR distributes the residual across call-frame push (14.01%), integer arithmetic (13.76%), loop advancement (12.99%), and stack initialization (10.83%), so the next bounded package targets stack storage rather than another metadata field.

### M19 Array-Backed Stack Checkpoint

Lua 5.5 represents thread stack slots as one contiguous `StackValue` vector. `ldo.c:luaD_growstack` grows that vector geometrically while respecting the requested minimum, and `luaD_reallocstack` nil-initializes its new segment and repairs stack references through offsets. KLua now stores each frame's slots directly in a `LuaValue` array, grows it by 1.5x or to the requested index, and fills new positions with `LuaNil`. KLua's open captures already synchronize through stable slot indices and independent `LuaUpvalue` cells, so array relocation requires no externally visible repair. Focused tests cover skipped-slot nil initialization, copying and slicing after growth, and open-capture synchronization across growth.

In an immediate array/list control comparison, matched GC reduces 10,000 ordinary Lua calls from `3,601,437.479` to `3,361,409.783 B/op`, a reduction of `240,027.696 B/op` or 6.7% (about 24 bytes per created stack). Recursive Fibonacci falls from `9,020,807.412` to `8,495,397.761 B/op`, a reduction of `525,409.651 B/op` or 5.8% across 21,891 calls, again about 24 bytes per stack. The closure control falls from `3,282,131.589` to `3,042,049.840 B/op`, while coroutine yield/resume is effectively unchanged at `22,878,435.052 B/op`. Timing is too machine-noisy for a supported claim. The complete correctness suite and final 14-control run pass. Post-change JFR removes the collection wrapper from stack construction and selects the remaining eager empty `ArrayList` created for fixed-arity frame varargs as the next bounded audit.

### M19 Fixed-Arity Empty-Vararg Checkpoint

Lua 5.5 runs `ltm.c:luaT_adjustvarargs` only for prototypes marked vararg; `luaT_getvarargs` then reads those hidden extra arguments. Fixed-arity `CallInfo` records do not own a separate mutable empty collection. KLua now mirrors that split with nullable mutable backing on the frame: fixed-arity calls expose the shared read-only empty list, while real vararg calls retain their exact owned mutable snapshot. Debugger vararg writes go through a bounds-checked frame method, preserving `debug.setlocal` behavior without allowing writes into fixed frames. Focused tests prove shared fixed empties, rejected fixed writes, and mutable vararg ownership independent of the caller list.

The immediate eager/shared control comparison reduces 10,000 fixed-arity Lua calls from `3,361,410.401` to `3,121,410.785 B/op`, a reduction of `239,999.616 B/op` or 7.1% (about 24 bytes per call). The actual vararg/multi-return control remains effectively unchanged at `3,441,618.883 B/op` versus `3,441,620.700 B/op` in the eager control. Timing is too noisy for a supported claim. The complete correctness suite and final 14-control run pass, with Lua calls at `3,145.241 us/op` and vararg/multi-return at `4,699.574 us/op`. Post-change JFR selects `LuaStack.slice` at 55.13% of sampled Lua-call allocation pressure, primarily from materializing Lua-to-Lua argument lists.

### M19 Lua-to-Lua Argument Transfer Checkpoint

Lua 5.5's `ldo.c:luaD_precall` receives a Lua closure and its arguments in adjacent stack slots, prepares the callee `CallInfo`, and pads missing fixed parameters directly; native functions retain their separate C-call path. KLua now specializes the same boundary: a Lua-closure call copies the caller's evaluated stack range directly into the new frame, including an exact owned copy of any extra varargs. Native calls and `__call` chains keep their existing snapshot-list API. Focused range tests plus the function-call suite cover fixed parameters, nil fill, vararg ownership, open-result expansion, evaluation order, call-site metadata, and suspension behavior.

An immediate feature-toggle comparison reduces 10,000 one-argument Lua calls from `3,121,409.907` to `2,641,409.476 B/op`, a reduction of `480,000.431 B/op` or 15.4% (about 48 bytes per call). The three-argument vararg/multi-return control falls from `3,441,618.529` to `2,881,619.867 B/op`, a reduction of `559,998.662 B/op` or 16.3% (about 56 bytes per call). Host-call allocation is unchanged at `7,278,266.447 B/op` versus `7,278,266.023 B/op` in the list-materializing control. Timing remains too noisy for a supported claim. The complete correctness suite and final 14-control run pass, with Lua calls at `2,333.785 us/op`, vararg/multi-return at `3,227.189 us/op`, and host calls at `2,747.453 us/op`. Post-change JFR reduces `LuaStack.slice` from 55.13% to 0.58% of sampled Lua-call pressure and selects the remaining small snapshot collection used for return and native-call ranges.

### M19 Small Return-Snapshot Checkpoint

Lua 5.5's `ldo.c:moveresults` handles zero and one wanted results separately before falling back to general result movement. KLua likewise now uses shared empty and singleton immutable snapshots for zero- and one-value returns, while retaining exact copied lists for multiple results. This representation change left the then-existing pre-hook snapshot order unchanged; the later direct-transfer checkpoint corrects that order to Lua 5.5's hook-before-move behavior. A proposed global `LuaStack.slice` specialization was rejected because matched GC showed a 24-byte regression on every native call; native argument snapshots therefore retain their prior representation. Focused tests prove empty reuse and single/multiple snapshot independence after stack mutation.

Matched GC reduces 10,000 one-result Lua calls from `2,641,409.476` to `2,401,380.281 B/op`, a reduction of `240,029.195 B/op` or 9.1% (about 24 bytes per call plus the outer result). Host calls remain effectively unchanged at `7,278,234.487 B/op`, only about 32 bytes below the `7,278,266.447 B/op` control due to the outer chunk return; the three-result vararg/multi-return kernel is likewise unchanged at `2,881,586.316 B/op`. Timing remains too noisy for a supported claim. The complete correctness suite and final 14-control run pass, with Lua calls at `1,811.663 us/op`, host calls at `2,534.190 us/op`, and vararg/multi-return at `2,987.333 us/op`. Post-change JFR attributes only 2.73% of sampled pressure to singleton return lists and selects the separate per-call `LuaStack` wrapper alongside `CallFrame` as the next structural audit.

### M19 Call-Frame Stack Co-Location Checkpoint

Lua 5.5 keeps one contiguous value stack in `lua_State`; `CallInfo` points into that storage, and `ldo.c:luaD_reallocstack` repairs stack and upvalue locations after growth rather than allocating a separate stack wrapper for every call. KLua retains independent arrays per frame, but `CallFrame` now inherits the existing `LuaStack` storage and exposes its legacy `stack` property as a computed self-reference. This removes the wrapper object and stored wrapper reference while preserving the tested stack implementation, lazy capture map, dynamic growth, and stable frame identity. Mutable frames are now ordinary identity classes instead of data classes; no generated equality, copy, or destructuring behavior was used. Focused tests prove frame/stack identity, growth, and capture synchronization, while the complete coroutine, hook, debugger, and DAP suites cover suspended visibility and mutation.

Matched GC reduces 10,000 ordinary Lua calls from `2,401,377.429` to `2,241,362.534 B/op`, a reduction of `160,014.895 B/op` or 6.7% (about 16 bytes per frame). The closure control falls from `2,321,945.610` to `2,161,945.316 B/op`, the same per-call effect. Coroutine yield/resume is effectively unchanged at `22,878,311.396 B/op` versus `22,878,341.958 B/op`, consistent with its few Lua frames. The matched closure score improves from `2,000.554` to `1,629.448 us/op` (18.5%) with non-overlapping intervals, and the final full run confirms `1,628.765 us/op`; other timing controls remain too noisy for a supported claim. The full correctness suite and 14-control matrix pass. Post-change JFR removes `LuaStack` as an allocated class; `CallFrame` now accounts for 65.14% and boxed integers for 27.81% of sampled Lua-call pressure.

### M19 Zero-Based Frame-Metadata Checkpoint

Lua 5.5 needs `CallInfo.func` and `top` offsets because all calls share the thread's value stack. KLua's co-located design gives every frame its own zero-based storage, and repository-wide inspection proves no `CallFrame` constructor supplies a nonzero base or either of the unused `returnBase` and frame-level `expectedResults` values. The latter result count already belongs to `PendingCallState` only while a call is suspended. Those three stored integers are removed; register translation is explicitly identity-based and debug return transfer starts at the zero-based return register plus one. Focused opcode tests and the exact debug `ftransfer`/`ntransfer` hook test cover the affected calculations.

Matched GC reduces 10,000 Lua calls from `2,241,361.355` to `2,081,345.412 B/op`, a reduction of `160,015.943 B/op` or 7.1% (about 16 bytes per frame after JVM alignment). The closure control falls from `2,161,913.904` to `2,001,913.823 B/op`, the same per-call effect. Coroutine yield/resume changes only from `22,878,308.089` to `22,878,277.367 B/op`, consistent with its few frames. Timing is too noisy for a supported claim; the final full run completes with Lua calls at `2,081.649 us/op`, closure calls at `1,965.621 us/op`, and coroutine transfer at `24,332.924 us/op`. The full correctness suite and 14-control matrix pass. Post-change JFR attributes 60.06% of sampled Lua-call pressure to boxed loop integers and 37.23% to the remaining frame object.

### M19 Closure-Owned Frame-Metadata Checkpoint

Lua 5.5's `ci_func(ci)` identifies the exact closure in a `CallInfo`; VM and debug paths then reach the prototype through `cl->p` rather than storing independent prototype and upvalue references on each call record. KLua now follows that ownership rule: every Lua frame retains its exact `LuaClosure`, and computed accessors derive the prototype and upvalue list from it. The separately resolved environment remains frame-owned because KLua's nullable closure environment and globals fallback can intentionally differ from the closure's stored field. Focused tests prove closure, prototype, and underlying upvalue-list identity, while the full debug and VM suites preserve function identity and call behavior.

Matched GC reduces 10,000 Lua calls from `2,081,345.207` to `2,001,341.308 B/op`, a reduction of about `80,004 B/op` or 3.8% (about 8 bytes per frame). The closure control falls from `2,001,913.361` to `1,921,899.126 B/op`, a reduction of about `80,014 B/op` or 4.0%. Coroutine yield/resume remains effectively unchanged at `22,878,278.040 B/op` versus `22,878,276.441 B/op`, consistent with its few Lua frames. Timing is too system-noisy for a supported claim. The full correctness suite and 14-control matrix pass. Post-change JFR attributes 60.97% of sampled Lua-call pressure to the per-frame `LuaExecutionResult.Returned`, 14.21% to its singleton result list, 9.40% to boxed arithmetic, 7.09% to stack arrays, and 6.81% to the now-smaller frame; the internal return-path representation is the next bounded audit.

### M19 Direct Lua-Return Transfer Checkpoint

Lua 5.5's `lvm.c:OP_RETURN` leaves results in the callee stack, and `ldo.c:luaD_poscall` runs `rethook` before `moveresults` copies only the wanted values into the caller. KLua now follows the same boundary for ordinary unsuspended Lua-to-Lua calls: after the return hook, the VM copies zero, one, fixed, or open results directly from the callee frame into the caller frame and uses nil padding where required. This removes the internal `LuaExecutionResult.Returned` and result-list allocation without changing native, public, coroutine, or suspended-call result objects. A new conformance test proves `debug.setlocal` in a return hook can alter the register value transferred to the caller, correcting KLua's former pre-hook snapshot order; a focused result matrix covers discarded, singleton, fixed multiple, nil-padded, and chained open results.

Matched GC reduces 10,000 Lua calls from `2,001,341.308` to `1,601,337.812 B/op`, a reduction of `400,003.496 B/op` or 20.0% (about 40 bytes per one-result return). The closure control falls from `1,921,899.126` to `1,521,858.038 B/op`, a reduction of `400,041.088 B/op` or 20.8%. The three-result vararg/multi-return control falls from `2,481,548.876` to `1,761,546.533 B/op`, a reduction of `720,002.343 B/op` or 29.0% (about 72 bytes per return). Host calls and coroutine yield/resume remain allocation-flat, and timing is too system-noisy for a supported claim. The full correctness suite and 14-control matrix pass. Post-change Lua-call JFR removes `LuaExecutionResult.Returned` and result-list classes; frame creation accounts for 70.26%, stack arrays 13.51%, and boxed arithmetic 14.69% of sampled pressure. A function-valued `__index` profile selects a separate local target: repeated immutable metamethod `CallSiteInfo` construction plus name substring/string storage contributes about 11% of sampled allocation, while numeric boxing remains deferred as a representation-level change.

### M19 Metamethod Call-Site Metadata Checkpoint

Lua 5.5's `ldebug.c:funcnamefromcode` maps metamethod-capable opcodes to an interned `tmname` entry, strips the fixed `__` prefix by pointer offset, and reports `namewhat = "metamethod"`; it does not build a new diagnostic record or name string per call. KLua now likewise returns prebuilt immutable `CallSiteInfo` records for its fixed `__index`, `__newindex`, length, comparison, concatenation, unary, bitwise, and arithmetic keys. Referential checks make canonical hot keys constant-time, while a fallback preserves behavior for any future noncanonical private key. Existing exact debug tests cover `index`, `newindex`, all supported operator names, and their `metamethod` classification.

Matched GC reduces 10,000 function-valued `__index` calls from `2,726,247.184` to `2,006,243.085 B/op`, a reduction of `720,004.099 B/op` or 26.4% (about 72 bytes per call). Ordinary Lua calls remain effectively unchanged at `1,601,337.691 B/op`, as does raw table read/write at `1,252,593.552 B/op`. Timing intervals overlap, so no speed claim is supported. The full correctness suite and 14-control matrix pass. Post-change JFR removes the selected `CallSiteInfo`, substring byte-array, and name-string sites. The remaining local entry cost is the two-value `listOf(receiver, key)` snapshot: its object array and `Arrays$ArrayList` wrapper are the next bounded target; frame pooling and numeric unboxing remain excluded.

### M19 Fixed-Arity Lua-Metamethod Argument Checkpoint

Lua 5.5's `ltm.c:luaT_callTMres` and `luaT_callTM` place the metamethod function and its two or three arguments directly into stack slots before entering the call machinery. KLua now follows that boundary for Lua-closure metamethods: fixed parameters are placed directly into a new frame, missing parameters are nil-filled, and a vararg closure receives an exact owned copy of only its extra arguments. Native functions and callable values retain their existing list snapshots, while call-site metadata, argument order, `__newindex`'s third value, and the existing error/yield boundary remain unchanged. A focused frame test covers two-argument nil fill and three-argument vararg ownership; the index, newindex, length, operator, callable-value, native-function, and exact debug-name suites cover the public paths.

Matched GC reduces 10,000 function-valued `__index` calls from `2,006,243.085` to `1,766,183.055 B/op`, a reduction of `240,060.030 B/op` or 12.0% (about 24 bytes per call). Host calls remain effectively unchanged at `7,278,194.391 B/op`, ordinary Lua calls at `1,601,313.734 B/op`, and raw table read/write at `1,252,594.007 B/op`. Timing is too noisy for a supported claim. The full correctness suite and 14-control matrix pass. Post-change JFR removes `Arrays$ArrayList` from the workload; the residual profile is dominated by `LuaExecutionResult.Returned` at 64.17%, the stack array at 15.21%, the frame at 12.34%, and the singleton result list at 1.43%. The next bounded audit therefore targets single-result Lua metamethod completion without changing public, native, callable, coroutine, or general multi-result transport.

### M19/M20 Metamethod-Completion Audit And Yieldable-Index Checkpoint

The proposed single-result Lua-metamethod specialization is rejected. Lua 5.5's `ltm.c:luaT_callTMres` permits a metamethod to yield when its caller is Lua code, `ldo.c` retains the interrupted call, and `lvm.c:luaV_finishOp` moves the resumed first result into `GETTABLE`, `GETFIELD`, and environment-read destinations, using nil when no value is returned. KLua's synchronous result helper instead treated the same yield as an outside-coroutine error; bypassing its result object first would have optimized and entrenched that conformance gap.

The first bounded continuation slice now covers KLua's `GET_TABLE`, `GET_FIELD`, and `GET_GLOBAL` opcodes. Read helpers explicitly enable suspension only for Lua instruction dispatch, table-valued `__index` chains carry that permission to the final function, and a suspended Lua or explicitly yieldable native metamethod reuses the caller's pending-result machinery to install exactly one resumed result. Calls made through native standard-library code retain the non-yieldable C boundary. Focused Lua-source tests cover receiver/key transfer, first-of-many selection, nil padding, computed keys, table chains, environment reads, native continuations, and the native `table.concat` boundary.

Matched GC remains effectively flat: function-valued `__index` measures `1,766,224.765 B/op` versus the prior `1,766,183.055`, ordinary Lua calls `1,601,337.877`, raw table read/write `1,252,594.677`, host calls `7,278,194.264`, and the existing coroutine control `22,878,262.260 B/op`. Timing is too noisy for a supported claim. The full correctness suite and all 14 benchmark controls pass. Remaining metamethod yield completion is explicitly queued by operation family rather than hidden behind the rejected performance work.

### M20 Yieldable-Newindex Checkpoint

Lua 5.5's `lvm.c:luaV_finishset` calls `ltm.c:luaT_callTM` with receiver, key, and value, requests no results, and leaves `SETTABLE`, `SETFIELD`, and environment-write opcodes with no post-yield value movement in `luaV_finishOp`. KLua now carries the same Lua-only suspension permission through its table, userdata-metatable, primitive-metatable, and table-chain assignment helpers. A suspended Lua closure or explicitly yieldable native `__newindex` reuses pending-call continuation state with zero expected results, so repeated yields resume the interrupted assignment and discard every metamethod return value. Native standard-library entry retains its non-yieldable boundary.

Focused Lua-source tests cover field and computed assignments, global/environment writes, exact receiver/key/value transfer, table-valued chains, repeated Lua yields, native continuation, discarded multiple results, raw-table nonmutation, and `table.move` boundary rejection. The full correctness suite and all 14 benchmark controls pass. Non-suspending allocation remains flat: table read/write is `1,252,596.553 B/op`, function-valued `__index` `1,766,340.191`, host calls `7,278,194.595`, and the existing coroutine control `22,878,282.971 B/op`. Timing is system-noisy and supports no speed claim.

### M20 Yieldable Value-Operator Checkpoint

Lua 5.5's `lvm.c:luaV_finishOp` completes binary arithmetic and bitwise metamethod opcodes plus `OP_UNM`, `OP_BNOT`, and `OP_LEN` by moving the metamethod's first result into register A; `ldo.c:moveresults` supplies nil when the call returns no value. KLua now gives the same Lua-only suspension permission to all 15 corresponding opcode families, including Lua closures, explicitly yieldable native functions, and callable metamethod values. Pending-call continuation installs exactly one result after each final resume, repeated yields retain the interrupted operation, and unary/length calls preserve Lua's duplicated operand arguments. Native standard-library entry remains a non-yieldable boundary.

Focused Lua-source tests cover all seven binary arithmetic operations, five binary bitwise operations, unary minus, bitwise not, and length; first-of-many selection; no-result nil padding; exact operand transfer; repeated yields; native and callable metamethod continuations; and `table.unpack` boundary rejection. The full correctness suite and all 14 benchmark controls pass. A first implementation changed a hot arithmetic helper's JVM shape and regressed JVM-to-Lua allocation from the `9,673,883 B/op` baseline to `9,993,882.289 B/op`, about 32 bytes per call; that version was rejected. Restoring the original helper boundary while limiting suspension to its metamethod branch brings the final control back to `9,673,884.690 B/op`. Other final controls remain allocation-flat, including function-valued `__index` at `1,766,341.046`, Lua calls at `1,601,339.694`, host calls at `7,278,196.610`, coroutine yield/resume at `22,878,286.945`, and numeric loops at `961,240.614 B/op`. Timing remains system-noisy and supports no speed claim.

### M20 Yieldable Comparison Checkpoint

Lua 5.5's `lvm.c:luaV_finishOp` completes yielding `OP_EQ`, `OP_LT`, and `OP_LE` by applying `l_isfalse` to the first metamethod result and then honoring the comparison instruction's conditional sense. `ltm.c:callbinTM` selects the first operand's metamethod and falls back to the second without reordering the arguments. KLua now preserves the corresponding rule in its value-producing bytecode: a comparison continuation writes one canonical `LuaBoolean` from the resumed first result or nil, `~=` applies its following `NOT`, and `>`/`>=` retain the compiler's reversed-operand lowering. Lua closures, explicitly yieldable native functions, and callable metamethod values can suspend repeatedly from Lua opcodes; native API and standard-library comparison helpers retain a non-yieldable C boundary.

Focused Lua-source tests cover all six source comparison operators, first- and second-operand metamethod selection, exact argument order, repeated yields, zero/one/multiple return behavior, Lua truthiness including numeric zero, closure/native/callable paths, resumed errors, and `math.min`/`table.move` boundary rejection. An initial explicit result-mode field enlarged every coroutine pending-call record by 8 bytes, moving its control from the `22,878,287 B/op` baseline to `22,958,263.716 B/op`; a triple-valued catch representation also moved JVM-to-Lua calls from the `9,673,884 B/op` baseline to `9,993,882.718 B/op`. Both representations were rejected. Encoding the comparison completion in the existing pending result-count field keeps the final controls flat: coroutine yield/resume is `22,878,281.000`, JVM-to-Lua calls `9,673,883.344`, function-valued `__index` `1,766,339.507`, Lua calls `1,601,338.368`, host calls `7,278,195.163`, and numeric loops `961,240.823 B/op`. The full correctness suite and all 14 controls pass; timing remains system-noisy and supports no speed claim.

### M20 Yieldable Concatenation Checkpoint

Lua 5.5's `lvm.c:luaV_concat` reduces concatenation operands from right to left and calls `luaT_tryconcatTM` when a pair cannot be converted directly; after a yield, `luaV_finishOp` installs the first metamethod result in the reduced operand position and re-enters `luaV_concat` with the remaining count. KLua's compiler represents the same progress as a right-associated sequence of binary `CONCAT` instructions, so the suspended program counter and destination register already preserve the partially reduced value and remaining operands. Resume now writes the first result, or nil for no results, into that instruction's destination and lets the next outer `CONCAT` continue without reevaluating operands or allocating a separate progress object.

Focused Lua-source tests cover four-operand right-to-left reduction, repeated yields at multiple reductions, exact first- and second-operand selection and argument order, raw string/number reduction before an outer metamethod, once-only operand evaluation, zero/one/multiple result behavior, closure/native/callable paths, resumed errors, and `table.sort` comparator boundary rejection. A direct yieldable call through the shared operator helper reproduced the established HotSpot escape-analysis regression, moving JVM-to-Lua calls from `9,673,883` to `9,993,883 B/op`, about 32 bytes per call; that code shape was rejected. A dedicated concat metamethod path restores the final control to `9,673,882.682 B/op` while string concatenation remains flat at `16,649,068.272 B/op` and coroutine yield/resume at `22,878,261.175 B/op`. The complete correctness suite and all 14 allocation controls pass; timing remains system-noisy and supports no speed claim.

### M19 Direct Core-Backed Protected-Call Checkpoint

Lua 5.5's conventional `lapi.c:lua_pcallk` path enters `ldo.c:luaD_pcall` and `luaD_callnoyield`, keeping the function, arguments, and results in the Lua stack while enforcing a non-yieldable protected-call boundary. KLua now follows that boundary when `LuaState.pcall` invokes a source-backed Lua closure that is already represented by a core function: `pcallNativeFunction` builds core arguments once, calls `KLuaCoreRuntime.callFunction` with `isYieldable = false`, and pushes the core results directly. Arbitrary host functions and callable values retain the generic `DefaultLuaCallContext`/`LuaReturn` adapter path, and protected error objects, exit propagation, fixed/open result handling, nil padding, and unsupported API-value diagnostics remain unchanged.

Focused API tests cover reuse of a returned closure, argument order, fixed-result truncation and nil padding, open results, runtime errors, and protected argument-conversion failures. Matched GC reduces 10,000 JVM-to-Lua protected calls from `9,673,884.563` to `6,560,001.808 B/op`, a reduction of `3,113,882.755 B/op` or 32.19% (about 311 bytes per call). Post-change JFR removes the selected `DefaultLuaCallContext`, stack `subList`, duplicate core-argument list, and `LuaReturn` allocation sites. A separate direct metamethod-result experiment reduced the index control by about `400,000 B/op` but added `320,000 B/op` to JVM-to-Lua calls through a HotSpot escape-analysis shape, so it was rejected and fully reverted. The complete correctness suite and all 14 allocation controls pass; adjacent controls remain flat, including host calls at `7,278,182.254`, function-valued `__index` at `1,766,312.743`, Lua calls at `1,601,339.480`, coroutine yield/resume at `22,878,260.956`, and numeric loops at `961,240.593 B/op`. Timing remains system-noisy and supports no speed claim.

### M20 Compile-Time-Const Call-Site Name Checkpoint

Lua 5.5's `lparser.c:localstat` promotes only the final local in an exactly matched declaration when its `<const>` initializer passes `lcode.c:luaK_exp2const`; `singlevaraux` carries that `VCONST` value through nested functions without creating an upvalue. Safe unary, arithmetic, bitwise, and the no-jump logical cases are folded by `luaK_prefix`, `luaK_posfix`, and `constfolding`. Consequently, `ldebug.c:basicgetobjname`/`getobjname` sees the substituted load or indexed opcode: a string callee is reported by value as `constant`, a non-string constant callee has no inferred name, and constant string or small unsigned integer keys become named `field` calls. KLua retains its ordinary execution slots but now tracks the same compile-time value environment solely for call-site metadata, including scope shadowing/restoration and nested-function lookup.

Focused compiler metadata and Lua-source debug tests cover direct string and numeric constants, the final-declarator and exact-value-count rule, adjusted and dynamic const initializers, safe numeric/logical folding and its zero/division failure boundaries, propagated nested constants, shadowing, string callees, unnamed numeric callees, constant string keys, and optimized integer-index keys. The complete correctness suite passes. A matched compile-allocation negative control measures `20,497.264 B/op` versus `20,543.538 B/op` in the prior 14-control matrix, so the lazy constant-binding map remains within the established allocation band; timing is system-noisy and supports no speed claim. The rolling gap snapshot now records this closed family while retaining broader indirect-call name inference as M20 work.

### M19 Shared Metatable-Service Checkpoint

Lua 5.5 stores primitive metatables once in `global_State.mt` (`lstate.h`) and reads the current shared entry in `ltm.c:luaT_gettmbyobj` and `lapi.c:lua_getmetatable`; `lua_setmetatable` updates that same shared state, while userdata uses its object metatable. KLua previously represented the equivalent live lookups with four capturing callbacks every time `KLuaCoreRuntime` created a VM. A single provider now belongs to each `KLuaCoreGlobals` instance and supplies current string, primitive-type, and host-userdata metatables to all of its VMs. This preserves mutation visibility and removes the repeated callback objects without snapshotting metatable state.

Fresh JFR selected those callback objects in the JVM-to-Lua protected-call control and confirms they disappear after consolidation. The immediate matched GC control reduces 10,000 calls from `6,560,001.754` to `5,760,002.191 B/op`, a reduction of `799,999.563 B/op` or 12.20% (about 80 bytes per call). The complete correctness suite passes, including the metatable-sensitive standard-library coverage. The final 14-control allocation matrix records compile `20,522.994`, numeric loop `961,160.816`, Lua calls `1,601,257.495`, closure counter `1,521,782.783`, host calls `7,278,115.012`, table read/write `1,252,516.927`, function-valued `__index` `1,766,258.374`, JVM-to-Lua calls `5,760,005.259`, method calls `1,681,520.626`, recursive Fibonacci `4,642,401.469`, string concatenation `16,648,988.833`, coroutine yield/resume `22,878,110.950`, vararg/multi-return `1,761,468.118`, and entity update `1,531,375.238 B/op`. Timing remains system-noisy and supports no speed claim.

### M20 To-Be-Closed Lifecycle Checkpoint

Lua 5.5's `lparser.c:localstat`, `checktoclose`, `marktobeclosed`, `leaveblock`, `closegoto`, and `forlist` establish readonly close bindings, mark them only after adjusted initialization, close scopes on all exits, and swap generic-for's fourth expression result into the hidden closing slot. `lfunc.c:checkclosemth`, `luaF_newtbcupval`, `callclosemethod`, and `luaF_close`, together with `lvm.c:OP_TBC`/`OP_CLOSE`/`OP_RETURN`, require declaration-time raw-method validation, close-time method lookup, reverse order, captured-upvalue closure before close calls, normal one-argument calls, active-error propagation and replacement, and yieldable normal closing. `ldo.c:lua_resume` retains an unprotected failed coroutine stack, while `lstate.c:luaE_resetthread` and `lua_closethread` later reset it, pass the active error through pending close handlers, and abandon yielded continuations through protected non-yieldable closing. KLua now implements that complete lifecycle in compiler bytecode, call-frame close-slot state, VM continuations and error handling, retained failed-coroutine frames, core coroutine closure, and generic-for's hidden fourth value. Registered userdata types can expose raw metamethod names, and `io` file handles map `__close` to the Lua `liolib.c:f_gc`-style idempotent closer, so `io.lines(filename)` closes owned files on early loop exit as well as EOF.

Focused compiler, VM, core-coroutine, and Lua-source standard-library tests cover nil/false no-ops, declaration rejection, readonly direct and captured bindings, reverse closure across fallthrough, `break`, `goto`, and return, preservation of open and fixed return values, callable and dynamically replaced close methods, normal and error argument shapes, replacement errors while earlier closers still run, normal close yields and non-yieldable top-level rejection, generic-for fourth-value early exit, suspended-coroutine abandonment/unwinding, and named `io.lines` file closure. The complete core suite and focused stdlib integration pass; the full repository verification is the package exit criterion.

### M20 Indirect Call-Name Inference Audit

Lua 5.5's `ldebug.c:findsetreg`, `basicgetobjname`, `rname`, `isEnv`, `getobjname`, and `funcnamefromcode` infer ordinary call names from an active local, a backward `MOVE`, an upvalue load, a string constant load, or a table-access opcode; they additionally name method, generic-for, hook, finalizer, read/write/operator, and close metamethod calls directly from the calling opcode or call state. KLua records the equivalent source identity when it lowers each call instead of replaying bytecode symbolically at inspection time. The audit maps every KLua-supported source family to that reference chain: direct and moved source variables retain local/upvalue/global identity, string constants and Lua 5.5 compile-time const substitution retain constant/key identity, table accesses retain environment/field/integer-index/method classification, and fixed VM call sites cover generic-for, hooks, `__call`, reads, writes, operators, and `__close`. Lua `__gc` finalization is not a hidden name-inference family in KLua because that lifecycle is not implemented; it must be selected as its own future VM campaign rather than kept behind a vague debug-name gap.

Existing compiler metadata and Lua-source tests already cover every ordinary symbolic-origin and supported fixed-call family except the newly reachable close path. Focused close-name coverage now exercises both block `CLOSE` and return-time closing and confirms `name = "close"`, `namewhat = "metamethod"`, matching `funcnamefromcode`'s `OP_CLOSE`/`OP_RETURN` branch. The broader indirect-name gap was stale and has been removed from the conformance snapshot.

### M20 Cross-Thread Debug State Audit

Lua 5.5's `ldblib.c:getthread` accepts an optional thread without restricting its status. `ldebug.c:lua_getstack` walks whatever `CallInfo` chain that target still owns; `ldo.c:lua_resume` leaves the chain intact after an unprotected error, and `lstate.c:luaE_resetthread` removes it only when `lua_closethread` resets the coroutine. The resulting reachable-state matrix is now explicit:

| Thread state | Expected stack | KLua behavior |
| --- | --- | --- |
| Current/main | Live caller frames | Existing explicit-current offset handling |
| Fresh suspended | Empty | `getinfo` is nil, local levels are invalid, traceback has only its header |
| Yielded suspended | Live suspended frames | Existing cross-thread inspection and mutation |
| Normal | Live frames while it resumes another coroutine | Existing cross-thread inspection and mutation |
| Dead after success | Empty | Existing empty-stack behavior |
| Dead after error | Live until `coroutine.close`, then empty | Failed frames are retained and mutable; close handlers and reset run at close time |

The failed state was the one missing family. Coroutine VMs now retain unhandled frames and open captures instead of closing and popping them during `resume`; `debug.getinfo`, `debug.traceback`, `debug.getlocal`, and `debug.setlocal` therefore operate on the failed stack, while hook state remains independently targetable as in the other states. `coroutine.close` passes the active error through pending close handlers, resets the frames, reports the final error once, and leaves subsequent debug inspection with the same empty-stack behavior as a successfully dead coroutine. Focused core and Lua-source tests cover fresh and failed targets, live local mutation, deferred `<close>` timing and error arguments, reset-after-close, and the existing full state families. The vague broader cross-thread debug gap was stale and has been removed.

### M20 Nested Coroutine State Audit

Lua 5.5's `lcorolib.c:auxstatus` derives `running`, `normal`, `suspended`, and `dead` from thread identity, `lua_status`, live stack frames, and the initial function slot. `luaB_coresume` returns resume errors without resetting the target, and `luaB_close` only resets dead or suspended threads; current main and normal resumer states reject close, while a non-main coroutine may close itself. Existing KLua coverage already exercises those current/main, nested-normal, fresh, yielded, dead-success, dead-error, explicit-close, self-close, and protected resume-error transitions.

The missing transition was `lcorolib.c:luaB_auxwrap`'s special failed-thread path. A wrapper owns a hidden coroutine that callers cannot close explicitly, so after an execution error Lua distinguishes the target's error status from ordinary dead/normal resume failures, calls `lua_closethread`, and only then propagates the final error. KLua now records genuine resume failures separately from state-validation errors. A failed wrapper runs its hidden thread's pending close handlers with the original error, accepts a replacement close error, resets the thread, and then preserves the final error object's identity through `pcall`; attempts to call an already dead or recursively running wrapper retain their existing messages without an inappropriate reset. Focused Lua-source tests cover both preserved and replaced table error objects and the active error passed to `__close`; the complete coroutine/stdlib and repository suites pass. The vague additional nested-coroutine edge-case wording has been retired from the gap snapshot.

Use this work-package order:

| Order | Work package | Outcome and exit criteria | Expected commit shape |
| --- | --- | --- | --- |
| 1 | M20 `table.move` boundary audit | Audit `ltablib.c:tmove` and `checktab` across overlapping directions, empty ranges, integer-wrap guards, same/different receivers, and table-like metamethod receivers. Close one bounded missing family or retire stale wording, then finish with focused Lua-source cases plus the full suite. | One coherent table-conformance commit; do not mix unrelated library behavior. |

M20 conformance remains important throughout development, but broad hardening should run as an explicitly selected campaign rather than an open-ended stream of unrelated probes. A campaign should name one subsystem or semantic invariant, list the affected entry points and reference-source functions, define its case matrix, and finish by updating the gap snapshot. Regression, data-integrity, security, or active-milestone blocking fixes may interrupt the order above; incidental edge cases should be queued for the next campaign.

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
- Update the work-package table when a package closes or priority materially changes, not for every commit inside the package.
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
