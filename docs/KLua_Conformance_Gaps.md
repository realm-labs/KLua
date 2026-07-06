# KLua Conformance Gaps

This note tracks known Lua 5.5 gaps that are too broad to treat as incidental test failures. Keep it source-backed and concise; move items out when they are implemented and covered by tests.

## Language And VM

- Full cross-scope `goto` and label semantics are still being hardened; current support covers AST representation, compilation, VM execution, end-of-block labels, exported pending gotos, and close-aware escaping jumps.
- Lua 5.5 `global` variable declarations are partially supported for regular and `<const>` named/wildcard scopes, initialized declarations, local-shadowing resolution, plain function declaration binding, and `global function` declarations with Lua-style already-defined checks. Full lexical `_ENV` rebinding semantics are not implemented yet; KLua can read, index, replace, and capture `_ENV` for ordinary global-name reads and writes, but initialized `global` declarations and `global function` declarations do not yet lower through lexical `_ENV`.
- `<close>` local declarations are parsed, then rejected by the compiler because to-be-closed variable semantics are not implemented.

## Debug Library

- Optional thread arguments for `debug.traceback`, `debug.getinfo`, `debug.getlocal`, `debug.setlocal`, `debug.sethook`, and `debug.gethook` are implemented for explicit current threads and suspended, normal, and empty-stack KLua coroutine states such as dead coroutines. Broader cross-thread debug behavior beyond these KLua thread states is not implemented yet.
- `debug.getinfo(..., "n")` resolves common source call-site names for local, global, upvalue, field, computed field, integer-indexed field, method, generic-for iterator, `__call`, `__index`, `__newindex`, and operator metamethod calls, but broader Lua 5.5 name inference for indirect call patterns is not complete yet.

## Package Library

- Native C module loading is intentionally unavailable in the pure Kotlin runtime. `package.loadlib` exposes the Lua fallback-style unsupported result, and the C/C-root searcher slots can report `package.cpath` misses or dynamic-loading failures but cannot create native loaders.

## Strings And UTF-8

- KLua strings are still JVM-string-backed rather than byte-string-faithful in all standard-library paths. This leaves Lua 5.5 byte-level behavior incomplete for cases such as validating or iterating broader malformed byte sequences.
- `string.dump`, binary `load`, and binary `loadfile` can round-trip Lua functions through KLua's internal bytecode package format using the stdlib raw-byte string helpers, and the `strip` flag removes KLua debug metadata from dumped packages. General Lua byte-string fidelity remains incomplete, so dumped chunks are not yet portable Lua byte strings.
