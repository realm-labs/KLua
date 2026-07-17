# KLua 1.0.0 Draft Release Notes

These notes describe the current v1 release candidate. They do not announce a release: the repository remains at `0.1.0-SNAPSHOT`, and no tag or remote publication is authorized yet.

## Highlights

- Pure Kotlin Lua 5.5 source runtime targeting JVM 17 and newer.
- Source-to-KLua-bytecode compiler and register VM with closures, upvalues, metamethods, coroutines, to-be-closed variables, structured errors, and deterministic Lua-object lifecycle behavior.
- Java-friendly low-level `LuaState` API, high-level `Lua` facade, Kotlin conveniences, userdata bindings, bytecode resource loading, production limits, and standard-library capability gates.
- Pure-Kotlin base, coroutine, debug, IO, math, OS, package, string, table, and UTF-8 library implementations with official-source-backed conformance coverage.
- Source debugger, interactive CLI, live DAP library, and standalone `klua --dap` stdio adapter.
- Byte-oriented strings, tagged VM slots, hybrid tables, guarded caches, stack-range calls, and fast/instrumented dispatch, qualified by the accepted canonical JDK 17 baseline.

## Artifacts and tooling

The supported Maven components are `klua-api`, `klua-kotlin`, `klua-stdlib`, `klua-debug`, `klua-dap`, and `klua-tools`; `klua-core` is a required runtime implementation component rather than an embedder compatibility surface. Every component has a binary JAR, sources JAR, Maven POM, automatic module name, implementation version, and MIT license metadata.

The `klua-tools` ZIP/TAR distributions contain `bin/klua` and `bin/klua.bat`. The launcher supports:

```text
klua --compile <script.lua> <output.kluac>
klua --debug <script.lua-or-bytecode> [args...]
klua --dap
```

## Compatibility boundary

The checked `.api` files define the reviewed v1 JVM ABI for supported public modules. JVM 17 is the minimum runtime. KLua targets Lua 5.5 source behavior only and does not provide older-language modes or load official PUC Lua `.luac` files. KLua's `.kluac` format is internal and independently validated.

## Known adaptations

KLua is pure JVM software, so native C module loading, C ABI-dependent representations, native errno details, POSIX signal wait status, process-wide C locale categories, and installation-specific `libm`/`strftime` details are not reproduced exactly. IO, OS, locale, time, numeric-formatting, byte-string, and lifecycle adaptations are classified in [the conformance matrix](KLua_Conformance_Gaps.md). The standalone DAP adapter supports launch over stdio; attach and an editor extension are not bundled.

## Verification

Run the complete local release-candidate matrix on JDK 17:

```text
./gradlew releaseCandidateCheck
```

The accepted performance environment, reproduction commands, timing confidence intervals, allocation figures, and gate decisions are recorded in [the benchmark baseline](KLua_Benchmark_Baseline.md).
