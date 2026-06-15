# KLua Conformance Gaps

This note tracks known Lua 5.5 gaps that are too broad to treat as incidental test failures. Keep it source-backed and concise; move items out when they are implemented and covered by tests.

## Language And VM

- `goto` and labels are tokenized but not represented in the AST or compiled.
- `<close>` local declarations are parsed, then rejected by the compiler because to-be-closed variable semantics are not implemented.

## Debug Library

- Optional thread arguments for `debug.traceback`, `debug.getinfo`, `debug.getlocal`, `debug.setlocal`, `debug.sethook`, and `debug.gethook` are not implemented yet.
- `debug.getuservalue` and `debug.setuservalue` are not implemented; host userdata currently has no Lua uservalue storage.
- `debug.setmetatable` is table-backed only; Lua 5.5 allows setting metatables on any value type supported by the runtime.

## Package Library

- Native C module loading is intentionally unavailable in the pure Kotlin runtime. `package.loadlib` exposes the Lua fallback-style unsupported result, and the C/C-root searcher slots can report `package.cpath` misses or dynamic-loading failures but cannot create native loaders.
