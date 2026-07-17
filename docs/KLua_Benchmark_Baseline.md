# KLua JMH Baseline

This file records comparable benchmark checkpoints used to select optimization work. Results are local evidence, not cross-machine performance claims.

The checkpoints below document the M19 performance work; they are not yet the accepted v1 release baseline. The 2026-07-17 checkpoint is the accepted pre-refactor comparison point for the high-performance interpreter track. M21 must rerun its complete suite on the release candidate, apply the regression policy in `docs/KLua_Codex_Goal.md`, and append one clearly labeled accepted v1 baseline with reproduction commands.

## 2026-07-17 JDK 17 Pre-Refactor Canonical Baseline

This checkpoint measures the commit containing this section and its benchmark controls; the last runtime commit before the measurement-only changes is `9c30a94b`. Every benchmark setup executes its workload and validates the result before measurement. The raw CSV and JFR recordings are generated under the ignored `klua-jmh/build/baseline` directory.

Environment:

- CPU: Intel Core i7-10700F, 8 cores / 16 logical processors, 2.90 GHz nominal
- Memory: 63.9 GB
- OS: Windows 10 Pro 10.0.19045, build 19045
- JVM: Azul Zulu OpenJDK 17.0.19+10-LTS, 64-bit Server VM
- Power policy: Windows High performance (`8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c`)
- JMH: 1.36, compiler blackholes, no explicit VM options
- Threads: 1; forks: 1
- Warmup: 3 iterations × 500 ms
- Measurement: 5 iterations × 500 ms
- Mode: average time, microseconds per operation

Reproduction commands from the repository root with `JAVA_HOME` set to a JDK 17 installation:

```text
./gradlew :klua-jmh:jmhJar
java -jar klua-jmh/build/libs/klua-jmh-0.1.0-SNAPSHOT-jmh.jar -rf csv -rff klua-jmh/build/baseline/jdk17-timing.csv
java -jar klua-jmh/build/libs/klua-jmh-0.1.0-SNAPSHOT-jmh.jar -prof gc -rf csv -rff klua-jmh/build/baseline/jdk17-gc.csv
```

Results:

| Benchmark | Score (µs/op) | 99.9% error | Allocation (B/op) |
| --- | ---: | ---: | ---: |
| Compile numeric loop | 8.629 | ±1.704 | 21,304.013 |
| Debug off | 487.886 | ±53.220 | 481,256.628 |
| Debug on, no observer | 494.474 | ±56.191 | 481,259.790 |
| Breakpoint observer, no hit | 1,061.336 | ±149.789 | 961,446.202 |
| Breakpoint hit, step, continue | 1,372.142 | ±1,069.814 | 973,347.349 |
| Count hook, interval 100 | 753.834 | ±52.961 | 990,192.282 |
| Line hook | 4,677.316 | ±1,038.778 | 18,885,118.576 |
| Instruction budget off | 509.429 | ±57.104 | 481,259.197 |
| Instruction budget on | 509.039 | ±50.088 | 481,181.977 |
| Closure counter, 10,000 calls | 1,864.081 | ±83.538 | 1,683,652.298 |
| Coroutine, 10,000 yield/resume cycles | 20,798.207 | ±7,228.186 | 29,679,797.072 |
| Entity-update kernel, 1,000 entities × 10 steps | 7,877.180 | ±11,716.188 | 3,272,176.261 |
| Host calls, 10,000 | 4,010.901 | ±1,672.248 | 9,998,210.598 |
| `__index` calls, 10,000 | 3,503.508 | ±1,434.616 | 1,931,914.228 |
| JVM-to-Lua calls, 10,000 | 5,382.518 | ±2,836.468 | 10,586,158.277 |
| Lua calls, 10,000 | 1,965.408 | ±646.866 | 1,762,277.698 |
| Method calls, 10,000 | 5,615.640 | ±3,332.897 | 1,843,551.692 |
| Recursive `fib(20)` | 6,592.674 | ±2,037.466 | 4,993,274.400 |
| Growing string concatenation, 1,000 appends | 6,039.462 | ±2,538.956 | 28,685,081.935 |
| Table writes and reads, 10,000 each | 3,020.783 | ±1,816.435 | 1,253,580.710 |
| Vararg/multiple return, 10,000 calls | 4,168.960 | ±2,030.752 | 1,922,692.493 |
| VM numeric loop, 10,000 iterations | 865.837 | ±687.002 | 961,189.502 |

The matched disabled-policy gates pass on this machine: debug-on without an observer is 1.35% above debug-off, and enabled instruction accounting is 0.08% below the otherwise identical unlimited production configuration. Both differences are inside the 5% gate and their confidence intervals overlap. Enabled observer, hook, breakpoint, and stepping figures are published costs, not regression gates. Several application-workload timing intervals are wide, so normalized allocation and repeatable matched controls are the primary selection evidence.

### Allocation-site evidence

JFR allocation sampling used the same JDK and JMH iteration settings on six representative workloads. Sample weights are directional rather than exact byte totals; the GC-profiler B/op values above are the quantitative baseline.

