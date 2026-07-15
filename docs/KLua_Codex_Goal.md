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
- AST model, compiler, internal bytecode, prototype model with a consolidated debug-info view and serialization-friendly snapshot hook covering source IDs, valid breakpoint line metadata, local variable debug ranges, upvalue name metadata, function definition line ranges, Lua-style const local and for-control assignment rejection, regular and `<const>` named/wildcard `global` declaration scopes, initialized `global` declarations, local-shadowing resolution, lexical `_ENV` lowering, closure-shared default environment assignment, plain function declarations through resolved bindings including const-global rejection, `global function` declarations with Lua-style already-defined checks, conservative block-scoped, close-aware `goto`/label compilation including end-of-block labels and exported pending gotos, and close-aware `break` exits, plus constant pool, disassembler, initial internal constant-pool, instruction-stream, recursive prototype serialization, prototype bytecode package encoding/decoding with fixed KLua/Lua 5.5 format markers plus payload size/checksum metadata, and core/API runtime bytecode package compile/load helpers.
- Interpreter VM with core values, stack/frame execution, expressions, locals, branches, loops, functions, calls, returns, varargs, tables, closures, upvalues, metatables, metamethods, globals, native functions, basic userdata bindings, initial instruction-limit enforcement for chunk execution, exported Lua function calls, and coroutine resumes, and internal thread/yield/resume/dead-state plumbing.
- Java-friendly `LuaState` API, high-level `Lua` facade, Kotlin convenience helpers, single-target runtime configuration, bytecode package resource loading, configurable instruction limits, standard-library whitelisting, a bounded `LuaConfig.production()` preset that suppresses debugger and unsafe standard-library host access while preserving explicit host bindings, and initial JMH baselines separating parse/compile/package encoding from fresh-coroutine VM numeric-loop execution.
- Runtime errors preserve structured source-name, line, Lua call-frame metadata including function definition, arity, upvalue, and active-line data, registered global and userdata host/native call-frame metadata, readable traceback strings, and API-visible explicit error objects from VM bytecode positions through core execution results, API runtime exceptions, direct native protected calls, and API coroutine runtime results, and host exceptions can survive as runtime error causes.
- Partial `klua-stdlib` support with base, math, string, table, utf8, package, coroutine, initial os, source-backed io, and minimal debug library installers covered by focused Lua-source tests, with config-level standard-library whitelisting and debug-library opt-out for production-style configs, including Lua 5.5-style math random xoshiro state and range projection, Lua warning control handling, Lua-style `require` searcher diagnostic prefixing, raw searcher iteration, original loaded/preload table binding, `package.cpath`, pure-Kotlin `package.loadlib` and C/C-root searcher diagnostics including disabled-library reporting, Lua-style source-prefixing and nil error-object normalization for `assert`/`error` through protected calls and coroutine resume/close errors, table-backed `load`/`loadfile` environment arguments, Lua-style `load` reader failure results, Lua-style `loadfile` mode validation ordering, stateless `utf8.codes` iterator controls, Lua-backed coroutine yield/resume, protected `pcall`/`xpcall` yield continuation, `xpcall` message-handler result/error behavior, wrap/close/isyieldable behavior, main/normal coroutine status and close reporting, coroutine thread type/string reporting, host/native yield-boundary checks, Lua-style `tostring` userdata identity and `__name` handling, Lua-frame-backed `debug.traceback`, explicit-current/suspended/normal/empty-stack coroutine arguments for `debug.traceback`/`debug.getinfo`/`debug.getlocal`/`debug.setlocal`/`debug.sethook`/`debug.gethook`, level-based and function-value `debug.getinfo` source/currentline/function-definition metadata and common call-site name metadata for Lua and host/native functions, `debug.getinfo` option filtering/default metadata including arity/upvalue/active-line/transfer/tail-call fields plus call/return hook transfer metadata, level-based `debug.getlocal` local name/value inspection with out-of-range level errors, function-value `debug.getlocal` local-name inspection, `debug.setlocal` local mutation with out-of-range level errors, `debug.getupvalue` closure upvalue inspection, `debug.setupvalue` closure upvalue mutation, `debug.upvalueid`/`debug.upvaluejoin` closure upvalue identity and sharing, host-userdata `debug.getuservalue`/`debug.setuservalue` slots, table and supported non-table `debug.getmetatable`/`debug.setmetatable` including host-userdata metatables, `table.concat`/`table.insert`/`table.move`/`table.remove`/`table.sort` support for table-like non-table values with required metamethods, source-like `table.unpack` length/index behavior for table-like non-table values, userdata index/newindex/call/length/comparison/arithmetic/bitwise/concat/unary metamethods, userdata `__name` index/call/length/operator errors, `os.clock`/`os.date` string and table formats/`os.difftime`/`os.exit` embedder-safe exit signaling/`os.getenv`/`os.remove`/`os.rename`/`os.setlocale`/`os.time` date-table conversion/`os.tmpname`, `debug.getregistry`, non-interactive `debug.debug`, Lua-style `debug.sethook`/`debug.gethook` call/return/line/count hook support including targeted coroutine hooks, and Lua-style argument validation for these debug/package/os entry points plus selected base/table iterator edge cases.
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
- Known Lua 5.5 language gaps include to-be-closed local semantics; `<const>` locals are parsed and enforced, statically nil/false `<close>` locals compile as no-op close values, dynamic close initializers are runtime-checked to allow only nil/false until `__close` semantics exist, and static non-false close locals are still rejected before execution.
- Broader standard library implementation, including table edge cases, string pattern/format, math edge cases, and utf8 coverage.
- Known debug-library gaps include broader cross-thread debug behavior beyond explicit current threads and suspended, normal, and empty-stack KLua coroutine snapshots.
- Broader coroutine runtime hardening, including additional nested coroutine edge cases and Lua 5.5 conformance coverage beyond the current Lua-backed and protected-call yield/resume paths.
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

