# KLua Conformance Gaps

This note tracks known Lua 5.5 gaps that are too broad to treat as incidental test failures. Keep it source-backed and concise; move items out when they are implemented and covered by tests.

This list is evidence and scope tracking, not a FIFO task queue or a commit map. Select related gaps into a named campaign in `docs/KLua_Codex_Goal.md`, with an owning subsystem or Lua source helper chain, affected entry points, a case matrix, verification, and an exit condition. Group fixes and tests for the same semantic rule; do not create a separate work package or commit for every probe.

## Package Library

- Native C module loading is intentionally unavailable in the pure Kotlin runtime. `package.loadlib` exposes the Lua fallback-style unsupported result, and the C/C-root searcher slots can report `package.cpath` misses or dynamic-loading failures but cannot create native loaders.

## IO Library

- The official Lua 5.5 `linit.c` standard-library list includes `LUA_IOLIBNAME`; KLua now has an initial pure-Kotlin `io` library with basic file handles, file-backed line iteration including fourth-result early-exit closing for `io.lines(filename)`, common file read formats, Lua-style numeric write formatting and capped numeric read scanning, source-backed open/default routing, seek boundaries, close/flush lifecycle and result shapes, and `setvbuf` argument validation, plus non-closing standard handles and stream-backed read/write-mode `io.popen`. Actual buffer reconfiguration remains a no-op, and broader `liolib.c` edge-case and platform-mode parity still needs conformance hardening.

## Strings And UTF-8

- KLua strings are still JVM-string-backed rather than byte-string-faithful in all standard-library paths. This leaves Lua 5.5 byte-level behavior incomplete for broader byte-sensitive standard-library cases. The raw-byte `utf8.offset`, `utf8.len`, `utf8.codepoint`, and `utf8.codes` paths are source-backed for representable malformed layouts; the remaining gap is general byte-string fidelity rather than an open UTF-8 entry-point family.
- `string.dump`, binary `load`, and binary `loadfile` can round-trip Lua functions through KLua's internal bytecode package format using the stdlib raw-byte string helpers, and the `strip` flag removes KLua debug metadata from dumped packages. General Lua byte-string fidelity remains incomplete, so dumped chunks are not yet portable Lua byte strings.