- The numeric loop selects boxed `LuaInteger` creation in `LuaVm.Arithmetic.integerArithmetic`, `runFrameAndPopOnCompletion`, and `advanceForLoop`.
- Coroutine yield/resume selects `callCoreContextFunction`, `LuaState.callHostFunction`, `KLuaCoreCallContext`, `VmNativeCallContext`, `LuaStack.slice`, result lists, and their backing arrays.
- Host calls select `LuaState.callHostFunction` and the same API/core adapters; public/core integer wrappers and JVM `Long` values are also visible.
- The entity kernel selects `LuaVm.lifecycleRoots`, `LuaTable.rawEntries`, `ReachabilityTrace.visit`, `LinkedHashMap` entries, and boxed integers. This ties table storage and lifecycle enumeration together and rules out changing either in isolation.
- Growing concatenation is dominated by byte arrays and JVM strings from `luaRawBytes`; this directly supports the byte-oriented `LuaString` package already selected next.
- A no-hit breakpoint observer selects `BreakpointKey` creation in `BreakpointManager.breakpointAt` plus the underlying numeric boxing. Debug-frame conversion remains lazy and is absent from the no-hit hot samples.

### Hot-model migration contract

The pre-refactor model and its required seams are:

| Boundary | Current hot representation | Migration seam and invariant |
| --- | --- | --- |
| Values and VM slots | `LuaBoolean`, `LuaInteger`, and `LuaFloat` objects in `LuaStack.values: MutableList<LuaValue>` | Introduce one internal slot abstraction with tag/payload/reference access, range copy, boxing, debug mutation, and heap-reference visitation. Keep `LuaValue`, public values, constants, and serialized bytecode at explicit materialization boundaries until their owning package changes them. |
| Strings | `LuaString.value: String` with lazily cached reconstructed bytes and hash | Store immutable bytes and cached byte hash directly. Text decoding/encoding belongs only at named source and host boundaries; arbitrary-byte paths never round-trip through JVM text. This is the first representation package. |
| Tables | `LinkedHashMap<LuaValue, LuaValue>`; `rawEntries()` copies with `toMap()` | Put raw get/set/next/length/entry visitation behind table-owned operations before adding array/hash parts. Preserve numeric-key canonicalization, iteration policy, weak modes, ephemerons, metatable behavior, and lifecycle edge classification. |
| Calls | `callValue`, native contexts, continuations, varargs, and open results pass `List<LuaValue>` snapshots | Later add stack-range and fixed-arity internal entries while retaining one semantic fallback. Box only at native/public/debug boundaries and preserve yield, protected-call, tail-call, multiple-return, and `<close>` ordering. |
| Debug and budgets | The dispatch loop performs a nullable observer check, instruction-budget check, and nullable hook check for each instruction; frames are materialized lazily | Keep opcode semantics single-sourced. A later dispatch-mode package may hoist policy checks, but observer/hook ordering, exact budget exhaustion, live-local mutation, and suspension PCs remain unchanged. The controls above become its matched gates. |
| Coroutines | `LuaThread` retains `CallFrame`s, list-backed stacks, pending calls/results, continuations, open-result ranges, and upvalues | Suspension must retain the internal slot storage without eager boxing. Resume, close, debug suspension, protected continuations, varargs, and yielded/result values must cross one audited transfer seam. |
| Lifecycle | `LuaVm.lifecycleRoots()` builds lists; tracing visits boxed stack/frame/upvalue values and copied table entries | Replace list/snapshot root collection with visitor-style reference enumeration before optimized slots or tables can land. Only heap references are traced from tagged slots; weak keys/values and finalization order keep their existing rules. |

Each representation package must be independently revertible. It may change only internal `klua-core` storage plus the explicit conversion boundaries it owns; public Java/Kotlin signatures, `KLuaCoreValue`, debug views, and the bytecode format remain stable. Its focused semantic matrix, `./gradlew test`, the JMH build, matched allocation measurements, and relevant performance gates must pass before the next representation package begins. A failed gate rolls back or reverts that package rather than layering compensating changes across later packages.

The byte-oriented string package exits only when source literals, API byte/text entry points, table keys, concatenation, patterns, UTF-8, IO, debug display, bytecode dump/load, malformed bytes, embedded NULs, and lifecycle reachability preserve the audited behavior in `docs/KLua_Conformance_Gaps.md`; the full suite passes; and matched concatenation/key-heavy allocation evidence is recorded. Interning is optional and requires a separate profile-supported win.

## 2026-07-17 Byte-Oriented String Checkpoint

The first representation package replaces `LuaString`'s JVM-text storage with immutable owned bytes, an eagerly available byte count, cached byte hash, lazy boundary text projection, bytewise equality and ordering, and direct byte concatenation. Lexer string tokens and AST/compiler constants carry `LuaString` values without a surrogate-marker semantic round trip; bytecode constants encode and decode the owned bytes directly. `LuaState.pushBytes`/`toBytes` and `LuaCallContext.toBytes` provide defensive exact-byte host paths, and core-backed native calls can supply original `LuaString` bytes to string and UTF-8 library consumers without materializing text first. Public text values, debug/error display, `KLuaCoreValue`, and the serialized bytecode contract remain unchanged.

The matched JDK 17 GC-profiler run measured:

