# KLua JMH Baseline

This file records comparable benchmark checkpoints used to select optimization work. Results are local evidence, not cross-machine performance claims.

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
