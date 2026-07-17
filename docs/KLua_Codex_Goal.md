# KLua Codex Goal

## Purpose

This document is the operational execution brief for implementing KLua with Codex. It defines the durable engineering rules, current milestone frontier, and the small set of work packages that may be executed next.

Use these documents for deeper detail instead of duplicating them here:

- `docs/KLua_Architecture.md`: architecture and design rationale.
- `docs/KLua_Implementation_Milestones.md`: capability roadmap and milestone success criteria.
- `docs/KLua_Conformance_Gaps.md`: source-backed gap evidence and residual limitations.
- `docs/KLua_Benchmark_Baseline.md`: benchmark methodology and retained measurements.
- `README.md`: user-facing project status.

This file is not a change log, benchmark report, or archive of completed campaigns. Git history is the record of individual changes.

## Codex Goal Prompt

Use this prompt to bootstrap Codex work sessions for this repository:

```text
/goal Follow docs/KLua_Codex_Goal.md to continue implementing KLua as a pure Kotlin Lua 5.5 runtime for JVM 17+. Treat the current repository and working tree as authoritative, and preserve unrelated user changes. Work only from an open package in Next Implementation Focus; if none exists, audit docs/KLua_Conformance_Gaps.md and add one bounded, milestone-aligned package before implementing. For behavior-sensitive parser, VM, coroutine, debug, and standard-library work, resolve the official Lua 5.5 source as described in Reference Behavior Policy, inspect the owning C function and helper chain, and treat that source as the primary logic reference. Define packages around a meaningful semantic rule or case matrix, not an individual probe. Prefer one to three coherent, independently verified final commits per package, keeping fixes and regression tests for the same rule together. Use a topic branch when practical, and do not stream internal checkpoint or documentation-only updates directly to the main branch. Update this goal only when the frontier, milestone status, material gap snapshot, or package order changes. Use Conventional Commit messages and keep moving through the prioritized plan without redefining the overall project goal as complete.
```

Update the prompt only when the execution rules in this document materially change.

## Project Goal

KLua is a greenfield pure Kotlin Lua runtime for JVM 17+. It should provide:

- Lua 5.5 as the only supported source-language target.
- A C-Lua-like low-level `LuaState` stack API.
- An `mlua`-style high-level embedding API for Java and Kotlin.
- A clean internal bytecode compiler and interpreter before any JVM bytecode generation.
- Source-level debugging as a runtime feature, not a wrapper around the JVM debugger.

There is no requirement before v1 to preserve old project APIs, transitional aliases, compatibility shims, or behavior from older Lua versions.

## Non-Negotiable Architecture Rules

- Keep internal runtime implementation in `klua-core`.
- Keep stable Java-friendly public APIs in `klua-api`.
- Keep Kotlin convenience APIs in `klua-kotlin`.
- Keep standard libraries in `klua-stdlib`.
- Keep debugging internals in `klua-debug`.
- Keep Debug Adapter Protocol integration in `klua-dap`.
- Keep command-line tools in `klua-tools`.
- Keep benchmarks in `klua-jmh`.
- Keep language, integration, and conformance tests in `klua-tests` or the owning module's focused test suite.
- All production and test packages must start with `io.github.realmlabs.klua`.
- Do not expose VM, compiler, bytecode, stack, frame, parser, or AST internals from public API modules.
- Do not add compatibility aliases for old project APIs.
- Do not add JVM bytecode generation before the interpreter, bytecode format, APIs, and benchmarks are stable.
- Do not optimize without a benchmark baseline.

## Work-Package Rules

- Define a package around one observable capability, Lua semantic rule, or tightly related official-source helper chain.
- A package may cross core, API, stdlib, and tests when those changes implement the same rule.
- Before implementation, record the owning milestone, outcome, affected modules, relevant Lua source functions, case matrix, verification, and exit criteria in `Next Implementation Focus`.
- A campaign must contain a meaningful behavior matrix. Do not create a separate package for each argument boundary, probe, assertion, diagnostic string, or test method when they share a source control path.
- When adjacent findings belong to the current rule, add them to its case matrix instead of repeatedly closing and reopening micro-campaigns.
- Incidental unrelated findings go to `docs/KLua_Conformance_Gaps.md`; they do not automatically interrupt the active package.
- Regression, data-integrity, security, or earliest-milestone blockers may interrupt the order, but the interruption must be recorded in the live plan.
- Mark relevant milestone success criteria as `Done`, `Partial`, or `Gap` before changing the primary frontier.
- Do not use isolated conformance probes as filler work.

## Commit And Integration Rules

