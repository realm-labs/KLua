# KLua Conformance Gaps

This note tracks known Lua 5.5 gaps that are too broad to treat as incidental test failures. Keep it source-backed and concise; move items out when they are implemented and covered by tests.

## Language And VM

- `<close>` local declarations are parsed; statically nil/false close locals compile as no-op close values, dynamic close initializers are runtime-checked to allow only nil/false, and statically non-false to-be-closed variable semantics are still rejected by the compiler because `__close` handling is not implemented.

## Debug Library

- Optional thread arguments for `debug.traceback`, `debug.getinfo`, `debug.getlocal`, `debug.setlocal`, `debug.sethook`, and `debug.gethook` are implemented for explicit current threads and suspended, normal, and empty-stack KLua coroutine states such as dead coroutines. Broader cross-thread debug behavior beyond these KLua thread states is not implemented yet.
- `debug.getinfo(..., "n")` resolves common source call-site names for local, global, upvalue, string constant callee, field, unknown computed field keys, integer-indexed field, method, generic-for iterator, `__call`, `__index`, `__newindex`, and operator metamethod calls, but broader Lua 5.5 name inference for indirect call patterns is not complete yet.

## Package Library

- Native C module loading is intentionally unavailable in the pure Kotlin runtime. `package.loadlib` exposes the Lua fallback-style unsupported result, and the C/C-root searcher slots can report `package.cpath` misses or dynamic-loading failures but cannot create native loaders.

## IO Library

- The official Lua 5.5 `linit.c` standard-library list includes `LUA_IOLIBNAME`; KLua now has an initial pure-Kotlin `io` library with basic file handles, file-backed line iteration, common file read formats, Lua-style numeric write formatting and capped numeric read scanning, explicit file-backed and standard default input/output routing, non-closing standard handles, no-op buffer-mode validation, and stream-backed read/write-mode `io.popen`, but broader `liolib.c` edge-case and platform-mode parity still needs conformance hardening.

## Strings And UTF-8

- KLua strings are still JVM-string-backed rather than byte-string-faithful in all standard-library paths. This leaves Lua 5.5 byte-level behavior incomplete for cases such as validating or iterating broader malformed byte sequences.
- `string.dump`, binary `load`, and binary `loadfile` can round-trip Lua functions through KLua's internal bytecode package format using the stdlib raw-byte string helpers, and the `strip` flag removes KLua debug metadata from dumped packages. General Lua byte-string fidelity remains incomplete, so dumped chunks are not yet portable Lua byte strings.