| Benchmark | Metric | Canonical baseline | Byte storage | Delta |
| --- | --- | ---: | ---: | ---: |
| 1,000 growing concatenations | Average time | 6,039.462 µs/op | 510.062 µs/op | -91.6% |
| 1,000 growing concatenations | Allocation | 28,685,081.935 B/op | 609,152.788 B/op | -97.9% |
| 10,000 method/table-key calls | Allocation | 1,843,551.692 B/op | 1,843,538.035 B/op | effectively unchanged |

The complete 22-workload timing screen found no supported unrelated regression under the combined-uncertainty policy. Its first count-hook point was above the canonical point, so the hot event labels were changed from per-event `LuaString` construction to shared immutable event strings. The required focused rerun measured 854.389 ±203.201 µs/op versus the canonical 753.834 ±52.961 µs/op; the intervals overlap and do not support a regression. The full Gradle suite and JMH build pass.

No interning cache was added. The profile attributes the win to direct byte storage/concatenation, while the key-heavy control is allocation-neutral; there is no measured benefit that would justify interning policy, retention, and identity risks.

## 2026-07-17 Tagged VM Slot Checkpoint

The second representation package replaces boxed primitive stack entries with internal byte tags, `Long` payloads, and lazily allocated heap-reference arrays. Stack copies, open Lua-call results, upvalues, and frame-owned varargs transfer tag and payload data without materializing `LuaValue` wrappers. Varargs share their frame's backing storage and relocate as an isolated tail segment when an open result grows the register range. Debug and public snapshots still materialize ordinary values, while lifecycle tracing visits only heap references held by stack, vararg, and upvalue slots.

The representation follows Lua 5.5's `TValue`/`StackValue` model in `lobject.h`, open-upvalue ownership and closing in `lfunc.c`, tagged VM copies and numeric operations in `lvm.c`, and stack growth in `ldo.c`. Focused tests cover tag/payload copies, raw float bits, open-upvalue mutation and closing, stale-reference removal, stack growth across the vararg tail, exact open-call forwarding, and heap-root visitation.

Matched JDK 17 measurements against the accepted pre-refactor checkpoint are:

| Benchmark | Metric | Canonical baseline | Tagged slots | Delta |
| --- | --- | ---: | ---: | ---: |
| VM numeric loop | Average time | 865.837 µs/op | 749.342 µs/op | -13.5% |
| VM numeric loop | Allocation | 961,189.502 B/op | 1,229.636 B/op | -99.87% |
| Debug-disabled control | Allocation | 481,256.628 B/op | 1,168.588 B/op | -99.76% |
| Closure counter | Allocation | 1,683,652.298 B/op | 1,363,728.099 B/op | -19.0% |
| Vararg/multiple return | Allocation | 1,922,692.493 B/op | 2,002,568.490 B/op | +4.2% |
| Table read/write control | Allocation | 1,253,580.710 B/op | 1,253,408.427 B/op | effectively unchanged |

The vararg allocation increase remains below the 5% gate and retains tagged storage rather than falling back to boxed lists. The full 22-workload timing screen found no regression supported by two matched runs under the combined-uncertainty rule. A focused table rerun measured 2,925.485 ±1,302.353 µs/op, consistent with the canonical 3,020.783 ±1,816.435 µs/op point, with allocation unchanged. The complete Gradle suite and JMH build pass.

## 2026-07-15 Runtime Workload Baseline

Environment:

- CPU: Intel Core i7-10700F at 2.90 GHz
- Memory: 63.9 GB
- OS: Windows 10 Pro 10.0.19045
- JVM: OpenJDK 21.0.11, 64-bit Server VM
- JMH: 1.36, compiler blackholes
- VM options: none
- Threads: 1
- Forks: 1
- Warmup: 3 iterations × 500 ms
- Measurement: 5 iterations × 500 ms
- Mode: average time, microseconds per operation

Command:

```text
java -jar klua-jmh/build/libs/klua-jmh-0.1.0-SNAPSHOT-jmh.jar
```

Results:

| Benchmark | Score (µs/op) | 99.9% error |
| --- | ---: | ---: |
| Compile numeric loop | 8.202 | ±1.461 |
| Execute numeric loop | 1,017.120 | ±57.892 |
| Execute 10,000 Lua calls | 2,252.124 | ±158.754 |
| Execute 10,000 host calls | 12,843.233 | ±7,488.727 |
| Execute 10,000 table writes and reads | 2,626.994 | ±136.816 |

The host-call workload is about 5.7× the Lua-call workload and 12.6× the numeric-loop control in this run. Its wider confidence interval calls for profiling and allocation evidence before changing code, but the gap is large enough to select the Lua-to-JVM argument/result conversion and dispatch path as the first optimization candidate.

The next performance package must:

1. profile the host-call workload and identify the dominant allocation or dispatch sites;
2. add any narrower control benchmark needed to test that hypothesis;
3. make one maintainable optimization;
4. rerun this full suite under the same environment and report before/after deltas;
5. retain the full correctness suite with no semantic changes.

## 2026-07-15 Host-Call Frame Materialization Checkpoint

JMH stack profiling identified eager construction of Lua debug-frame snapshots as the dominant host-call cost. Ordinary callbacks paid for `LuaVm.luaStackFrames`, active-local capture, core-frame conversion, and API-frame conversion even when they only read an argument and returned a value.

