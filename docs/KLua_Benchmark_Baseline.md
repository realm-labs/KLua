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
