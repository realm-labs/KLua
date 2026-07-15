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