Lua 5.5's `ldo.c` `precallC`/`luaD_precall` path establishes the C call and invokes it directly; debug information is queried separately rather than copied into an eager snapshot for every C call. KLua now follows that behavioral boundary: VM, core, and API frame views are materialized and memoized only when a callback accesses `luaFrames`. A focused API regression test confirms that on-access frames still expose the current source line and live local values.

The JMH GC profiler on the 10,000-call host workload measured:

| Metric | Before | After | Delta |
| --- | ---: | ---: | ---: |
| Average time | 13,475.187 µs/op | 5,414.179 µs/op | -59.8% |
| Allocation | 83,913,482.710 B/op | 29,039,596.322 B/op | -65.4% |
| Allocation per host call | 8,391 B | 2,904 B | -5,487 B |

The Lua-call control retained effectively identical allocation: 4,562,738.082 B/op before and 4,562,737.791 B/op after.

The full five-workload suite was then rerun with the baseline command and environment:

| Benchmark | Baseline (µs/op) | Checkpoint (µs/op) | Change |
| --- | ---: | ---: | ---: |
| Compile numeric loop | 8.202 | 8.163 | -0.5% |
| Execute numeric loop | 1,017.120 | 982.525 | -3.4% |
| Execute 10,000 Lua calls | 2,252.124 | 2,213.879 | -1.7% |
| Execute 10,000 host calls | 12,843.233 | 5,710.206 | -55.5% |
| Execute 10,000 table writes and reads | 2,626.994 | 2,835.344 | +7.9% |

The non-host changes are treated as run variance; the table checkpoint in particular had a wide 99.9% error of ±925.322 µs/op. The host improvement is independently supported by the allocation reduction, and the complete Gradle test suite passes. The next M19 package should profile table access and choose one evidence-backed representation or dispatch improvement without mixing in unrelated VM changes.

## 2026-07-15 Table Metamethod Traversal Checkpoint

Stack and GC profiling of the combined 10,000-write/10,000-read workload found that ordinary raw table accesses allocated cycle-detection sets before determining whether an `__index` or `__newindex` chain existed. The workload allocated 3,093,994.067 B/op, and stack samples included `HashSet.add` alongside hash-table lookup and growth.

Lua 5.5's `lvm.c` fast table opcodes attempt raw access first and enter `luaV_finishget` or `luaV_finishset` only for an unfinished access. KLua now follows that split: present-key reads/writes and missing-key accesses without a metamethod avoid traversal-state allocation, while table-valued metamethod chains retain the existing cycle checks. Existing core and standard-library tests cover raw precedence, integral-float key canonicalization, chained table delegates, and cycle errors.

The matched JMH GC-profiler run measured:

| Metric | Before | After | Delta |
| --- | ---: | ---: | ---: |
| Average time | 2,594.550 µs/op | 2,327.755 µs/op | -10.3% |
| Allocation | 3,093,994.067 B/op | 1,253,993.849 B/op | -59.5% |

The full suite after the change measured:

| Benchmark | Score (µs/op) | 99.9% error |
| --- | ---: | ---: |
| Compile numeric loop | 7.890 | ±0.945 |
| Execute numeric loop | 1,003.856 | ±56.477 |
| Execute 10,000 Lua calls | 2,316.229 | ±148.557 |
| Execute 10,000 host calls | 5,345.019 | ±1,493.626 |
| Execute 10,000 table writes and reads | 2,365.362 | ±405.078 |

The table score is 10.0% below the initial 2,626.994 µs/op baseline, and the host-call improvement remains intact. The complete Gradle test suite passes. With the two baseline-selected hotspots improved, the next M19 package should expand coverage with metamethod and JVM-to-Lua call controls before selecting another optimization.

## 2026-07-15 Metamethod and JVM-to-Lua Coverage

The runtime workload suite now includes two verified controls:

- 10,000 function-valued `__index` calls from one compiled Lua chunk;
- 10,000 calls from the low-level JVM API into one compiled Lua closure retained on the `LuaState` stack.

Both setup paths execute the workload and reject an unexpected result before JMH measurement. The metamethod control opens only the base library needed for `setmetatable`; neither control includes parse or compile time in a measured operation.

The expanded seven-workload suite measured:

| Benchmark | Score (µs/op) | 99.9% error |
| --- | ---: | ---: |
| Compile numeric loop | 8.238 | ±0.946 |
| Execute numeric loop | 1,237.312 | ±313.188 |
| Execute 10,000 Lua calls | 2,813.719 | ±979.873 |
| Execute 10,000 host calls | 5,552.540 | ±2,243.971 |
| Execute 10,000 `__index` metamethod calls | 4,516.945 | ±666.206 |
| Execute 10,000 JVM-to-Lua calls | 4,461.456 | ±1,409.221 |
| Execute 10,000 table writes and reads | 3,897.642 | ±1,166.221 |

This full run was noisier than the targeted checkpoints, so it establishes coverage rather than a regression claim. A matched GC-profiler run provides the selection evidence:

| Benchmark | Average time | Allocation |
| --- | ---: | ---: |
| 10,000 `__index` metamethod calls | 4,578.365 µs/op | 12,009,101.652 B/op |
| 10,000 JVM-to-Lua calls | 4,044.512 µs/op | 21,113,883.216 B/op |

The JVM-to-Lua control has the larger allocation cost at roughly 2,111 B per call. It is the next bounded profiling target; a code change requires stack or allocation-site evidence and a matched control rerun.

