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
- Java-friendly `LuaState` API, high-level `Lua` facade, Kotlin convenience helpers, single-target runtime configuration, bytecode package resource loading, configurable instruction limits, standard-library whitelisting, and JMH module baseline.
- Runtime errors preserve structured source-name, line, Lua call-frame metadata including function definition, arity, upvalue, and active-line data, registered global and userdata host/native call-frame metadata, readable traceback strings, and API-visible explicit error objects from VM bytecode positions through core execution results, API runtime exceptions, direct native protected calls, and API coroutine runtime results, and host exceptions can survive as runtime error causes.
- Partial `klua-stdlib` support with base, math, string, table, utf8, package, coroutine, initial os, source-backed io, and minimal debug library installers covered by focused Lua-source tests, with config-level standard-library whitelisting and debug-library opt-out for production-style configs, including Lua 5.5-style math random xoshiro state and range projection, Lua warning control handling, Lua-style `require` searcher diagnostic prefixing, raw searcher iteration, original loaded/preload table binding, `package.cpath`, pure-Kotlin `package.loadlib` and C/C-root searcher diagnostics including disabled-library reporting, Lua-style source-prefixing and nil error-object normalization for `assert`/`error` through protected calls and coroutine resume/close errors, table-backed `load`/`loadfile` environment arguments, Lua-style `load` reader failure results, Lua-style `loadfile` mode validation ordering, stateless `utf8.codes` iterator controls, Lua-backed coroutine yield/resume, protected `pcall`/`xpcall` yield continuation, `xpcall` message-handler result/error behavior, wrap/close/isyieldable behavior, main/normal coroutine status and close reporting, coroutine thread type/string reporting, host/native yield-boundary checks, Lua-style `tostring` userdata identity and `__name` handling, Lua-frame-backed `debug.traceback`, explicit-current/suspended/normal/empty-stack coroutine arguments for `debug.traceback`/`debug.getinfo`/`debug.getlocal`/`debug.setlocal`/`debug.sethook`/`debug.gethook`, level-based and function-value `debug.getinfo` source/currentline/function-definition metadata and common call-site name metadata for Lua and host/native functions, `debug.getinfo` option filtering/default metadata including arity/upvalue/active-line/transfer/tail-call fields plus call/return hook transfer metadata, level-based `debug.getlocal` local name/value inspection with out-of-range level errors, function-value `debug.getlocal` local-name inspection, `debug.setlocal` local mutation with out-of-range level errors, `debug.getupvalue` closure upvalue inspection, `debug.setupvalue` closure upvalue mutation, `debug.upvalueid`/`debug.upvaluejoin` closure upvalue identity and sharing, host-userdata `debug.getuservalue`/`debug.setuservalue` slots, table and supported non-table `debug.getmetatable`/`debug.setmetatable` including host-userdata metatables, `table.concat`/`table.insert`/`table.move`/`table.remove`/`table.sort` support for table-like non-table values with required metamethods, source-like `table.unpack` length/index behavior for table-like non-table values, userdata index/newindex/call/length/comparison/arithmetic/bitwise/concat/unary metamethods, userdata `__name` index/call/length/operator errors, `os.clock`/`os.date` string and table formats/`os.difftime`/`os.exit` embedder-safe exit signaling/`os.getenv`/`os.remove`/`os.rename`/`os.setlocale`/`os.time` date-table conversion/`os.tmpname`, `debug.getregistry`, non-interactive `debug.debug`, Lua-style `debug.sethook`/`debug.gethook` call/return/line/count hook support including targeted coroutine hooks, and Lua-style argument validation for these debug/package/os entry points plus selected base/table iterator edge cases.
- Initial `klua-debug` source-line breakpoint manager for setting, replacing, source-wide replacement, clearing, listing, and enabled-hit lookup by source ID and line, public Lua stack-frame, local-variable, locals/upvalues/globals-scope, and paged table-variable debugger views with stable value summaries, opt-in userdata display adapters, and a small debug controller for pause, breakpoint, conditional-breakpoint, and step stop decisions.
- Initial `klua-dap` typed session surface for initialize requests, launch/attach/disconnect lifecycle modes, advertised debugger capabilities including stack-trace and variable paging, `setBreakpoints` source breakpoint replacement, `configurationDone`, pause/continue/step controls backed by the debug controller, thread listing, paged stackTrace/scopes/variables adapters over debug frame views, an expression-evaluation hook, transport-independent typed command routing, `Content-Length` framed JSON message transport primitives, a dependency-free JSON value parser/stringifier for DAP wire messages, generic DAP request/response/event protocol envelopes, a wire-session bridge from JSON request envelopes to typed DAP session responses with stackTrace thread/frame-provider routing, initialized-event emission, and chunked framed-request handling that emits framed response/event bytes.
- Initial `klua-tools` CLI debugger runner core with `klua --debug <script.lua> [args...]` invocation parsing, command-loop entry wrapper, command parsing for `break`, `run`, `continue`, `next`, `step`, `out`, `bt`, `locals`, `print`, and `quit`, source breakpoint registration, supplied-frame backtrace/local rendering, public-API script execution, `.kluac` bytecode package execution from the runner, top-level expression evaluation, `klua --compile <script.lua> <output.kluac>` bytecode package writing, debug tooling documentation, and an example VS Code launch configuration.
- String pattern support covers literals, dot wildcard, anchors, Lua character classes, bracket classes/ranges, bracketed percent classes, optional single-item matches, greedy/minimal single-item repetitions, basic captures for `find`, `match`, `gsub`, and `gmatch`, backreferences, balanced matches, and frontier matches.
- `string.gsub` supports string, function, and table replacements with Lua-style capture arguments and nil/false preservation.
- `string.packsize`, `string.pack`, and `string.unpack` cover Lua 5.5 format parsing, endian directives, alignment, fixed and variable strings, integer formats, and floating-point formats.
- Table-library conformance includes table-like primitive receivers for `table.concat`, `table.unpack`, `table.insert`, `table.remove`, `table.move`, and `table.sort` when Lua 5.5 `checktab`/metamethod requirements are met.
- Focused parser, compiler, VM, API, Kotlin helper, conformance, and foundation tests.