The closure audit above closes M13 through M18 against their success criteria. M19 is active. Compile, numeric-loop, Lua-call, closure-counter, host-call, table read/write, function-valued `__index`, JVM-to-Lua call, method-call, recursive Fibonacci, growing string-concatenation, coroutine yield/resume, fixed-arity vararg/multi-return, and table-backed entity-update baselines now exist. Profiling the first selected hotspot showed that eager Lua debug-frame snapshots dominated the host-call bridge; lazy on-access frame materialization reduced profiled allocation by 65.4% and the matched full-suite host-call score by 55.5%. Profiling the second hotspot showed that ordinary table accesses eagerly allocated metamethod cycle-detection sets; matching Lua 5.5's raw-fast-path/finish-operation split reduced table-workload allocation by 59.5% and profiled time by 10.3%. JFR profiling of the JVM-to-Lua control then showed scalar conversions eagerly allocating table-identity caches; lazy graph-cache creation reduced that workload's allocation by 44.3% and profiled time by 16.0%. The same evidence on coroutine transfer showed specialized argument-synchronization caches on every scalar yield/resume; lazy creation reduced allocation by 35.5% and the matched full-suite score by 15.9%. Method-control profiling then showed repeated Lua-byte reconstruction in every string-key hash/equality probe; caching immutable raw bytes and hashes on demand reduced method allocation by 84.9%, matched profiled time by 65.0%, and the full-suite method score by 64.2% without materializing bytes for transient non-key strings. Residual coroutine profiling then selected eager VM native-context service callbacks; consolidating them into one VM-backed context reduced coroutine allocation by 7.4%, matched profiled time by 11.6%, and the post-string-cache full-suite score by 24.5%. A matched host/coroutine comparison next selected API-side service lambdas; passing one core-backed call context directly reduced coroutine allocation by a further 7.0% and host allocation by 10.8%. Timing was too noisy for a supported claim in that package. Matched result profiling then rejected a broad scalar/list rewrite but selected the coroutine-only double copy used to prefix successful resume values; single-pass prefixing reduced coroutine allocation by 3.1% with host and JVM-to-Lua controls unchanged. The first application-kernel follow-up removed an intermediate vararg-range materialization, reducing vararg/multi-return allocation by 10.8%; timing was too noisy for a supported claim. A numeric-loop follow-up rejected a redundant-looking integer-limit fast path after matched GC proved that HotSpot already eliminates its temporary wrapper and JFR showed immutable value boxing is the representation-level residual. Static `NEW_TABLE` entry hints then reduced entity-kernel allocation by 2.0% and removed the selected resize hotspot without affecting evaluation order. Lazy stack capture storage reduced ordinary Lua-call allocation by another 14.0% while preserving shared and closed upvalue identity. Splitting suspension and debug/hook fields out of ordinary call frames then reduced Lua-call allocation by a further 6.1%. Retaining one shared call-site metadata reference instead of two expanded fields reduced it by another 2.2%. Array-backed stack slots then removed another 24 bytes per created Lua frame, and shared empty vararg views removed a further 24 bytes from fixed-arity frames. All completed optimization packages retain the full correctness suite.

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

Use this work-package order:

| Order | Work package | Outcome and exit criteria | Expected commit shape |
| --- | --- | --- | --- |
| 1 | M19 Lua-to-Lua argument range transfer | Follow Lua 5.5's contiguous-stack call setup and copy Lua-closure arguments directly from the caller stack into the callee frame without an intermediate `List`. Preserve native-call lists, fixed-parameter nil fill, vararg snapshots, open-result expansion, yields, and call-site metadata. Exit with focused fixed/vararg and evaluation-order tests, matched Lua-call/host/vararg GC controls, and the full suite, or reject if the specialized path is not a clear allocation win. | One focused VM call-transfer/evidence commit; do not combine return-list or native API changes. |
| 2 | M19 post-transfer selection | Rerun the relevant controls and full suite, then select the next maintainable runtime cost from measured evidence. | One bounded profile/optimization package; do not mix unrelated workload families. |

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