## 2026-07-15 JVM-to-Lua Conversion-Cache Checkpoint

JFR allocation-by-site profiling attributed about 60% of JVM-to-Lua allocation pressure to `IdentityHashMap.init`, led by core argument conversion and followed by core/API result conversion. Each scalar crossing created empty graph-identity caches even though those caches are only required when converting tables. Lua 5.5's `lapi.c` primitive push operations place scalar values directly on the stack; they do not initialize table-graph traversal state.

Core public/VM conversion and API core/stack conversion now initialize an identity cache only when the value being converted is a table. Recursive table conversion continues to share one cache, preserving aliases and cycles. A focused API test round-trips a self-referential table through both conversion directions and verifies its identity from Lua.

The matched GC-profiler results were:

| Benchmark | Metric | Before | After | Delta |
| --- | --- | ---: | ---: | ---: |
| 10,000 JVM-to-Lua calls | Average time | 4,044.512 µs/op | 3,396.782 µs/op | -16.0% |
| 10,000 JVM-to-Lua calls | Allocation | 21,113,883.216 B/op | 11,753,882.719 B/op | -44.3% |
| 10,000 `__index` metamethod calls | Allocation | 12,009,101.652 B/op | 12,008,163.902 B/op | effectively unchanged |

The full seven-workload suite after the change measured:

| Benchmark | Score (µs/op) | 99.9% error |
| --- | ---: | ---: |
| Compile numeric loop | 7.689 | ±0.667 |
| Execute numeric loop | 978.447 | ±77.953 |
| Execute 10,000 Lua calls | 3,082.074 | ±2,166.965 |
| Execute 10,000 host calls | 5,216.088 | ±397.044 |
| Execute 10,000 `__index` metamethod calls | 4,563.055 | ±997.662 |
| Execute 10,000 JVM-to-Lua calls | 3,837.488 | ±1,120.497 |
| Execute 10,000 table writes and reads | 2,379.362 | ±135.692 |

The full correctness suite passes. The next M19 package should continue the benchmark matrix with string concatenation and coroutine yield/resume controls before selecting another optimization target.

## 2026-07-15 String and Coroutine Coverage

The runtime workload suite now adds two setup-verified controls:

- 1,000 repeated `value = value .. "x"` operations, returning the final byte length;
- 10,000 `coroutine.yield`/`coroutine.resume` transfers plus the completion resume, returning a checksum of the last yielded and final values.

The concat control exercises the growing-string case governed by Lua 5.5's `lvm.c` `luaV_concat`. The coroutine control uses the standard-library create/yield/resume path modeled by `lcorolib.c` `auxresume`, `luaB_coresume`, and `luaB_yield`, including the leading success boolean and yielded/result transfer.

The matched GC-profiler checkpoint measured:

| Benchmark | Average time | Allocation | Normalized unit |
| --- | ---: | ---: | ---: |
| 1,000 growing string concatenations | 4,251.075 µs/op | 16,633,514.487 B/op | 16,634 B/concat |
| 10,000 coroutine yield/resume cycles | 24,746.339 µs/op | 89,285,731.446 B/op | 8,929 B/cycle |

The expanded nine-workload suite measured:

| Benchmark | Score (µs/op) | 99.9% error |
| --- | ---: | ---: |
| Compile numeric loop | 7.625 | ±0.304 |
| Execute numeric loop | 1,052.539 | ±156.414 |
| Execute 10,000 Lua calls | 2,499.627 | ±204.112 |
| Execute 10,000 host calls | 6,337.259 | ±1,138.405 |
| Execute 10,000 `__index` metamethod calls | 4,449.858 | ±307.897 |
| Execute 10,000 JVM-to-Lua calls | 3,342.372 | ±236.741 |
| Execute 10,000 table writes and reads | 2,970.459 | ±700.930 |
| Execute 1,000 growing string concatenations | 4,475.131 | ±1,347.879 |
| Execute 10,000 coroutine yield/resume cycles | 26,011.328 | ±1,975.760 |

The complete Gradle test suite passes. Growing concatenation intentionally copies an increasing result and therefore needs a differently shaped control before any representation conclusion. Coroutine transfer has the largest total measured allocation and a directly comparable per-cycle cost, so coroutine yield/resume is the next bounded profiling target.

## 2026-07-15 Coroutine Transfer-Cache Checkpoint

Stack profiling placed host/API adaptation on the coroutine hot path. JFR allocation-by-site profiling then attributed about 45% of allocation pressure to `IdentityHashMap.init`, primarily from core/native argument synchronization, return/yield conversion, and API coroutine resume. These identity maps preserve aliases and cycles when table graphs cross a boundary, but scalar `coroutine.resume` success flags, yielded integers, and resume arguments do not require them.

KLua now creates the specialized synchronization caches only when paired table arguments or table resume arguments exist. Multiple table arguments still share one cache, and returned/yielded table graphs still allocate a cache on demand. A focused standard-library regression passes self-referential tables into a coroutine, yields one back, resumes with another, and verifies identity and cycles on both transfers. This preserves the `lcorolib.c` `auxresume` value-transfer semantics while removing scalar-only graph state.

The matched GC-profiler checkpoint measured:

