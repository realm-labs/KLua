# KLua Conformance Gaps

This note tracks known Lua 5.5 gaps that are too broad to treat as incidental test failures. Keep it source-backed and concise; move items out when they are implemented and covered by tests.

## Language And VM

- Full cross-scope `goto` and label semantics are still being hardened; current support covers AST representation, compilation, VM execution, end-of-block labels, exported pending gotos, and close-aware escaping jumps.
- `<close>` local declarations are parsed, then rejected by the compiler because to-be-closed variable semantics are not implemented.

## Debug Library

- Optional thread arguments for `debug.traceback`, `debug.getinfo`, `debug.getlocal`, `debug.setlocal`, `debug.sethook`, and `debug.gethook` are implemented for suspended KLua coroutine threads. Broader cross-thread debug behavior beyond suspended KLua coroutine threads is not implemented yet.
- `debug.getinfo(..., "n")` resolves common source call-site names for local, global, upvalue, field, integer-indexed field, method, generic-for iterator, `__call`, `__index`, `__newindex`, and operator metamethod calls, but broader Lua 5.5 name inference for indirect call patterns is not complete yet.

## Package Library

- Native C module loading is intentionally unavailable in the pure Kotlin runtime. `package.loadlib` exposes the Lua fallback-style unsupported result, and the C/C-root searcher slots can report `package.cpath` misses or dynamic-loading failures but cannot create native loaders.

## Strings And UTF-8

- KLua strings are still JVM-string-backed rather than byte-string-faithful in all standard-library paths. This leaves Lua 5.5 byte-level behavior incomplete for cases such as `utf8.char` accepting extended UTF-8 values up to `MAXUTF` and validating or iterating malformed byte sequences.
- `string.dump` and binary chunk loading through `load`/`loadfile` are not implemented yet. KLua has an internal bytecode package format, but the standard-library string path is still text-only and cannot round-trip dumped functions as Lua byte strings.
