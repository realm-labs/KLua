# KLua Performance Guide

KLua is an interpreter-first runtime. Its v1 performance policy is regression-based: measurements from different machines are not an absolute SLA and should not be compared without matching the environment.

## Workload guidance

- Reuse loaded `LuaChunk` or exported Lua functions when source does not change; parsing and compilation are measured separately from execution.
- Keep long-lived application data in Lua tables or host userdata instead of rebuilding large boundary graphs on every call.
- Prefer coarse host functions over one host callback per trivial scalar operation when the interface permits it.
- Use `pushBytes` and `toBytes` for binary strings so arbitrary bytes do not make unnecessary text round trips.
- Leave debugging and execution observers disabled unless they are needed. Instruction accounting is separately configurable through `instructionLimit`.
- Treat coroutine suspension, debug snapshots, public result lists, and generic host-table conversion as materialization boundaries rather than zero-copy paths.

Correctness comes first. Do not replace normal operations with raw/internal access, depend on traversal accidents, or bypass instruction and debug policy for a benchmark.

## Build and list benchmarks

Set `JAVA_HOME` to the JDK being measured. JDK 17 is the canonical v1 runtime.

```text
./gradlew :klua-jmh:jmhJar
java -jar klua-jmh/build/libs/klua-jmh-0.1.0-SNAPSHOT-jmh.jar -l
```

The suite separates compilation, numeric VM execution, Lua/host/JVM call directions, tables, metamethods, closures, methods, recursion, strings, coroutines, varargs, an entity-update kernel, debug observers/hooks/breakpoints, and instruction budgets.

Run the configured suite with Gradle:

```text
./gradlew :klua-jmh:jmh
```

Or write comparable timing and allocation CSV files from the generated JAR:

```text
java -jar klua-jmh/build/libs/klua-jmh-0.1.0-SNAPSHOT-jmh.jar \
  -rf csv -rff klua-jmh/build/baseline/jdk17-timing.csv

java -jar klua-jmh/build/libs/klua-jmh-0.1.0-SNAPSHOT-jmh.jar \
  -prof gc -rf csv -rff klua-jmh/build/baseline/jdk17-gc.csv
```

On Windows PowerShell, enter each command on one line or replace the displayed continuation style with PowerShell backticks.

## Comparable measurements

Record the commit, CPU, memory, OS, JVM vendor/build, JVM options, power policy, JMH version, threads, forks, warmup, measurement time, profiler, and benchmark filter. Hold them constant for before/after runs. Close noisy applications and avoid thermal or power-policy changes.

A short single-iteration run proves that a benchmark executes; it is not performance evidence. For a suspected regression, use matched runs and reproduce the result twice. Inspect confidence intervals rather than comparing point estimates alone.

Allocation from `-prof gc` is often more stable than timing for small interpreter workloads. Use JFR or another profiler to attribute a measured change, but do not treat allocation samples as exact byte totals.

## Release gates and current results

The exact regression gates, accepted pre-release checkpoints, environment, commands, confidence intervals, allocation figures, and profiling evidence live in [the benchmark baseline](KLua_Benchmark_Baseline.md). The final v1 release-candidate baseline has not yet been accepted.

In summary, a release regression requires more than a noisy higher point: it must exceed the documented threshold and combined uncertainty and reproduce in matched runs. Allocation increases on stable workloads require a fix or reviewed rationale. Debug-disabled and budget-disabled controls have their own overhead gates.

Lua 5.5 and LuaJ comparisons are informational unless the project later adopts an explicit cross-runtime target.