| Benchmark | Metric | Before | After | Delta |
| --- | --- | ---: | ---: | ---: |
| 10,000 coroutine yield/resume cycles | Average time | 24,746.339 µs/op | 23,918.230 µs/op | -3.3% |
| 10,000 coroutine yield/resume cycles | Allocation | 89,285,731.446 B/op | 57,602,250.770 B/op | -35.5% |
| 1,000 growing string concatenations | Allocation | 16,633,514.487 B/op | 16,633,331.532 B/op | effectively unchanged |

The full nine-workload suite after the change measured:

| Benchmark | Score (µs/op) | 99.9% error |
| --- | ---: | ---: |
| Compile numeric loop | 7.963 | ±1.062 |
| Execute numeric loop | 1,024.128 | ±96.112 |
| Execute 10,000 Lua calls | 2,499.410 | ±335.054 |
| Execute 10,000 host calls | 5,637.406 | ±2,569.825 |
| Execute 10,000 `__index` metamethod calls | 4,598.839 | ±1,201.256 |
| Execute 10,000 JVM-to-Lua calls | 3,124.115 | ±275.988 |
| Execute 10,000 table writes and reads | 2,510.404 | ±665.893 |
| Execute 1,000 growing string concatenations | 4,055.632 | ±190.823 |
| Execute 10,000 coroutine yield/resume cycles | 21,867.310 | ±2,244.246 |

The full-suite coroutine score is 15.9% below its 26,011.328 µs/op coverage baseline, and the complete Gradle test suite passes. The next M19 package should add closure-counter, method-call, and representative application-kernel controls before selecting another optimization.

## 2026-07-15 Closure, Method, and Recursive-Kernel Coverage

The runtime workload suite now adds three setup-verified, execution-only controls:

- a captured counter closure performing 10,000 calls and upvalue updates;
- a table method performing 10,000 colon calls, receiver-field reads, and receiver-field writes;
- recursive `fib(20)`, returning 6,765 as a compact application kernel with a branching Lua call tree.

These controls cover Lua 5.5's `OP_GETUPVAL`/`OP_SETUPVAL` closure path and the `OP_SELF` receiver placement described by `lvm.c`; compilation remains outside the measured operations. The focused GC-profiler run measured:

| Benchmark | Average time | Allocation | Normalized observation |
| --- | ---: | ---: | ---: |
| 10,000 closure-counter calls | 5,507.281 µs/op | 4,242,278.300 B/op | 424 B/call |
| 10,000 method calls | 16,814.452 µs/op | 30,642,653.284 B/op | 3,064 B/call |
| Recursive `fib(20)` | 9,186.279 µs/op | 11,122,447.351 B/op | complete call tree |

The expanded twelve-workload suite measured:

| Benchmark | Score (µs/op) | 99.9% error |
| --- | ---: | ---: |
| Compile numeric loop | 17.638 | ±10.761 |
| Execute numeric loop | 1,045.381 | ±51.643 |
| Execute 10,000 Lua calls | 3,645.401 | ±1,533.597 |
| Execute 10,000 closure-counter calls | 3,418.154 | ±2,799.833 |
| Execute 10,000 host calls | 6,646.840 | ±3,627.715 |
| Execute 10,000 `__index` metamethod calls | 7,829.313 | ±5,259.406 |
| Execute 10,000 JVM-to-Lua calls | 4,929.565 | ±2,078.112 |
| Execute 10,000 method calls | 13,690.815 | ±2,761.808 |
| Execute recursive `fib(20)` | 7,273.834 | ±1,215.923 |
| Execute 10,000 table writes and reads | 2,397.936 | ±352.679 |
| Execute 1,000 growing string concatenations | 4,169.263 | ±488.289 |
| Execute 10,000 coroutine yield/resume cycles | 25,956.554 | ±3,915.871 |

The full run remains a local selection aid rather than a regression claim, especially where confidence intervals are wide. Method dispatch is the next bounded profiling target because it is the slowest new control and allocates roughly 7.2× as many bytes per 10,000 operations as the closure-counter control. A code change requires stack or allocation-site evidence and a matched GC-profiler rerun. The complete Gradle test suite passes.

## 2026-07-15 Lua-String Hash Checkpoint

Stack profiling of the method control attributed 19.0% of runnable samples to `luaRawBytes`, with additional `HashMap` lookup/update samples absent from the plain Lua-call and closure-counter controls. JFR allocation sampling then identified byte arrays created by `LuaString.hashCode` and `LuaString.equals` during receiver-field `rawGet`/`rawSet` as the dominant allocation site. KLua was reconstructing a string's Lua byte sequence for every JVM hash-table probe.

Lua 5.5 stores byte contents and a hash in each `TString`; `lstring.c` computes short-string hashes when interning and `luaS_hashlongstr` caches a long string's hash on first use. KLua now follows that stable-representation rule by lazily memoizing each immutable `LuaString`'s raw bytes and hash. Lazy materialization matters for transient strings such as growing concatenation results that are never table keys. A focused core regression verifies that two distinct JVM strings encoding the same invalid UTF-8 Lua byte sequence retain bytewise equality, equal hashes, and interchangeable table-key behavior.

The matched GC-profiler checkpoint measured:

| Benchmark | Metric | Before | After | Delta |
| --- | --- | ---: | ---: | ---: |
| 10,000 method calls | Average time | 16,814.452 µs/op | 5,883.130 µs/op | -65.0% |
| 10,000 method calls | Allocation | 30,642,653.284 B/op | 4,641,884.703 B/op | -84.9% |
| 10,000 closure-counter calls | Allocation | 4,242,278.300 B/op | 4,242,289.931 B/op | effectively unchanged |
| 1,000 growing string concatenations | Allocation | 16,633,331.532 B/op | 16,649,330.899 B/op | +0.1% |

The full twelve-workload suite after the change measured:

| Benchmark | Score (µs/op) | 99.9% error |
| --- | ---: | ---: |
| Compile numeric loop | 7.723 | ±0.889 |
| Execute numeric loop | 998.189 | ±36.138 |
| Execute 10,000 Lua calls | 2,388.556 | ±262.762 |
| Execute 10,000 closure-counter calls | 2,446.613 | ±625.095 |
| Execute 10,000 host calls | 4,339.472 | ±1,044.964 |
| Execute 10,000 `__index` metamethod calls | 3,789.281 | ±1,613.509 |
| Execute 10,000 JVM-to-Lua calls | 3,775.159 | ±2,115.900 |
| Execute 10,000 method calls | 4,906.335 | ±593.014 |
| Execute recursive `fib(20)` | 6,486.125 | ±855.812 |
| Execute 10,000 table writes and reads | 2,394.570 | ±247.126 |
| Execute 1,000 growing string concatenations | 3,898.804 | ±1,638.532 |
| Execute 10,000 coroutine yield/resume cycles | 20,698.180 | ±3,602.873 |

The full-suite method score is 64.2% below its 13,690.815 µs/op coverage baseline. Growing concatenation remains within its established range because non-key strings do not materialize the cache. The complete Gradle test suite passes. Coroutine yield/resume remains the largest runtime workload after its first optimization and is the next residual profiling target.

## 2026-07-15 Native-Call Context Checkpoint

After the Lua-string hash change, a new matched GC baseline placed the coroutine workload at 27,119,173.255 B/op and 16,753.175 µs/op. JFR allocation sampling no longer selected string bytes or identity maps; the largest KLua-attributed groups were the layered host/native adapters in `LuaState.callHostFunction`, `LuaVm.nativeCallContext`, and their core/API continuation conversions. The VM-side native context created a captured frame-cache closure plus callbacks for frames, local mutation, hook mutation, and hook lookup on every native call, although ordinary coroutine operations do not inspect those debug services.

Lua 5.5's `ldo.c` `precallC` path exposes the active call state to a C function without constructing per-service callbacks. KLua now uses one VM-backed native-call context object that accesses the active VM directly. Lua frame materialization remains lazy and memoized; local mutation, hook access, and yieldability retain the same context methods. Existing API, debug-library, coroutine, and debugger tests exercise these services.

The matched GC-profiler checkpoint measured:

| Benchmark | Metric | Before | After | Delta |
| --- | --- | ---: | ---: | ---: |
| 10,000 coroutine yield/resume cycles | Average time | 16,753.175 µs/op | 14,809.104 µs/op | -11.6% |
| 10,000 coroutine yield/resume cycles | Allocation | 27,119,173.255 B/op | 25,118,948.376 B/op | -7.4% |
| 10,000 host calls | Allocation | 9,518,434.574 B/op | 8,158,434.655 B/op | -14.3% |

The full twelve-workload suite after the change measured:

| Benchmark | Score (µs/op) | 99.9% error |
| --- | ---: | ---: |
| Compile numeric loop | 7.906 | ±0.918 |
| Execute numeric loop | 1,095.518 | ±249.661 |
| Execute 10,000 Lua calls | 2,413.860 | ±272.444 |
| Execute 10,000 closure-counter calls | 2,498.739 | ±498.721 |
| Execute 10,000 host calls | 2,884.707 | ±452.812 |
| Execute 10,000 `__index` metamethod calls | 2,969.671 | ±287.213 |
| Execute 10,000 JVM-to-Lua calls | 3,656.762 | ±872.227 |
| Execute 10,000 method calls | 5,260.093 | ±1,373.698 |
| Execute recursive `fib(20)` | 6,393.035 | ±651.734 |
| Execute 10,000 table writes and reads | 2,626.674 | ±949.929 |
| Execute 1,000 growing string concatenations | 3,669.386 | ±1,256.241 |
| Execute 10,000 coroutine yield/resume cycles | 15,635.268 | ±1,539.794 |

The full-suite coroutine score is 24.5% below its 20,698.180 µs/op post-string-cache checkpoint. The complete Gradle test suite passes. The remaining API-side host context and continuation adapters require a fresh post-change profile before another optimization.

## 2026-07-15 API Host-Context Checkpoint

Fresh JFR profiles after VM native-context consolidation compared coroutine yield/resume with plain host calls. Both selected `LuaState.callHostFunction` and core/API context adaptation; plain host calls in particular were dominated by `ArrayList`, backing-array, `DefaultLuaCallContext`, and per-invocation adapter-lambda allocation. Both registered globals and converted host functions already receive one `KLuaCoreCallContext`, but KLua unpacked that object into separate frame, local, hook-set, and hook-get lambdas and then wrapped those callbacks in another API context.