- Prefer one to three review-ready final commits per work package. Commit count is not a progress metric.
- Keep each final commit independently understandable, revertible, and green.
- Put a behavior fix and its regression tests in the same commit unless a prerequisite refactor is independently meaningful and verified.
- Use a standalone test commit only when the coverage is independently valuable and the implementation is already correct.
- Keep unrelated milestone work, independently risky refactors, and public API changes in separate commits.
- Use no hard line-count, file-count, hourly, or daily commit quota. Semantic and rollback boundaries decide commit size.
- For long autonomous runs, prefer a topic branch. Internal checkpoint commits may be reorganized before integration; only the package's one to three review-ready commits should reach the shared main branch.
- When working directly on the shared main branch, finish the active package and its verification before starting the next package. Do not push every probe or working checkpoint.
- Include a Goal or gap-document update in the package's final behavior commit only when status, priority, or the residual gap materially changes. Do not touch this document in every code commit.
- Put detailed verification evidence in CI, tests, commit bodies, or a dedicated report rather than accumulating it in this file.
- Run relevant focused verification before every final commit and the broader required verification before closing a package. If verification cannot run, document why and obtain explicit user approval before committing.
- Use Conventional Commit messages with a clear type and scope, such as `feat(core): add token model`, `test(parser): cover numeric literals`, or `docs(goal): refresh M20 frontier`.

## File Structure Rules

- Production Kotlin files target no more than 1000 lines of code.
- Split files approaching 1000 lines by responsibility before adding unrelated behavior.
- Test files may be larger when their scenarios remain cohesive.
- Put large fixtures, golden outputs, generated files, and bytecode snapshots in clearly named paths.
- Give each file one main responsibility; split by lexer, parser, compiler, VM, API, debug, or stdlib behavior.
- Prefer small package-private or internal helpers over global utilities when behavior belongs to one subsystem.

## Language Target Policy

- Lua 5.5 is the only supported source-language target.
- Do not add old-version runtime modes, flags, aliases, shims, or source-version selection APIs.
- Do not support official PUC Lua `.luac` bytecode in v1.
- Use one internal KLua bytecode format.
- Compiled prototypes must not expose a language-version field.
- A future bytecode package may carry a fixed Lua 5.5 marker for validation and diagnostics, but it must not become a public version-selection API.
- Treat Lua 5.5 feature gaps as conformance work, not compatibility-profile work.

## Reference Behavior Policy

Resolve the official Lua 5.5 source checkout in this order:

1. The `LUA55_SOURCE` environment variable, when set.
2. The local default `~/Downloads/lua-lua-a5522f0`.

The resolved checkout must contain the relevant Lua source tree, currently `lua55/src` in the local checkout. If it is unavailable, do not guess behavior; report the blocked reference and either restore it or obtain explicit approval for an alternative source.

An optional reference executable can be selected with `LUA55_BIN`, then discovered as `lua5.5` on `PATH`. Its absence does not block source inspection, but any work package requiring differential probes must record how observable behavior was verified.

For behavior-sensitive work:

- Start from the source file and function that own the behavior, such as `lparser.c`, `lvm.c`, `ltable.c`, `lcorolib.c`, `loadlib.c`, or the matching `l*.c` library file.
- Follow helper functions far enough to understand argument checks, coercion, integer conversion, stack effects, metamethod order, continuations, boundary cases, and error construction.
- Treat official source logic as primary over existing KLua behavior, older-version memory, internet examples, or incomplete manual examples.
- Use the Lua 5.5 manual and executable probes for observable evidence; use the source to understand the control flow behind it.
- Update existing KLua tests when they conflict with official Lua 5.5 behavior.
- Record important source files and functions in test names, concise implementation comments, working notes, or commit bodies when that context improves auditability.
- Document intentional JVM adaptations or unimplementable host differences in `docs/KLua_Conformance_Gaps.md`.

## Public API Direction

These concepts define the initial API direction; exact signatures may evolve before v1:

- `LuaState`: low-level stack API for Java and Kotlin embedders.
- `Lua`: high-level embedding facade.
- `LuaChunk`: loaded source or bytecode chunk.
- `LuaConfig`: runtime configuration.
- `LuaStatus`: non-throwing low-level call result.
- `LuaException`: base structured exception for high-level APIs.
- `LuaReturn`: return values from host functions.
- `LuaCallContext`: host-function arguments and runtime context.

Public APIs may change freely before v1. After v1, breaking changes require explicit migration notes.

## Implementation Order

Follow this order unless a later task strictly unblocks an earlier one:

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

The first major proof point remains permanently covered by tests:

```text
Lua source -> lexer/parser -> AST -> compiler -> KLua bytecode -> VM -> observable result
```

## Current Status And Remaining Gaps

This is a milestone-level snapshot of committed capability, not a list of completed commits.

| Milestone range | Status | Summary |
| --- | --- | --- |
| M0-M12 | Done | Multi-module JVM 17 foundation, parser/compiler/VM pipeline, tables, closures, metatables, embedding APIs, userdata, and initial standard libraries are connected and tested. |
| M13-M18 | Done | Coroutine, structured error/debug metadata, hooks/debugger, DAP library integration, KLua bytecode packaging, and initial sandbox controls meet their documented foundation success criteria. Residual hardening remains M20 or later release work. |
| M19 | Done | The canonical JDK 17 baseline, byte-oriented strings, tagged VM slots, hybrid versioned tables, guarded inline caches, stack-range calls, fast/instrumented dispatch, and final matched performance screen have landed. Measurements and the one explicitly accepted bounded allocation tradeoff live in `docs/KLua_Benchmark_Baseline.md`. |
| M20 | Done | Source-backed language, VM, coroutine, debug, base, package, table, string, math, UTF-8, IO, OS, and lifecycle conformance passed the optimized-representation audit. `docs/KLua_Conformance_Gaps.md` classifies every remaining JVM/host difference and records no unowned v1 blocker. |
| M21 | In progress | The Java/Kotlin ABI, local Maven artifact contract, task-oriented guides, and compiled Java/Kotlin examples are locked and verified. Release readiness remains the primary frontier: qualify the release-candidate baseline and prepare final release packaging without publishing until explicitly authorized. |
| M22 | Deferred | JVM bytecode generation remains optional and must not begin before v1 foundations stabilize. |

Current capability includes:

- A complete source-to-internal-bytecode interpreter path with closures, upvalues, metatables, metamethod continuations, coroutines, to-be-closed variables, and structured runtime diagnostics.
- Java-friendly low-level and high-level embedding APIs, Kotlin conveniences, userdata bindings, bytecode package loading, resource limits, standard-library whitelisting, and production configuration.
- Initial pure-Kotlin base, math, string, table, UTF-8, package, coroutine, OS, IO, and debug libraries with substantial official-source-derived conformance coverage.
- Source-level debugging, live coroutine sessions, breakpoints, stepping, variables, expression evaluation, DAP protocol support, and CLI debugging.
- Deterministic Lua-object lifecycle support for `__gc`, weak tables, ephemerons, userdata uservalues, logical incremental scheduling, warnings, and state close.
- Benchmark baselines separating compile, VM, API, coroutine, metamethod, string, and application-kernel workloads.
- Checked public-module ABI baselines plus locally generated binary/source JARs and Maven POMs with verified coordinates, module names, and dependency scopes.
- Task-oriented embedding, sandbox/standard-library, debugging/DAP, performance, conformance, and release-contract documentation backed by compiled and executed Java/Kotlin examples.

Material remaining gaps include:

- Broader release-level executable packaging and standalone DAP hosting now that the embedding, artifact, and documentation contracts are fixed.
- Automated performance regression comparison, representative sandbox overhead controls, and published accepted v1 performance evidence.

The detailed, source-backed residual list belongs in `docs/KLua_Conformance_Gaps.md` and must not be duplicated here.

## Pre-v1 High-Performance Interpreter Track

The current runtime is a correct, evolvable interpreter model, not the final high-performance representation. Complete this track after the active VM semantic package and byte-string audit, and before the final M20 conformance matrix. This is a gated milestone sequence, not the live package queue; promote only the next eligible row into `Next Implementation Focus`. Each package must preserve public Java/Kotlin APIs and keep optimized storage internal to `klua-core`.

Use this order:

