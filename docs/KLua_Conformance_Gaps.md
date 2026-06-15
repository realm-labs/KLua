# KLua Conformance Gaps

This note tracks known Lua 5.5 gaps that are too broad to treat as incidental test failures. Keep it source-backed and concise; move items out when they are implemented and covered by tests.

## Language And VM

- Full cross-scope `goto` and label semantics are still being hardened; current support covers AST representation, compilation, VM execution, end-of-block labels, exported pending gotos, and close-aware escaping jumps.
- `<close>` local declarations are parsed, then rejected by the compiler because to-be-closed variable semantics are not implemented.

## Debug Library

- Optional thread arguments for `debug.traceback`, `debug.getinfo`, `debug.getlocal`, and `debug.setlocal` are implemented for suspended KLua coroutine threads. Optional thread arguments for `debug.sethook` and `debug.gethook`, plus broader cross-thread debug hook behavior, are not implemented yet.

## Package Library

- Native C module loading is intentionally unavailable in the pure Kotlin runtime. `package.loadlib` exposes the Lua fallback-style unsupported result, and the C/C-root searcher slots can report `package.cpath` misses or dynamic-loading failures but cannot create native loaders.