Remaining major gaps:

- Broader Lua language and conformance hardening.
- Known Lua 5.5 language gaps include to-be-closed local semantics; `<const>` locals are parsed and enforced, statically nil/false `<close>` locals compile as no-op close values, dynamic close initializers are runtime-checked to allow only nil/false until `__close` semantics exist, and static non-false close locals are still rejected before execution.
- Broader standard library implementation, including table edge cases, string pattern/format, math edge cases, and utf8 coverage.
- Known debug-library gaps include broader cross-thread debug behavior beyond explicit current threads and suspended, normal, and empty-stack KLua coroutine snapshots.
- Broader coroutine runtime hardening, including additional nested coroutine edge cases and Lua 5.5 conformance coverage beyond the current Lua-backed and protected-call yield/resume paths.
- Broader error, traceback, and debug-library conformance beyond the closed M14 foundation criteria.
- A live M15 debugger integration that can suspend VM execution and expose the stopped context.
- End-to-end M16 DAP and command-line debugger behavior over a live stopped runtime.
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
| A script can stop at a breakpoint. | Gap | `DebugController` can decide a synthetic breakpoint stop, but the VM never consults it and cannot suspend into a debugger-owned stop context. |
| A script can step into, over, and out. | Gap | Step-decision state is unit tested, but it is not driven by live VM line/call/return events. |
| The debugger can show the current source line. | Partial | API frame snapshots and `DebugFrameView` expose source and line, but there is no live stopped-frame provider. |
| Locals, upvalues, and globals are inspectable. | Partial | Snapshot/view and paging types exist; only locals are populated from API frame snapshots, and none are tied to a real debugger stop. |
| Debug expression evaluation works for simple expressions. | Gap | CLI evaluation runs in a fresh top-level Lua state and DAP accepts an injected evaluator; selected-frame evaluation is absent. |
| Coroutines appear as debuggable Lua threads. | Partial | Coroutine snapshots and debugger thread-provider abstractions exist, but live runtime threads are not registered with a debugger session. |
| Debug checks are cheap or disabled in normal fast execution. | Partial | Lua debug hooks are opt-in and the detached controller adds no VM cost, but the intended integrated fast-path/disabled-path behavior is not implemented or benchmarked. |

#### M16 DAP Adapter And Debug Tooling

| Success criterion | Status | Evidence |
| --- | --- | --- |
| A script can be launched under the CLI debugger. | Partial | The CLI parses debugger commands and executes source/bytecode, but `run` executes to completion without stopping. |
| Breakpoints and stepping work through the debug controller. | Partial | CLI/DAP commands mutate the standalone controller; no running script consumes those decisions. |
| A DAP client can set breakpoints and step through Lua code. | Partial | Typed and wire-level request routing is tested, but there is no VM-backed stopped session. |
| A DAP client can request stack frames and variables. | Partial | Stack/scope/variable routing and provider adapters are tested with supplied views, not live execution state. |
| Coroutines appear as debugger threads. | Partial | DAP thread-provider routing exists without runtime coroutine registration. |
| Large tables do not freeze the debugger UI. | Done | Variable expansion is paged and paging behavior is covered by debug/DAP tests. |
| Debugger can be disabled completely through production runtime configuration. | Partial | `debugEnabled = false` suppresses the debug standard library, but no integrated debugger runtime exists yet to prove a complete disabled path. |

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
| Scripts cannot access OS/files unless allowed. | Partial | Standard-library whitelisting can remove `os`, `io`, and package surfaces, but the default configuration still enables all libraries and is not production-safe by default. |
| Host APIs can be selectively exposed. | Done | Host globals/functions and registered userdata types are exposed explicitly by the embedder; unregistered host members are unavailable. |

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

The closure audit above closes M13, M14, and M17 against their success criteria. M15 is the earliest open milestone: its controller and view types are detached from VM execution. M16 depends on that live stop context, while M18 still needs a production-safe library-access policy before performance work begins.

Use this work-package order:

| Order | Work package | Outcome and exit criteria | Expected commit shape |
| --- | --- | --- | --- |
| 1 | M15 live debugger vertical slice | Connect VM line/call/return events and stop decisions to a real suspended Lua context. Exit when integration tests prove a breakpoint stop, step into/over/out, current line, locals, and upvalues on live execution. | One to three commits for VM stop state, debugger views, and integration verification. |
| 2 | M16 end-to-end client integration | Make the CLI and DAP paths consume the real stop context and pass end-to-end breakpoint, stepping, stack, scope, variable, and coroutine-thread tests. Update debug-tooling documentation to describe only working behavior. | One to three commits grouped by transport-independent behavior and client integration. |
| 3 | M18 production-safe access policy | Add an explicit production-safe configuration/preset so OS/file libraries require opt-in, and verify that selective host exposure remains intact. | One or two API/stdlib behavior commits with Java and Kotlin coverage. |
| 4 | M19 benchmark baselines | Replace the dummy benchmark with the first parse/compile and VM execution baselines before optimizing runtime paths. | One benchmark-baseline commit, followed by evidence-backed optimization packages only. |

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