| Order | Package | Required outcome and gate |
| --- | --- | --- |
| 1 | Canonical baseline and hot-model design | Run the complete current JMH suite on JDK 17 with time and allocation evidence. Record the accepted pre-refactor baseline, dominant allocation sites, internal slot/table/call interfaces, debug materialization boundary, coroutine suspension representation, lifecycle root enumeration, and rollback plan. No representation change begins without this evidence. |
| 2 | Byte-oriented string storage | Apply the byte-string audit decision. Move `LuaString` to immutable byte-oriented storage with cached hash and add short/common-string interning only where profiles support it. Preserve source, API, dump/load, table-key, pattern, and UTF-8 semantics; focused byte-domain tests, allocation profiles, and the full suite must pass. |
| 3 | Tagged VM slots | Replace boxed hot-stack booleans, integers, and floats with tag and payload arrays. Heap objects remain for strings, tables, closures, functions, threads, and userdata; values are boxed only at API, debug, snapshot, constant-pool, and generic-container boundaries. Upvalues, open results, varargs, debug mutation, coroutine suspension, and lifecycle root enumeration must operate on the optimized slots. |
| 4 | Hybrid tables and shape/version tracking | Replace the general `LinkedHashMap<LuaValue, LuaValue>` hot path with an array part plus specialized hash part, then add table and metatable shape/version invalidation. Raw access, iteration order policy, numeric-key canonicalization, weak tables, `__mode`, `__gc`, length, and debug mutation must remain source-compatible. |
| 5 | Guarded inline caches and quickening | Add guarded caches or internal quickened opcodes for field, global, metamethod, and known-call access. Cache hits must validate table/metatable versions; mutations, debug APIs, registry replacement, and metatable changes must invalidate or miss safely and use one semantic fallback. |
| 6 | Stack-range call ABI | Remove avoidable `List<LuaValue>` argument/result snapshots from Lua-to-Lua and common host paths through stack ranges and fixed-arity entry points. Yield/resume, protected calls, tail calls, multiple returns, varargs, `<close>`, hooks, and debugger suspension remain exact. |
| 7 | Fast, debug, and budget dispatch modes | Split or hoist execution-observer, hook, line, count, breakpoint, and instruction-budget policy so disabled features meet their overhead gates while enabled modes retain exact event ordering and limits. Avoid duplicating opcode semantics between modes. |
| 8 | Performance and conformance closure | Rerun the canonical JMH suite and allocation profiles, compare every workload with the pre-refactor baseline, run `./gradlew test`, and perform the M20 conformance-matrix audit against the new representation. Any regression or semantic deviation must be fixed, explicitly accepted, or assigned before entering M21. |

Cross-cutting constraints:

- Do not expose tagged slots, table shapes, inline caches, quickened opcodes, or compiled-frame details through public APIs or the serialized bytecode contract.
- Keep one semantic fallback for metamethods and uncommon paths; caches and specialized operations must guard and fall back rather than duplicate semantics.
- Debug inspection and `debug.setlocal` must materialize and mutate live optimized values correctly.
- Coroutine suspension, protected continuations, lifecycle tracing, weak edges, and finalization must enumerate references held in optimized slots.
- Prefer migration seams that can later feed M22, but do not generate JVM bytecode before v1. M22 must separately define compiled frames, safepoints, guards, deoptimization, and source-level debug mapping.
- Every retained optimization needs matched time or allocation evidence and must satisfy the regression gates below.

## V1 Performance Qualification

M19 Pass 1 and the pre-v1 interpreter track provide optimization evidence. They do not by themselves define how fast the released artifact must be. M21 must rerun the canonical suite on the release candidate, establish the accepted release baseline, and prevent material regressions without treating noisy cross-machine microbenchmark numbers as an absolute SLA.

The qualification package must:

- Use JDK 17 as the canonical supported-runtime measurement environment. JDK 21 results may be reported separately.
- Record CPU, memory, OS, JVM build, JVM options, power policy, JMH version, forks, warmup, measurement settings, and benchmark commit.
- Refresh the complete current suite for compile, numeric execution, Lua calls, host calls, JVM-to-Lua calls, table access, metamethods, closures, methods, recursion, strings, coroutines, varargs/multiple returns, and an application-shaped table kernel.
- Add representative controls for debug disabled versus enabled, line/count hooks, breakpoint stepping, and instruction-budget disabled versus enabled behavior.
- Record both time and allocation where allocation is meaningful; keep startup/compile cost separate from steady-state execution.
- Store the accepted v1 results and comparison evidence in `docs/KLua_Benchmark_Baseline.md`, and document how to reproduce them.
- Keep Lua 5.5 and LuaJ comparisons informational unless a later milestone explicitly adopts a cross-runtime target.

Use these initial release gates on the canonical runner:

- Correctness and conformance tests must pass before performance evidence is accepted.
- A workload that is more than 10% slower than the accepted baseline, exceeds the combined measurement uncertainty, and reproduces in two matched runs is a release regression unless explicitly approved and re-baselined with rationale.
- An allocation increase above 5% on a stable allocation benchmark requires a fix or an explicit reviewed explanation before re-baselining.
- With debugging disabled, representative VM workloads should remain within 5% of the equivalent build/path without an execution observer or hook.
- Disabled instruction-budget policy should remain within 5% of the equivalent unrestricted execution path; enabled-policy overhead must be measured and published but has no universal hard limit.