KLua now passes the core call context directly into one specialized API call context. Frame conversion is still lazy and memoized, and local mutation, debug-hook access, yieldability, table synchronization, native-frame errors, and yield/result conversion retain their previous paths. This continues the `ldo.c` `precallC` principle of exposing active call state without constructing independent service closures.

The matched GC-profiler checkpoint measured:

| Benchmark | Before allocation | After allocation | Delta |
| --- | ---: | ---: | ---: |
| 10,000 coroutine yield/resume cycles | 25,118,948.376 B/op | 23,358,773.389 B/op | -7.0% |
| 10,000 host calls | 8,158,434.655 B/op | 7,278,434.446 B/op | -10.8% |

Focused timing was noisy and does not support a timing claim: coroutine averages were 14,809.104 µs/op before and 16,603.648 µs/op after, with the latter carrying a ±4,491.583 µs/op 99.9% error. The deterministic normalized allocation delta is the acceptance evidence.

The full twelve-workload regression screen measured:

| Benchmark | Score (µs/op) | 99.9% error |
| --- | ---: | ---: |
| Compile numeric loop | 8.196 | ±1.339 |
| Execute numeric loop | 1,028.727 | ±174.571 |
| Execute 10,000 Lua calls | 2,385.166 | ±322.362 |
| Execute 10,000 closure-counter calls | 2,389.848 | ±893.124 |
| Execute 10,000 host calls | 3,061.394 | ±806.747 |
| Execute 10,000 `__index` metamethod calls | 2,736.694 | ±270.192 |
| Execute 10,000 JVM-to-Lua calls | 3,381.129 | ±786.472 |
| Execute 10,000 method calls | 4,944.629 | ±301.308 |
| Execute recursive `fib(20)` | 7,095.810 | ±3,865.169 |
| Execute 10,000 table writes and reads | 2,359.413 | ±336.230 |
| Execute 1,000 growing string concatenations | 3,672.558 | ±876.935 |
| Execute 10,000 coroutine yield/resume cycles | 18,167.585 | ±8,093.663 |

No workload shows a supported regression, and the complete Gradle test suite passes. Core/public result-list and continuation conversion remain visible in the profiles and require a fresh matched investigation before another change.

## 2026-07-15 Coroutine Success-Prefix Checkpoint

Matched post-context GC baselines measured 23,358,771.956 B/op for coroutine yield/resume, 7,278,434.201 B/op for host calls, and 12,073,882.783 B/op for JVM-to-Lua calls. JFR profiles showed scalar wrapper and general result-list allocation across all three controls, so they reject a broad value-model or list-copy rewrite. The coroutine-only path also built `listOf(true) + results` and then copied that combined list again into `LuaReturn` for every successful yield or return.

Lua 5.5's `lcorolib.c` `luaB_coresume` receives the yielded/result values from `auxresume`, inserts one true boolean before them, and returns exactly `nres + 1` values. `LuaReturn` now provides a prefixed-values factory that allocates one correctly sized result list and snapshots the remaining values once. Every successful coroutine return, yield, and host-yield continuation uses it. Existing coroutine tests cover repeated yields, nil holes, cyclic tables, and host continuations; a new Java API test verifies prefix order, nil preservation, and independence from later mutation of the source list.

The matched GC-profiler checkpoint measured:

| Benchmark | Before allocation | After allocation | Delta |
| --- | ---: | ---: | ---: |
| 10,000 coroutine yield/resume cycles | 23,358,771.956 B/op | 22,638,675.936 B/op | -3.1% |
| 10,000 host calls | 7,278,434.201 B/op | 7,278,458.189 B/op | effectively unchanged |
| 10,000 JVM-to-Lua calls | 12,073,882.783 B/op | 12,073,882.980 B/op | effectively unchanged |

The coroutine delta is about 72 B per resume cycle and matches the removed intermediate list/backing storage. Focused time remained effectively flat at 15,035.869 µs/op before and 15,084.243 µs/op after, so this is an allocation-only claim.

The full twelve-workload regression screen measured:

| Benchmark | Score (µs/op) | 99.9% error |
| --- | ---: | ---: |
| Compile numeric loop | 7.954 | ±0.522 |
| Execute numeric loop | 1,079.468 | ±259.426 |
| Execute 10,000 Lua calls | 2,512.397 | ±621.208 |
| Execute 10,000 closure-counter calls | 2,266.345 | ±183.532 |
| Execute 10,000 host calls | 2,648.331 | ±144.670 |
| Execute 10,000 `__index` metamethod calls | 2,796.297 | ±281.183 |
| Execute 10,000 JVM-to-Lua calls | 3,668.653 | ±604.439 |
| Execute 10,000 method calls | 5,062.565 | ±693.894 |
| Execute recursive `fib(20)` | 6,470.889 | ±925.545 |
| Execute 10,000 table writes and reads | 2,933.636 | ±928.915 |
| Execute 1,000 growing string concatenations | 3,955.308 | ±814.821 |
| Execute 10,000 coroutine yield/resume cycles | 14,435.184 | ±2,601.592 |

The complete Gradle test suite passes. The next M19 package should expand application-shaped coverage with vararg/multi-return flow and a table-backed entity-update kernel before selecting another optimization.