These thresholds are regression gates, not promises that results from different hardware are directly comparable. Large performance work still requires profiling evidence and must not trade away Lua 5.5 correctness.

## Next Implementation Focus

Keep exactly one active package and no more than two immediately following packages. Replace closed rows instead of appending campaign history.

| Order | Status | Work package | Outcome and exit criteria | Expected final commit shape |
| --- | --- | --- | --- | --- |
| 1 | In progress | M21 release-candidate performance qualification | On the documented canonical JDK 17 environment, rerun the complete timing/allocation suite against the accepted closure checkpoint, apply the release gates and matched-rerun rule, record the accepted release-candidate baseline and reproduction commands, and leave external publication out of scope. | One to three coherent benchmark-control, regression-fix if needed, and accepted-baseline commits. |
| 2 | Next | M21 final packaging and release checklist | Close executable CLI/DAP-host packaging or explicitly disposition the standalone-host gap, verify license/metadata/source artifacts and clean-checkout commands, prepare release notes and the final version/tag checklist, and stop before remote publication, signing, tagging, or credential use without explicit authorization. | One to three coherent packaging, smoke-validation, and release-checklist commits. |

When package 1 closes, promote package 2 and select at most one new successor from M21's remaining release checklist. Do not keep closed campaign narratives or commit hashes in this table.

Do not close M20 until the performance track's representation changes have passed the final conformance matrix. M21 must still run the release-candidate performance qualification before artifacts are declared ready.

## Testing Requirements

Every implemented feature includes the relevant tests:

- Parser tests for syntax changes.
- Compiler bytecode or golden tests for lowering changes.
- VM behavior tests for runtime semantics.
- Error and traceback tests where source locations matter.
- Debug metadata tests where lines, locals, upvalues, or breakpoints change.
- Java API tests for `klua-api`.
- Kotlin API tests for `klua-kotlin`.
- Lua 5.5 conformance tests for language and standard-library behavior.
- Benchmarks only after correctness exists for a selected hot path.

Minimum repository verification is:

```text
./gradlew test
```

Focused tests may be used while iterating, but a package does not close without the broader verification required by its risk and exit criteria.

## Definition Of Done

A feature is done only when:

- It is implemented in the correct module.
- It does not leak internal types into public APIs.
- It has focused tests at the appropriate parser, compiler, VM, API, stdlib, or integration level.
- Failures include useful source context when applicable.
- The implementation follows file-size and responsibility rules.
- It implements Lua 5.5 semantics or documents an intentional, classified deviation.
- Behavior-sensitive changes were checked against the owning official Lua 5.5 source and helper chain.
- Relevant focused and broader verification passed.
- The live plan and gap matrix changed only if the completed work materially changed them.

## Engineering Defaults

- Prefer correctness and explicit semantics before speed.
- Prefer explicit runtime structures over clever Kotlin abstractions in VM hot paths.
- Avoid allocation per opcode and Kotlin lambdas inside the VM dispatch loop.
- Avoid exceptions for normal control flow.
- Keep debug checks disabled or isolated from fast execution when debugging is off.
- Use Java-friendly public API shapes: factories, explicit result types, Java functional interfaces, and no Kotlin-only public types in `klua-api`.
- Use reflection only at registration time for host bindings; runtime calls should use cached adapters.
- Use VM-managed coroutine state, not JVM threads.

## Maintaining This Document

- Keep this document aligned with committed repository capability while allowing an active row to describe clearly identified working-tree work as `In progress`.
- Keep durable rules here and detailed rationale in the architecture document.
- Keep the live plan to one active and at most two next packages.
- Replace closed rows; do not append completed campaigns, benchmark narratives, commit hashes, dates, or per-commit history.
- Update this document only when a package closes, priority changes, milestone reality changes, or new evidence materially changes scope.
- Do not modify this document merely because a code commit was created.
- Keep detailed source-backed residuals in `docs/KLua_Conformance_Gaps.md`.
- Keep benchmark measurements in `docs/KLua_Benchmark_Baseline.md`.
- Keep user-facing status in `README.md`.

## Assumptions

- The repository already contains working compiler, VM, API, Kotlin helper, userdata, stdlib, debug, tooling, benchmark, and test slices.
- This document is the operational execution brief; the current repository remains authoritative when prose becomes stale.
- "No backward capability" means no legacy project API preservation and no old Lua-version compatibility profile.
- Lua 5.5 is the only runtime target.
- Interpreter-first architecture remains required before any JVM bytecode compiler.
- Clean module boundaries are more important than minimizing module count.
- Performance work requires correctness and benchmark evidence.
